package dev.atoll.app.store

import dev.atoll.app.core.Row
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** Transactional test double, including the same retention rule as RoomRowStore. */
class MemoryRowStore(private val maxRows: Int = 20_000) : RowStore {
    private val mutex = Mutex()
    private val data = mutableMapOf<String, MutableMap<Long, Row>>()
    private val channels = mutableMapOf<String, CachedChannel>()
    private val revision = MutableStateFlow(0L)

    override suspend fun channel(channelId: String): CachedChannel = mutex.withLock {
        channels[channelId] ?: CachedChannel(ChannelMeta(channelId), emptyList())
    }

    override suspend fun rows(channelId: String, beforeSeq: Long, limit: Int): List<Row> = mutex.withLock {
        data[channelId].orEmpty().values.filter { it.seq < beforeSeq }.sortedByDescending { it.seq }.take(limit).asReversed()
    }

    override suspend fun commit(channelId: String, rows: List<Row>, coverage: List<CoverageRange>, meta: ChannelMeta?): List<Row> = mutex.withLock {
        val held = data.getOrPut(channelId) { mutableMapOf() }
        val unique = rows.distinctBy { it.seq }.sortedBy { it.seq }
        val inserted = unique.filter { it.seq !in held }
        inserted.forEach { held[it.seq] = it }
        val previous = channels[channelId] ?: CachedChannel(ChannelMeta(channelId), emptyList())
        var ranges = mergeCoverage(previous.coverage + coverage + unique.map { CoverageRange(it.seq, it.seq) })
        if (held.size > maxRows) {
            val floor = held.keys.sortedDescending()[maxRows - 1]
            held.keys.removeAll { it < floor }
            ranges = clippedCoverage(ranges, floor)
        }
        val next = meta ?: previous.meta
        channels[channelId] = CachedChannel(next.copy(
            headSeq = maxOf(next.headSeq, unique.lastOrNull()?.seq ?: 0),
            lastActivity = maxOf(next.lastActivity, unique.maxOfOrNull { it.envelope.tsReceived.takeIf { time -> time > 0 } ?: it.envelope.ts } ?: 0),
        ), ranges)
        revision.value++
        inserted
    }

    override suspend fun clear() = mutex.withLock { data.clear(); channels.clear(); revision.value++; Unit }

    suspend fun awaitRows(channelId: String, predicate: (List<Row>) -> Boolean) {
        withTimeout(10_000) { revision.first { predicate(rows(channelId, limit = maxRows)) } }
    }
}

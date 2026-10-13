package dev.atoll.app.store

import dev.atoll.app.core.Row

/** A scanned interval, including sequences invisible to this account. */
data class CoverageRange(val low: Long, val high: Long) {
    init { require(low > 0 && high >= low) }
}

data class ChannelMeta(
    val channelId: String,
    val headSeq: Long = 0,
    val lastActivity: Long = 0,
    val readPosition: Long = 0,
)

data class CachedChannel(val meta: ChannelMeta, val coverage: List<CoverageRange>)

/** Rows and their scan proof must be committed in the same transaction. */
interface RowStore {
    suspend fun channel(channelId: String): CachedChannel
    /** Descending lookup; returns rows in ascending sequence order. beforeSeq is exclusive. */
    suspend fun rows(channelId: String, beforeSeq: Long = Long.MAX_VALUE, limit: Int = 200): List<Row>
    /** Returns only newly inserted rows; existing immutable ledger rows are ignored. */
    suspend fun commit(
        channelId: String,
        rows: List<Row> = emptyList(),
        coverage: List<CoverageRange> = emptyList(),
        meta: ChannelMeta? = null,
    ): List<Row>
    /** AppGraph clears the account's database when the attach boot changes. */
    suspend fun clear()
}

fun mergeCoverage(ranges: List<CoverageRange>): List<CoverageRange> {
    val merged = mutableListOf<CoverageRange>()
    for (range in ranges.sortedBy { it.low }) {
        val previous = merged.lastOrNull()
        if (previous == null || (range.low > previous.high && range.low - previous.high > 1)) {
            merged += range
        } else {
            merged[merged.lastIndex] = CoverageRange(previous.low, maxOf(previous.high, range.high))
        }
    }
    return merged
}

/** Highest missing interval above the durable cache floor. Empty coverage means initial tail. */
fun tailGap(ranges: List<CoverageRange>, head: Long): CoverageRange? {
    if (ranges.isEmpty() || head <= 0) return null
    var cursor = head
    for (range in ranges.asReversed()) {
        if (range.low > cursor) continue
        if (cursor > range.high) return CoverageRange(range.high + 1, cursor)
        cursor = range.low - 1
    }
    // Do not hydrate below the first cached interval: that is older paging's frontier.
    return null
}

internal fun clippedCoverage(ranges: List<CoverageRange>, floor: Long): List<CoverageRange> =
    ranges.filter { it.high >= floor }.map { CoverageRange(maxOf(it.low, floor), it.high) }

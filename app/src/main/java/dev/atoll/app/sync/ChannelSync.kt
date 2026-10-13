package dev.atoll.app.sync

import dev.atoll.app.core.DownFrame
import dev.atoll.app.core.HistoryPage
import dev.atoll.app.core.Origin
import dev.atoll.app.core.Row
import dev.atoll.app.core.RowBatch
import dev.atoll.app.core.WireClient
import dev.atoll.app.core.WireException
import dev.atoll.app.core.WireState
import dev.atoll.app.core.string
import dev.atoll.app.store.CachedChannel
import dev.atoll.app.store.ChannelMeta
import dev.atoll.app.store.CoverageRange
import dev.atoll.app.store.RowStore
import dev.atoll.app.store.tailGap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class SyncState(
    val cached: Boolean = false,
    val connected: Boolean = false,
    val generation: Long = 0,
    val headSeq: Long = 0,
    val loading: Boolean = false,
    val current: Boolean = false,
    val error: String? = null,
)

data class PagingResult(val nextBeforeSeq: Long, val hasOlder: Boolean, val rows: Int, val origin: Origin)

/**
 * One channel's single writer. SyncHub is the only wire subscriber and calls accept.
 * Subscribe to batches before start(); start emits the local window before network work.
 * Network waits run outside the mailbox, so a refill never blocks live persistence.
 * Durable coverage (including its lower intervals) is the refill continuation cursor.
 */
class ChannelSync(
    val channelId: String,
    private val wire: WireClient,
    private val store: RowStore,
    parentScope: CoroutineScope,
) {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job + Dispatchers.Default)
    private val commands = Channel<Command>(256)
    private val mutableBatches = MutableSharedFlow<RowBatch>(extraBufferCapacity = 32)
    val batches: SharedFlow<RowBatch> = mutableBatches.asSharedFlow()
    private val mutableState = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = mutableState.asStateFlow()

    private lateinit var cached: CachedChannel
    private var opened = false
    private var generation = 0L
    private var connected = false
    private var needsInitialTail = false
    private var error: String? = null
    private var requestNumber = 0L
    private var fetching: Fetch? = null
    private var networkJob: Job? = null
    private val olderQueue = ArrayDeque<Older>()
    private val liveRows = mutableListOf<Row>()
    private var flushJob: Job? = null

    init {
        scope.launch {
            try {
                cached = store.channel(channelId)
                needsInitialTail = cached.coverage.isEmpty()
                publish()
                for (command in commands) {
                    try {
                        handle(command)
                    } catch (failure: Exception) {
                        if (failure is CancellationException) throw failure
                        error = failure.message ?: failure.javaClass.simpleName
                        command.fail(failure)
                        publish()
                    }
                }
            } finally {
                commands.cancel()
                networkJob?.cancel()
                flushJob?.cancel()
                val closed = CancellationException("Channel sync closed")
                fetching?.older?.reply?.completeExceptionally(closed)
                olderQueue.forEach { it.reply.completeExceptionally(closed) }
                while (true) { (commands.tryReceive().getOrNull() ?: break).fail(closed) }
            }
        }
    }

    suspend fun start() = acknowledge { Start(it) }
    suspend fun retry() = acknowledge { Retry(it) }
    suspend fun markRead(seq: Long) { require(seq >= 0); acknowledge { Read(seq, it) } }
    suspend fun loadOlder(beforeSeq: Long, limit: Int = 200): PagingResult {
        require(beforeSeq > 0 && limit in 1..200)
        val reply = CompletableDeferred<PagingResult>()
        commands.send(Older(beforeSeq, limit, reply))
        return reply.await()
    }

    suspend fun accept(state: WireState) { commands.send(Connection(state)) }
    suspend fun accept(frame: DownFrame) { commands.send(Frame(frame)) }
    /** Cancels network work; AppGraph must stop the hub before clearing/changing databases. */
    fun close() { scope.cancel() }
    suspend fun stop() { job.cancelAndJoin() }

    private suspend fun acknowledge(command: (CompletableDeferred<Unit>) -> Command) {
        val reply = CompletableDeferred<Unit>()
        commands.send(command(reply))
        reply.await()
    }

    private suspend fun handle(command: Command) {
        when (command) {
            is Start -> {
                if (!opened) {
                    val rows = store.rows(channelId, limit = 4500)
                    mutableBatches.emit(RowBatch(channelId, rows, Origin.CACHE))
                    opened = true
                }
                error = null
                connection(wire.state.value)
                command.reply.complete(Unit)
            }
            is Retry -> { error = null; command.reply.complete(Unit) }
            is Read -> {
                store.commit(channelId, meta = cached.meta.copy(readPosition = maxOf(cached.meta.readPosition, command.seq)))
                cached = store.channel(channelId)
                command.reply.complete(Unit)
            }
            is Connection -> connection(command.state)
            is Frame -> frame(command.frame)
            is Older -> {
                check(opened) { "Start the channel before paging" }
                olderQueue.addLast(command)
            }
            is Completed -> if (fetching?.number == command.fetch.number) {
                val fetch = command.fetch
                fetching = null
                networkJob = null
                if (command.failure != null) {
                    error = command.failure.message ?: "History request failed"
                    fetch.older?.reply?.completeExceptionally(command.failure)
                } else {
                    installPage(fetch, requireNotNull(command.page))
                }
            }
            FlushLive -> flushLive()
        }
        schedule()
        publish()
    }

    private suspend fun connection(state: WireState) {
        if (state is WireState.Attached) {
            if (!connected || generation != state.generation) {
                cancelFetch("Connection generation changed")
                generation = state.generation
                connected = true
                error = null
            }
            val meta = (state.receipt["history_meta"] as? JsonArray)?.filterIsInstance<JsonObject>()
                ?.firstOrNull { it["channel_id"].string() == channelId }
            if (meta != null) {
                store.commit(channelId, meta = cached.meta.copy(
                    headSeq = maxOf(cached.meta.headSeq, meta.number("head_seq")),
                    lastActivity = maxOf(cached.meta.lastActivity, meta.number("last_activity")),
                ))
                cached = store.channel(channelId)
            }
        } else {
            connected = false
            cancelFetch("Wire is disconnected")
        }
    }

    private fun cancelFetch(reason: String) {
        networkJob?.cancel()
        networkJob = null
        val failure = WireException("history_before", "disconnected", reason)
        fetching?.older?.reply?.completeExceptionally(failure)
        fetching = null
        olderQueue.forEach { it.reply.completeExceptionally(failure) }
        olderQueue.clear()
    }

    private suspend fun frame(frame: DownFrame) {
        when (frame) {
            is DownFrame.Feed -> if (frame.ref.isEmpty() && frame.row.channelId == channelId) {
                liveRows += frame.row
                if (liveRows.size >= 200) flushLive()
                else if (flushJob == null) flushJob = scope.launch { delay(16); commands.send(FlushLive) }
            }
            is DownFrame.Checkpoint -> if (frame.channelId == channelId && connected && frame.generation == generation &&
                frame.scanLowSeq > 0 && frame.scannedSeq >= frame.scanLowSeq) {
                // A durable scan proof must never overtake its buffered live rows.
                flushLive()
                store.commit(channelId, coverage = listOf(CoverageRange(frame.scanLowSeq, frame.scannedSeq)),
                    meta = cached.meta.copy(headSeq = maxOf(cached.meta.headSeq, frame.scannedSeq)))
                cached = store.channel(channelId)
            }
            is DownFrame.ObserveEnded -> if (frame.channelId == channelId) {
                flushLive()
                connected = false
                cancelFetch(frame.reason)
                error = frame.reason
            }
            else -> Unit
        }
    }

    private suspend fun flushLive() {
        flushJob?.cancel()
        flushJob = null
        if (liveRows.isEmpty()) return
        val rows = liveRows.distinctBy { it.seq }.sortedBy { it.seq }
        val inserted = store.commit(channelId, rows)
        liveRows.clear()
        cached = store.channel(channelId)
        if (opened && inserted.isNotEmpty()) mutableBatches.emit(RowBatch(channelId, inserted, Origin.LIVE))
    }

    private suspend fun schedule() {
        if (!opened || fetching != null) return
        // User paging takes the next page slot, rather than waiting for a whole refill.
        while (olderQueue.isNotEmpty()) {
            val older = olderQueue.removeFirst()
            if (older.reply.isCancelled) continue
            var before = older.before
            while (before > 1) {
                val range = cached.coverage.lastOrNull { before - 1 in it.low..it.high } ?: break
                val rows = store.rows(channelId, before, older.limit).filter { it.seq >= range.low }
                if (rows.isNotEmpty()) {
                    mutableBatches.emit(RowBatch(channelId, rows, Origin.CACHE, olderThanWindow = true))
                    older.reply.complete(PagingResult(rows.first().seq, rows.first().seq > 1, rows.size, Origin.CACHE))
                    break
                }
                before = range.low // Entire scanned interval contains no visible rows.
            }
            if (older.reply.isCompleted) continue
            if (before <= 1) { older.reply.complete(PagingResult(1, false, 0, Origin.CACHE)); continue }
            if (!connected) {
                older.reply.completeExceptionally(WireException("history_before", "disconnected", "Wire is disconnected"))
                continue
            }
            fetch(before, "user-demand", older)
            return
        }
        if (!connected || error != null) return
        if (needsInitialTail) {
            // before=0 means current head, also when history_meta was not included.
            fetch(cached.meta.headSeq.takeIf { it > 0 }?.plus(1) ?: 0, "initial-tail")
            return
        }
        val gap = tailGap(cached.coverage, cached.meta.headSeq) ?: return
        fetch(gap.high + 1, "hydrate")
    }

    private fun fetch(before: Long, purpose: String, older: Older? = null) {
        val fetch = Fetch(++requestNumber, before, purpose, older)
        val requestGeneration = generation
        fetching = fetch
        networkJob = scope.launch {
            try {
                val page = wire.history(buildJsonObject {
                    put("channel_id", channelId)
                    put("before_seq", before)
                    put("limit", older?.limit ?: 200)
                    put("byte_limit", 256 * 1024)
                    put("generation", requestGeneration)
                    put("purpose", purpose)
                    put("priority", "foreground")
                })
                commands.send(Completed(fetch, page, null))
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                commands.send(Completed(fetch, null, failure))
            }
        }
    }

    private suspend fun installPage(fetch: Fetch, page: HistoryPage) {
        val code = page.end["error_code"].string()
        if (code.isNotEmpty()) {
            val failure = WireException("history_before", code, page.end["error_detail"].string())
            error = failure.message
            fetch.older?.reply?.completeExceptionally(failure)
            return
        }
        try {
            require(page.rows.all { it.channelId == channelId && (fetch.before == 0L || it.seq < fetch.before) })
            // An exhausted empty page uses low=0: it scanned the entire remaining prefix.
            val low = page.scanLowSeq.takeIf { it > 0 } ?: if (!page.hasOlder && page.scanHighSeq > 0) 1L else 0L
            val coverage = if (low > 0 && page.scanHighSeq >= low) listOf(CoverageRange(low, page.scanHighSeq)) else emptyList()
            require(!page.hasOlder || (coverage.isNotEmpty() && (fetch.before == 0L || low < fetch.before))) {
                "History page did not advance its scan cursor"
            }
            flushLive()
            val inserted = store.commit(channelId, page.rows, coverage,
                cached.meta.copy(headSeq = maxOf(cached.meta.headSeq, page.headSeq)))
            cached = store.channel(channelId)
            if (fetch.purpose == "initial-tail") needsInitialTail = false
            val rows = if (fetch.older != null) page.rows.distinctBy { it.seq }.sortedBy { it.seq } else inserted
            if (rows.isNotEmpty()) mutableBatches.emit(RowBatch(channelId, rows, Origin.HISTORY, fetch.older != null))
            fetch.older?.reply?.complete(PagingResult(page.nextBeforeSeq, page.hasOlder, rows.size, Origin.HISTORY))
            // A malformed scan cannot turn into an infinite auto-refill loop.
            if (fetch.purpose == "hydrate" && coverage.isEmpty()) error = "History page supplied no scan coverage"
        } catch (failure: Exception) {
            fetch.older?.reply?.completeExceptionally(failure)
            throw failure
        }
    }

    private fun publish() {
        mutableState.value = SyncState(cached = opened, connected = connected, generation = generation,
            headSeq = cached.meta.headSeq, loading = fetching != null,
            current = connected && !needsInitialTail && error == null && tailGap(cached.coverage, cached.meta.headSeq) == null,
            error = error)
    }

    private sealed interface Command {
        fun fail(failure: Throwable) {}
    }
    private data class Start(val reply: CompletableDeferred<Unit>) : Command { override fun fail(failure: Throwable) { reply.completeExceptionally(failure) } }
    private data class Retry(val reply: CompletableDeferred<Unit>) : Command { override fun fail(failure: Throwable) { reply.completeExceptionally(failure) } }
    private data class Read(val seq: Long, val reply: CompletableDeferred<Unit>) : Command { override fun fail(failure: Throwable) { reply.completeExceptionally(failure) } }
    private data class Connection(val state: WireState) : Command
    private data class Frame(val frame: DownFrame) : Command
    private data class Older(val before: Long, val limit: Int, val reply: CompletableDeferred<PagingResult>) : Command { override fun fail(failure: Throwable) { reply.completeExceptionally(failure) } }
    private data class Completed(val fetch: Fetch, val page: HistoryPage?, val failure: Exception?) : Command
    private data object FlushLive : Command
    private data class Fetch(val number: Long, val before: Long, val purpose: String, val older: Older?)
}

internal fun JsonObject.number(key: String): Long = (this[key] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L

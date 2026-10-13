package dev.atoll.app.sync

import dev.atoll.app.core.*
import dev.atoll.app.store.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ChannelSyncTest {
    @Test fun coldStartEmitsCacheBeforeInitialTail() = runBlocking {
        val fixture = Fixture(head = 12)
        try {
            fixture.start()
            assertEquals(Origin.CACHE, fixture.batch().origin)
            val call = fixture.wire.call()
            assertEquals(13L, call.before)
            assertEquals("initial-tail", call.payload["purpose"].string())
            call.reply.complete(page(9, 12, head = 12))
            assertEquals((9L..12).toList(), fixture.batch().rows.map { it.seq })
            fixture.current()
            assertEquals(listOf(CoverageRange(9, 12)), fixture.store.channel("c").coverage)
        } finally { fixture.stop() }
    }

    @Test fun warmStartRefillsUsingScannedRangesEvenWhenNoRowsAreVisible() = runBlocking {
        val store = MemoryRowStore()
        store.commit("c", listOf(row(3), row(5)), listOf(CoverageRange(1, 5)))
        val fixture = Fixture(head = 11, store = store)
        try {
            fixture.start()
            assertEquals(listOf(3L, 5L), fixture.batch().rows.map { it.seq })
            val top = fixture.wire.call()
            assertEquals(12L, top.before)
            top.reply.complete(page(9, 11, head = 11, rows = emptyList()))
            val bottom = fixture.wire.call()
            assertEquals(9L, bottom.before)
            bottom.reply.complete(page(6, 8, head = 11, rows = emptyList()))
            fixture.current()
            assertEquals(listOf(CoverageRange(1, 11)), store.channel("c").coverage)
            assertEquals(listOf(3L, 5L), store.rows("c").map { it.seq })
        } finally { fixture.stop() }
    }

    @Test fun interruptedRefillResumesItsOwedFloorAfterAProcessRestart() = runBlocking {
        val store = MemoryRowStore()
        store.commit("c", listOf(row(5)), listOf(CoverageRange(1, 5)))
        val first = Fixture(head = 11, store = store)
        try {
            first.start(); first.batch()
            first.wire.call().reply.complete(page(9, 11, head = 11))
            first.wire.call().reply.completeExceptionally(WireException("history_before", "disconnected", "interrupted"))
            withTimeout(10_000) { first.sync.state.first { it.error != null } }
        } finally { first.stop() }

        // No in-memory owed-floor state survives this new instance.
        val next = Fixture(head = 13, store = store)
        try {
            next.start(); next.batch()
            val newHead = next.wire.call()
            assertEquals(14L, newHead.before)
            newHead.reply.complete(page(12, 13, head = 13))
            val owed = next.wire.call()
            assertEquals(9L, owed.before)
            owed.reply.complete(page(6, 8, head = 13))
            next.current()
            assertEquals(listOf(CoverageRange(1, 13)), store.channel("c").coverage)
        } finally { next.stop() }
    }

    @Test fun olderPagingUsesRoomBeforeFetchingAndReturnsToCache() = runBlocking {
        val store = MemoryRowStore()
        store.commit("c", listOf(row(8), row(10)), listOf(CoverageRange(8, 10)))
        val fixture = Fixture(head = 10, store = store)
        try {
            fixture.start(); fixture.batch(); fixture.current()
            val cached = fixture.sync.loadOlder(11, 2)
            assertEquals(Origin.CACHE, cached.origin)
            assertEquals(8L, cached.nextBeforeSeq)
            assertEquals(listOf(8L, 10L), fixture.batch().rows.map { it.seq })
            assertTrue(fixture.wire.calls.tryReceive().isFailure)

            val demand = async { fixture.sync.loadOlder(8) }
            val call = fixture.wire.call()
            assertEquals(8L, call.before)
            assertEquals("user-demand", call.payload["purpose"].string())
            call.reply.complete(page(5, 7, head = 10, rows = listOf(row(5), row(7))))
            assertEquals(Origin.HISTORY, demand.await().origin)
            assertTrue(fixture.batch().olderThanWindow)
            assertEquals(listOf(CoverageRange(5, 10)), store.channel("c").coverage)
            assertEquals(Origin.CACHE, fixture.sync.loadOlder(8).origin)
            assertEquals(listOf(5L, 7L), fixture.batch().rows.map { it.seq })
            assertTrue(fixture.wire.calls.tryReceive().isFailure)
        } finally { fixture.stop() }
    }

    @Test fun liveRowsAndCheckpointPersistWithoutDuplicates() = runBlocking {
        val store = MemoryRowStore()
        store.commit("c", listOf(row(5)), listOf(CoverageRange(1, 5)))
        val fixture = Fixture(head = 5, store = store)
        try {
            fixture.start(); fixture.batch(); fixture.current()
            fixture.wire.feed(row(6))
            fixture.wire.feed(row(6))
            fixture.wire.frames.emit(DownFrame.Checkpoint("c", 6, 8, 1))
            val live = fixture.batch()
            assertEquals(Origin.LIVE, live.origin)
            assertEquals(listOf(6L), live.rows.map { it.seq })
            withTimeout(10_000) { fixture.sync.state.first { it.headSeq == 8L && it.current } }
            assertEquals(listOf(CoverageRange(1, 8)), store.channel("c").coverage)
            assertEquals(listOf(5L, 6L), store.rows("c").map { it.seq })
            assertTrue(fixture.received.tryReceive().isFailure)
        } finally { fixture.stop() }
    }

    @Test fun olderPagingDoesNotJumpOverAHoleToCachedRowsBelowIt() = runBlocking {
        val store = MemoryRowStore()
        store.commit("c", listOf(row(1), row(4), row(8), row(10)),
            listOf(CoverageRange(1, 4), CoverageRange(8, 10)))
        val fixture = Fixture(head = 15, store = store)
        try {
            fixture.start(); fixture.batch()
            val tail = fixture.wire.call()
            val older = async(start = CoroutineStart.UNDISPATCHED) { fixture.sync.loadOlder(8) }
            tail.reply.complete(page(11, 15, head = 15))
            val missing = fixture.wire.call()
            assertEquals("user-demand", missing.payload["purpose"].string())
            assertEquals(8L, missing.before)
            missing.reply.complete(page(5, 7, head = 15))
            assertEquals(Origin.HISTORY, older.await().origin)
            fixture.current()
            assertEquals(listOf(CoverageRange(1, 15)), store.channel("c").coverage)
        } finally { fixture.stop() }
    }

    @Test fun reconnectCancelsTheOldPageAndRefillsWithTheNewGeneration() = runBlocking {
        val store = MemoryRowStore()
        store.commit("c", listOf(row(5)), listOf(CoverageRange(1, 5)))
        val fixture = Fixture(head = 7, store = store)
        try {
            fixture.start(); fixture.batch()
            val obsolete = fixture.wire.call()
            fixture.wire.state.value = WireState.Attached(2, buildJsonObject {
                put("history_meta", buildJsonArray { add(buildJsonObject { put("channel_id", "c"); put("head_seq", 9) }) })
            })
            val replacement = fixture.wire.call()
            assertEquals(10L, replacement.before)
            assertEquals(2L, replacement.payload.number("generation"))
            obsolete.reply.complete(page(6, 7, head = 7))
            replacement.reply.complete(page(6, 9, head = 9))
            fixture.current()
            assertEquals(2L, fixture.sync.state.value.generation)
            assertEquals(listOf(CoverageRange(1, 9)), store.channel("c").coverage)
            assertTrue(fixture.wire.calls.tryReceive().isFailure)
        } finally { fixture.stop() }
    }

    @Test fun liveContinuesWhileARefillIsWaitingOnTheNetwork() = runBlocking {
        val store = MemoryRowStore()
        store.commit("c", (1L..5).map { row(it) }, listOf(CoverageRange(1, 5)))
        val fixture = Fixture(head = 10, store = store)
        try {
            fixture.start(); fixture.batch()
            val refill = fixture.wire.call() // Leave it pending while live work runs.
            fixture.wire.feed(row(10))
            fixture.wire.feed(row(11))
            fixture.wire.frames.emit(DownFrame.Checkpoint("c", 11, 12, 1))
            store.awaitRows("c") { rows -> rows.any { it.seq == 11L } }
            assertFalse(refill.reply.isCompleted)
            refill.reply.complete(page(6, 10, head = 12))
            fixture.current()
            assertEquals((1L..11).toList(), store.rows("c").map { it.seq })
            assertEquals(listOf(CoverageRange(1, 12)), store.channel("c").coverage)
            val emitted = mutableListOf<Long>()
            withTimeout(10_000) {
                while (emitted.size < 6) emitted += fixture.received.receive().rows.map { it.seq }
            }
            assertEquals((6L..11).toList(), emitted.sorted())
        } finally { fixture.stop() }
    }

    @Test fun unopenedChannelsAreCachedWithOneWireSubscription() = runBlocking {
        val fixture = Fixture(head = 0)
        try {
            fixture.hub.channel("other")
            withTimeout(10_000) { fixture.wire.frames.subscriptionCount.first { it > 0 } }
            assertEquals(1, fixture.wire.frames.subscriptionCount.value)
            fixture.wire.feed(row(50, "other"))
            fixture.wire.frames.emit(DownFrame.Checkpoint("other", 50, 52, 1))
            fixture.store.awaitRows("other") { it.size == 1 }
            withTimeout(10_000) {
                fixture.hub.channel("other").state.first { it.headSeq == 52L }
            }
            assertEquals(listOf(CoverageRange(50, 52)), fixture.store.channel("other").coverage)
            assertTrue(fixture.wire.calls.tryReceive().isFailure)
        } finally { fixture.stop() }
    }

    @Test fun oldGenerationCheckpointsDoNotFillTheReconnectGap() = runBlocking {
        val store = MemoryRowStore()
        store.commit("c", listOf(row(5)), listOf(CoverageRange(1, 5)))
        val fixture = Fixture(head = 10, store = store, generation = 2)
        try {
            fixture.start(); fixture.batch()
            val refill = fixture.wire.call()
            fixture.wire.frames.emit(DownFrame.Checkpoint("c", 6, 10, 1))
            fixture.wire.feed(row(11))
            store.awaitRows("c") { it.any { row -> row.seq == 11L } }
            assertEquals(listOf(CoverageRange(1, 5), CoverageRange(11, 11)), store.channel("c").coverage)
            refill.reply.complete(page(6, 10, head = 11))
            fixture.current()
        } finally { fixture.stop() }
    }

    @Test fun startupReadsABoundedWindowWhileKeepingOlderRowsOnDisk() = runBlocking {
        val store = MemoryRowStore()
        store.commit("c", (1L..5000).map { row(it) }, listOf(CoverageRange(1, 5000)))
        val fixture = Fixture(head = 5000, store = store)
        try {
            fixture.start()
            val rows = fixture.batch().rows
            assertEquals(4500, rows.size)
            assertEquals(501L, rows.first().seq)
            assertEquals(5000, store.rows("c", limit = 6000).size)
        } finally { fixture.stop() }
    }

    private class Fixture(head: Long, val store: MemoryRowStore = MemoryRowStore(), generation: Long = 1) {
        val wire = FakeWire(head, generation)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val hub = SyncHub(wire, store, scope)
        lateinit var sync: ChannelSync
        val received = Channel<RowBatch>(Channel.UNLIMITED)
        private var collector: Job? = null
        suspend fun start() {
            sync = hub.channel("c")
            collector = scope.launch(start = CoroutineStart.UNDISPATCHED) { sync.batches.collect { received.send(it) } }
            sync.start()
            withTimeout(10_000) { wire.frames.subscriptionCount.first { it > 0 } }
        }
        suspend fun batch(): RowBatch = withTimeout(10_000) { received.receive() }
        suspend fun current() { withTimeout(10_000) { sync.state.first { it.current && !it.loading } } }
        suspend fun stop() { hub.stop(); collector?.cancelAndJoin(); scope.cancel() }
    }

    private class FakeWire(head: Long, generation: Long) : WireClient {
        override val state = MutableStateFlow<WireState>(WireState.Attached(generation, buildJsonObject {
            put("history_meta", buildJsonArray { add(buildJsonObject { put("channel_id", "c"); put("head_seq", head) }) })
        }))
        override val frames = MutableSharedFlow<DownFrame>(extraBufferCapacity = 64)
        val calls = Channel<Call>(32)
        override suspend fun request(type: String, payload: JsonObject): JsonObject = error("Unexpected request $type")
        override suspend fun history(payload: JsonObject): HistoryPage {
            val call = Call(payload, CompletableDeferred())
            calls.send(call)
            return call.reply.await()
        }
        suspend fun call(): Call = withTimeout(10_000) { calls.receive() }
        suspend fun feed(row: Row) { frames.emit(DownFrame.Feed("", row)) }
    }

    private data class Call(val payload: JsonObject, val reply: CompletableDeferred<HistoryPage>) {
        val before get() = payload.number("before_seq")
    }

    companion object {
        private fun row(seq: Long, channel: String = "c") = Row(channel, seq, Envelope(buildJsonObject {
            put("id", "$channel-$seq"); put("channel_id", channel); put("kind", "event"); put("type", "notice")
        }))
        private fun page(low: Long, high: Long, head: Long, rows: List<Row> = (low..high).map { row(it) }) =
            HistoryPage(rows, buildJsonObject {
                put("head_seq", head); put("scan_low_seq", low); put("scan_high_seq", high)
                put("next_before_seq", low); put("has_older", low > 1)
            })
    }
}

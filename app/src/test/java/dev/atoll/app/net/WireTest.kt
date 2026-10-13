package dev.atoll.app.net

import dev.atoll.app.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class WireTest {
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject
    private class Fixture(timeout: Long = 30_000, reconnect: Long = 500, startImmediately: Boolean = true, private val firstLive: Long? = null) : AutoCloseable {
        val server = MockWebServer()
        val inbound = LinkedBlockingQueue<JsonObject>()
        val sockets = CopyOnWriteArrayList<WebSocket>()
        lateinit var wire: Wire
        var acknowledgeAttach = true
        init {
            server.start()
            upgrade()
            wire = Wire(server.url("/").toString(), OkHttpClient(), "Test phone", receiptTimeoutMs = timeout, reconnectBaseMs = reconnect)
            if (startImmediately) wire.start()
        }
        fun upgrade() { server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { sockets.add(webSocket) }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = Json.parseToJsonElement(text).jsonObject
                inbound.add(frame)
                if (frame["frame_type"].string() == Up.ATTACH && acknowledgeAttach) {
                    send("receipt", frame["ref"].string(), """{"session":"screen-1","label":"Test phone","contract_version":5,"memberships":[],"history_meta":[]}""", webSocket)
                    firstLive?.let { seq -> send("feed", payload = """{"channel_id":"c","seq":$seq,"generation":1,"envelope":{"id":"first","payload":{"body":{}}}}""", socket = webSocket) }
                }
            }
        })) }
        suspend fun attached(): WireState.Attached = withTimeout(5_000) { wire.state.filterIsInstance<WireState.Attached>().first() }
        fun next(type: String): JsonObject {
            val frame = inbound.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("missing $type")
            assertEquals(type, frame["frame_type"].string())
            return frame
        }
        fun send(type: String, ref: String = "", payload: String = "{}", socket: WebSocket = sockets.last(), version: Int = 5) {
            assertTrue(socket.send("""{"v":$version,"frame_type":"$type","ref":"$ref","payload":$payload}"""))
        }
        fun feed(seq: Long, ref: String = "", generation: Long = 1) = send("feed", ref,
            """{"channel_id":"c","seq":$seq,"generation":$generation,"envelope":{"id":"m-$seq","channel_id":"c","kind":"event","payload":{"body":{"text":"hello"}}}}""")
        override fun close() {
            wire.close()
            runBlocking { withTimeout(5_000) { wire.state.first { it is WireState.SignedOut } } }
            sockets.forEach { it.close(1000, "test complete") }
            server.shutdown()
        }
    }
    private fun historyPayload(generation: Long = 1) = obj("""{"channel_id":"c","generation":$generation,"purpose":"initial-tail","priority":"foreground","limit":200}""")

    @Test fun attachesAndCorrelatesConcurrentReceiptsAndErrors() = runBlocking<Unit> {
        Fixture().use { f ->
            val attached = f.attached()
            val attach = f.next(Up.ATTACH)["payload"]!!.jsonObject
            assertEquals(1L, attached.generation)
            assertEquals(JsonPrimitive(5), attach["history_protocol"])
            assertEquals(JsonPrimitive("Test phone"), attach["label"])
            val requests = (1..16).map { n -> async(start = CoroutineStart.UNDISPATCHED) {
                f.wire.request(Up.CHANNEL_META, obj("""{"channel_id":"c$n","generation":1}"""))
            } }
            val outgoing = requests.map { f.next(Up.CHANNEL_META) }
            assertEquals(16, outgoing.map { it["ref"] }.toSet().size)
            outgoing.reversed().forEach { frame -> f.send("receipt", frame["ref"].string(), frame["payload"].toString()) }
            requests.forEachIndexed { i, result -> assertEquals(JsonPrimitive("c${i+1}"), result.await()["channel_id"]) }
            val refused = async(start = CoroutineStart.UNDISPATCHED) { runCatching { f.wire.request(Up.CANCEL, obj("""{"channel_id":"c","req_id":"r"}""")) }.exceptionOrNull() }
            val ref = f.next(Up.CANCEL)["ref"].string()
            f.send("error", ref, """{"frame":"cancel","code":"request_not_found","detail":"gone"}""")
            val error = refused.await() as WireException
            assertEquals("request_not_found", error.code); assertEquals("gone", error.message); assertEquals(ref, error.ref)
        }
    }

    @Test fun stampsHumanAskOriginFromAttachReceipt() = runBlocking<Unit> {
        Fixture().use { f ->
            f.attached(); f.next(Up.ATTACH)
            val submit = async(start = CoroutineStart.UNDISPATCHED) {
                f.wire.request(Up.SUBMIT, obj("""{"channel_id":"c","msg_type":"agent.ask","payload":{"text":"hello"}}"""))
            }
            val frame = f.next(Up.SUBMIT)
            val origin = frame["payload"]!!.jsonObject["payload"]!!.jsonObject["origin"]!!.jsonObject
            assertEquals(JsonPrimitive("screen-1"), origin["session"])
            assertEquals(JsonPrimitive("Test phone"), origin["label"])
            f.send("receipt", frame["ref"].string())
            submit.await()
        }
    }

    @Test fun historyRowsAreIsolatedAndSortedWhileLiveFramesContinue() = runBlocking<Unit> {
        Fixture().use { f ->
            f.attached(); f.next(Up.ATTACH)
            val live = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5_000) { f.wire.frames.take(4).toList() } }
            val page = async(start = CoroutineStart.UNDISPATCHED) { f.wire.history(historyPayload()) }
            val ref = f.next(Up.HISTORY_BEFORE)["ref"].string()
            f.send("receipt", ref)
            f.feed(2, ref); f.feed(99); f.feed(1, ref)
            f.send("checkpoint", payload = """{"channel_id":"c","scan_low_seq":90,"scanned_seq":99,"generation":1}""")
            f.send("memberships", payload = """{"memberships":[]}""")
            f.send("observe_ended", payload = """{"channel_id":"c","reason":"now_member"}""")
            f.send("page_end", ref, """{"channel_id":"c","generation":1,"scan_low_seq":1,"scan_high_seq":2,"head_seq":99,"has_older":false}""")
            val history = withTimeout(5_000) { page.await() }
            assertEquals(listOf(1L, 2L), history.rows.map { it.seq })
            assertEquals(1L, history.scanLowSeq); assertEquals(2L, history.scanHighSeq)
            val received = live.await()
            assertEquals(99L, (received[0] as DownFrame.Feed).row.seq)
            assertTrue(received[1] is DownFrame.Checkpoint)
            assertTrue(received[2] is DownFrame.Memberships)
            assertTrue(received[3] is DownFrame.ObserveEnded)
        }
    }

    @Test fun timeoutAndPageFailureKeepUsefulErrorDetails() = runBlocking<Unit> {
        Fixture(timeout = 250).use { f ->
            f.attached(); f.next(Up.ATTACH)
            val request = async(start = CoroutineStart.UNDISPATCHED) { runCatching { f.wire.request(Up.OBSERVE, obj("""{"channel_id":"c"}""")) }.exceptionOrNull() }
            f.next(Up.OBSERVE)
            assertEquals("timeout", (request.await() as WireException).code)
            val page = async(start = CoroutineStart.UNDISPATCHED) { runCatching { f.wire.history(historyPayload()) }.exceptionOrNull() }
            val ref = f.next(Up.HISTORY_BEFORE)["ref"].string()
            f.send("page_end", ref, """{"channel_id":"c","generation":1,"error_code":"channel_unavailable","error_detail":"retry later"}""")
            val error = page.await() as WireException
            assertEquals("channel_unavailable", error.code); assertEquals("retry later", error.message)
        }
    }

    @Test fun disconnectFailsPendingAndForegroundCancelsBackoff() = runBlocking<Unit> {
        Fixture(reconnect = 5_000).use { f ->
            f.attached(); f.next(Up.ATTACH)
            val pending = async(start = CoroutineStart.UNDISPATCHED) { runCatching { f.wire.request(Up.OBSERVE, obj("""{"channel_id":"c"}""")) }.exceptionOrNull() }
            f.next(Up.OBSERVE)
            val page = async(start = CoroutineStart.UNDISPATCHED) { runCatching { f.wire.history(historyPayload()) }.exceptionOrNull() }
            f.next(Up.HISTORY_BEFORE)
            f.upgrade()
            f.sockets.last().close(1001, "test disconnect")
            val disconnected = withTimeout(5_000) { f.wire.state.filterIsInstance<WireState.Disconnected>().first() }
            assertEquals(5_000L, disconnected.retryInMs)
            assertEquals("closed", (pending.await() as WireException).code)
            assertEquals("closed", (page.await() as WireException).code)
            f.wire.reconnectNow()
            val attached = withTimeout(2_000) { f.wire.state.filterIsInstance<WireState.Attached>().first { it.generation == 2L } }
            assertEquals(2L, attached.generation)
            assertEquals(JsonPrimitive(2), f.next(Up.ATTACH)["payload"]!!.jsonObject["generation"])
        }
    }

    @Test fun automaticallyReconnectsWithNewGeneration() = runBlocking<Unit> {
        Fixture(reconnect = 25).use { f ->
            f.attached(); f.next(Up.ATTACH)
            f.upgrade(); f.sockets.last().close(1001, "test disconnect")
            val attached = withTimeout(5_000) { f.wire.state.filterIsInstance<WireState.Attached>().first { it.generation > 1L } }
            assertEquals(2L, attached.generation)
            f.next(Up.ATTACH)
            val live = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5_000) { f.wire.frames.filterIsInstance<DownFrame.Feed>().first() } }
            f.feed(20, generation = 1); f.feed(21, generation = 2)
            assertEquals(21L, live.await().row.seq)
        }
    }

    @Test fun wrongVersionFailsPendingAndStopsReconnect() = runBlocking<Unit> {
        Fixture(reconnect = 25).use { f ->
            f.attached(); f.next(Up.ATTACH)
            val pending = async(start = CoroutineStart.UNDISPATCHED) { runCatching { f.wire.request(Up.OBSERVE, obj("""{"channel_id":"c"}""")) }.exceptionOrNull() }
            f.next(Up.OBSERVE)
            f.send("future", version = 4)
            withTimeout(5_000) { f.wire.state.filterIsInstance<WireState.Incompatible>().first() }
            assertEquals("version_incompatible", (pending.await() as WireException).code)
            f.wire.reconnectNow()
            val new = runCatching { f.wire.request(Up.OBSERVE, obj("""{"channel_id":"c"}""")) }.exceptionOrNull() as WireException
            assertEquals("version_incompatible", new.code)
            delay(100)
            assertEquals(1, f.server.requestCount)
        }
    }

    @Test fun canceledHistoryReleasesServerPageAndLateRowsNeverLeak() = runBlocking<Unit> {
        Fixture().use { f ->
            f.attached(); f.next(Up.ATTACH)
            val page = async(start = CoroutineStart.UNDISPATCHED) { f.wire.history(historyPayload()) }
            val ref = f.next(Up.HISTORY_BEFORE)["ref"].string()
            page.cancelAndJoin()
            val cancel = f.next(Up.HISTORY_CANCEL)
            assertEquals(JsonPrimitive(ref), cancel["payload"]!!.jsonObject["target_ref"])
            f.send("receipt", cancel["ref"].string())
            val live = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5_000) { f.wire.frames.filterIsInstance<DownFrame.Feed>().first() } }
            f.feed(2, ref); f.feed(3)
            assertEquals(3L, live.await().row.seq)
        }
    }

    @Test fun signOutFailsPendingAndDisablesForegroundReconnect() = runBlocking<Unit> {
        Fixture().use { f ->
            f.attached(); f.next(Up.ATTACH)
            val request = async(start = CoroutineStart.UNDISPATCHED) { runCatching { f.wire.request(Up.OBSERVE, obj("""{"channel_id":"c"}""")) }.exceptionOrNull() }
            f.next(Up.OBSERVE)
            f.wire.close()
            assertEquals("closed", (request.await() as WireException).code)
            assertTrue(f.wire.state.value is WireState.SignedOut)
            f.wire.reconnectNow()
            val error = runCatching { f.wire.request(Up.OBSERVE, obj("""{"channel_id":"c"}""")) }.exceptionOrNull() as WireException
            assertEquals("closed", error.code)
            assertEquals(1, f.server.requestCount)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun signOutDrainsCallsAlreadyAcceptedByTheWriter() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(401))
            val wire = Wire(server.url("/").toString(), OkHttpClient(), "Test phone", dispatcher = StandardTestDispatcher(testScheduler))
            // Both operations are queued before the writer runs: closing must
            // fail this accepted call as well as calls already in pending.
            wire.close()
            val queued = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { wire.request(Up.OBSERVE, obj("""{"channel_id":"c"}""")) }.exceptionOrNull()
            }
            runCurrent()
            assertEquals("closed", (queued.await() as WireException).code)
            assertTrue(wire.state.value is WireState.SignedOut)
        }
    }

    @Test fun explicitStartLetsSubscribersReceiveTheFirstLiveRow() = runBlocking<Unit> {
        Fixture(startImmediately = false, firstLive = 7).use { f ->
            delay(50)
            assertEquals(0, f.server.requestCount)
            val first = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5_000) { f.wire.frames.filterIsInstance<DownFrame.Feed>().first() } }
            f.wire.start()
            f.wire.start()
            f.attached()
            assertEquals(7L, first.await().row.seq)
            assertEquals(1, f.server.requestCount)
        }
    }

}

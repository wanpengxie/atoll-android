package dev.atoll.app.terminal

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import okio.ByteString
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class PtyClientTest {
    private class Fixture(delays: List<Long> = listOf(10, 20, 30), idle: Long = 200, dispatcher: CoroutineDispatcher = Dispatchers.IO) : AutoCloseable {
        val server = MockWebServer()
        val text = LinkedBlockingQueue<JsonObject>()
        val binary = LinkedBlockingQueue<PtyBytes>()
        val sockets = CopyOnWriteArrayList<WebSocket>()
        val closed = LinkedBlockingQueue<Unit>()
        val http = OkHttpClient()
        val client: PtyClient
        init { server.start(); upgrade(); client = PtyClient(server.url("/").toString(), http, delays, idle, dispatcher) }
        fun upgrade() { server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) { sockets.add(ws) }
            override fun onMessage(ws: WebSocket, value: String) { text.add(Json.parseToJsonElement(value).jsonObject) }
            override fun onMessage(ws: WebSocket, value: ByteString) { binary.add(PtyFrames.decode(value)!!) }
            override fun onClosing(ws: WebSocket, code: Int, reason: String) { ws.close(code, reason) }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) { closed.add(Unit) }
        })) }
        fun next(type: String): JsonObject = (text.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("Missing $type")).also { assertEquals(type, it["type"]!!.jsonPrimitive.content) }
        fun ready(id: Long, session: String = "session-1") { assertTrue(sockets.last().send("""{"type":"ready","id":$id,"session":"$session"}""")) }
        fun error(id: Long) { sockets.last().send("""{"type":"error","id":$id,"code":"session_not_found","detail":"session gone"}""") }
        suspend fun ready(handle: PtyHandle) = withTimeout(5_000) { handle.status.filterIsInstance<PtyStatus.Ready>().first() }
        override fun close() { client.close(); sockets.forEach { it.close(1000, "test") }; server.shutdown(); http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll() }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun cancelledCallerAfterActorCompletesAttachDoesNotLeaveOrphanStream() {
        val actorClock = TestCoroutineScheduler()
        val callerClock = TestCoroutineScheduler()
        val caller = CoroutineScope(SupervisorJob() + StandardTestDispatcher(callerClock))
        Fixture(dispatcher = StandardTestDispatcher(actorClock), idle = 10).use { f ->
            var resumed = false
            val job = caller.launch { f.client.attach("c", "d", onData = {}); resumed = true }
            callerClock.runCurrent() // caller awaits attach
            actorClock.runCurrent() // stream created; caller's continuation is pending
            job.cancel(); callerClock.runCurrent(); actorClock.runCurrent()
            assertFalse(resumed)
            actorClock.advanceTimeBy(11); actorClock.runCurrent()
            assertNotNull("cancelled stream must permit idle socket shutdown", f.closed.poll(5, TimeUnit.SECONDS))
            f.client.close(); actorClock.runCurrent(); caller.cancel()
        }
    }
    @Test fun multiplexesAndInstallsCallbacksBeforeFirstReplay() = runBlocking<Unit> {
        Fixture().use { f ->
            val output = LinkedBlockingQueue<ByteArray>()
            val events = LinkedBlockingQueue<String>()
            val a = f.client.attach("channel", "local-device", onReady = { events.add("ready") }) { output.add(it); events.add("data") }
            val b = f.client.attach("other", "mac", onData = {})
            val opens = listOf(f.next("open"), f.next("open"))
            assertEquals(setOf(a.id,b.id), opens.map { it["id"]!!.jsonPrimitive.long }.toSet())
            assertEquals(1, f.server.requestCount)
            assertEquals("/pty", f.server.takeRequest().path)
            assertNull(f.text.poll(100, TimeUnit.MILLISECONDS))
            f.ready(a.id); f.sockets.last().send(PtyFrames.binary(a.id, "初次回放".toByteArray()))
            assertArrayEquals("初次回放".toByteArray(), output.poll(5, TimeUnit.SECONDS))
            assertEquals("ready", events.poll(5, TimeUnit.SECONDS)); assertEquals("data", events.poll(5, TimeUnit.SECONDS))
            a.detach(); b.close(); f.next("detach"); f.next("close")
        }
    }
    @Test fun buffersInputInOrderAndCapsAt64KiBUntilReady() = runBlocking<Unit> {
        Fixture().use { f ->
            val h = f.client.attach("c", "d", onData = {}); f.next("open")
            h.write(byteArrayOf(1,2)); h.write(ByteArray(PtyClient.MAX_PENDING_INPUT - 2) { 3 }); h.write(byteArrayOf(4))
            h.resize(90, 30)
            assertNull(f.binary.poll(100, TimeUnit.MILLISECONDS))
            f.ready(h.id); f.ready(h)
            val resize = f.next("resize"); assertEquals(90, resize["cols"]!!.jsonPrimitive.int)
            assertArrayEquals(byteArrayOf(1,2), f.binary.poll(5, TimeUnit.SECONDS)!!.bytes)
            assertEquals(PtyClient.MAX_PENDING_INPUT - 2, f.binary.poll(5, TimeUnit.SECONDS)!!.bytes.size)
            assertNull(f.binary.poll(100, TimeUnit.MILLISECONDS))
        }
    }
    @Test fun reconnectsAllStreamsWithSavedSessionAndPendingInput() = runBlocking<Unit> {
        Fixture(delays=listOf(250)).use { f ->
            val a = f.client.attach("c", "d", onData = {}); val b = f.client.attach("c2", "d2", onData = {})
            f.next("open"); f.next("open"); f.ready(a.id, "a"); f.ready(b.id, "b"); f.ready(a); f.ready(b)
            f.upgrade(); f.sockets.last().close(1012, "restart")
            withTimeout(5_000) { a.status.filterIsInstance<PtyStatus.Reconnecting>().first() }; a.write("during disconnect".toByteArray())
            val opens = listOf(f.next("open"), f.next("open"))
            assertEquals(setOf("a","b"), opens.map { it["session"]!!.jsonPrimitive.content }.toSet())
            f.ready(a.id, "a"); f.ready(a)
            assertEquals("during disconnect", f.binary.poll(5, TimeUnit.SECONDS)!!.bytes.toString(Charsets.UTF_8))
            assertEquals(2, f.server.requestCount)
        }
    }
    @Test fun connectedSocketDoesNotResetRetryBudgetBeforeReady() = runBlocking<Unit> {
        Fixture(delays=listOf(10,10)).use { f ->
            f.upgrade(); f.upgrade()
            val h = f.client.attach("c", "d", onData = {})
            repeat(3) { f.next("open"); f.sockets.last().close(1012, "not ready") }
            val end = withTimeout(5_000) { h.status.filterIsInstance<PtyStatus.Ended>().first() }
            assertEquals("连不上终端——设备可能不在线", end.detail); assertEquals(3, f.server.requestCount)
        }
    }
    @Test fun detachReattachesSameSessionAndInvalidSessionReopensOnlyOnce() = runBlocking<Unit> {
        Fixture(idle=1000).use { f ->
            val a = f.client.attach("c", "d", onData = {}); f.next("open"); f.ready(a.id); f.ready(a)
            a.detach(); f.next("detach")
            val b = f.client.attach("c", "d", onData = {})
            val resume = f.next("open"); assertEquals("session-1", resume["session"]!!.jsonPrimitive.content)
            f.error(b.id)
            val fresh = f.next("open"); assertFalse(fresh.containsKey("session")); assertEquals(b.id, fresh["id"]!!.jsonPrimitive.long)
            f.error(b.id)
            assertEquals("session gone", withTimeout(5_000) { b.status.filterIsInstance<PtyStatus.Ended>().first() }.detail)
            assertNull(f.text.poll(100, TimeUnit.MILLISECONDS)); assertEquals(1, f.server.requestCount)
        }
    }
    @Test fun exitAndCloseForgetSessionAndReadyResetsRetryBudget() = runBlocking<Unit> {
        Fixture(delays=listOf(10),idle=1000).use { f ->
            val a = f.client.attach("c", "d", onData = {}); f.next("open"); f.ready(a.id); f.ready(a)
            repeat(2) {
                f.upgrade(); f.sockets.last().close(1012,"restart"); f.next("open"); f.ready(a.id); f.ready(a)
            }
            f.sockets.last().send("""{"type":"exit","id":${a.id},"reason":"shell exited"}""")
            withTimeout(5_000) { a.status.filterIsInstance<PtyStatus.Ended>().first() }
            val b = f.client.attach("c", "d", onData = {}); assertFalse(f.next("open").containsKey("session")); f.ready(b.id); f.ready(b)
            b.close(); f.next("close")
            f.client.attach("c", "d", onData = {}); assertFalse(f.next("open").containsKey("session"))
        }
    }
    @Test fun rapidAttachDuringIdleSocketShutdownKeepsOneConnection() = runBlocking<Unit> {
        Fixture(idle=10).use { f ->
            val a = f.client.attach("c", "d", onData = {}); f.next("open"); f.ready(a.id); f.ready(a)
            f.upgrade(); a.detach(); f.next("detach"); delay(100)
            val b = f.client.attach("c", "d", onData = {})
            assertEquals("session-1", f.next("open")["session"]!!.jsonPrimitive.content); f.ready(b.id); f.ready(b)
            assertEquals(2, f.server.requestCount)
        }
    }
}

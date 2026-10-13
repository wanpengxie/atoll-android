package dev.atoll.app.app

import dev.atoll.app.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class NodeUpdateTest {
    @Test fun httpCheckAndStartUseTheExactEndpointsAndDevelopmentBuildCannotStart() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val api = NodeUpdateClient(server.url("/").toString(), OkHttpClient())
            server.enqueue(MockResponse().setBody("""{"current_version":"v1","latest_version":"v2","available":true,"status":"idle"}"""))
            assertTrue(api.status(true).canStart)
            assertEquals("/api/update?check=1", server.takeRequest().path)
            server.enqueue(MockResponse().setResponseCode(202).setBody("""{"current_version":"v1","latest_version":"v2","available":true,"status":"downloading"}"""))
            assertTrue(api.start().active)
            assertEquals("POST", server.takeRequest().method)
            val development = NodeUpdateValue.fromJson(Json.parseToJsonElement("""{"current_version":"dev","latest_version":"v2","available":true,"status":"idle","detail":"开发版不执行自动升级"}""").jsonObject)
            assertFalse(development.canStart); assertFalse(development.view().canStart)
            server.enqueue(MockResponse().setResponseCode(503).setBody("""{"code":"unavailable","detail":"service offline"}"""))
            try { api.status(false); fail("expected error") } catch (e: dev.atoll.app.net.HttpFailure) { assertEquals(503, e.status) }
            assertEquals("/api/update", server.takeRequest().path)
        }
    }
    @Test fun onlyRootChecksAtStartupReconnectAndSixHoursAndDoubleClickPostsOnce() = runTest {
        val wire = FakeWire()
        val api = FakeApi()
        val monitor = NodeUpdateMonitor(api, wire, this, root = true)
        runCurrent()
        assertEquals(listOf(true), api.checks)
        wire.state.value = WireState.Attached(1, Envelope.EMPTY); runCurrent()
        assertEquals(1, api.checks.size)
        monitor.start(); monitor.start(); runCurrent()
        assertEquals(1, api.starts)
        assertTrue(monitor.state.value.pending)
        api.started.complete(NodeUpdateValue("v1", "v2", true, "restarting")); runCurrent()
        api.failure = IOException("node restarting")
        advanceTimeBy(1001); runCurrent()
        assertEquals(false, api.checks.last())
        assertEquals("restarting", monitor.state.value.phase)
        api.failure = null
        api.value = NodeUpdateValue("v2", "v2", false, "idle")
        wire.state.value = WireState.Attached(2, Envelope.EMPTY); runCurrent()
        assertEquals(true, api.checks.last())
        assertFalse(monitor.state.value.active)
        assertEquals("current", monitor.state.value.view().phase)
        val count = api.checks.size
        advanceTimeBy(6 * 60 * 60 * 1000L); runCurrent()
        assertEquals(count + 1, api.checks.size)
        monitor.close()
        val unauthorized = FakeApi()
        val memberMonitor = NodeUpdateMonitor(unauthorized, wire, this, root = false)
        memberMonitor.start(); runCurrent()
        assertTrue(unauthorized.checks.isEmpty()); assertEquals(0, unauthorized.starts)
        memberMonitor.close()
    }
    private class FakeApi : NodeUpdateApi {
        val checks = mutableListOf<Boolean>(); var starts = 0
        var value = NodeUpdateValue("v1", "v2", true, "idle")
        var failure: Exception? = null
        val started = CompletableDeferred<NodeUpdateValue>()
        override suspend fun status(check: Boolean): NodeUpdateValue { checks += check; failure?.let { throw it }; return value }
        override suspend fun start(): NodeUpdateValue { starts++; return started.await() }
    }
    private class FakeWire : WireClient {
        override val state = MutableStateFlow<WireState>(WireState.Connecting)
        override val frames = MutableSharedFlow<DownFrame>()
        override suspend fun request(type: String, payload: JsonObject): JsonObject = error("unused")
        override suspend fun history(payload: JsonObject): HistoryPage = error("unused")
    }
}

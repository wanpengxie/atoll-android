package dev.atoll.app.app

import dev.atoll.app.core.*
import dev.atoll.app.files.FileRepository
import dev.atoll.app.net.HttpFailure
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SessionHttpTest {
    @Test fun filesProfile401ExpiresThroughSharedClientWithoutAnObsCallback() = verify("profile", 401, 1)
    @Test fun filesDevices401ExpiresThroughSharedClientWithoutAnObsCallback() = verify("devices", 401, 1)
    @Test fun filesPermissionFailureDoesNotExpireTheAccount() = verify("profile", 403, 0)

    private fun verify(endpoint: String, status: Int, expectedExpired: Int) = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = if (request.path!!.endsWith("/$endpoint"))
                    MockResponse().setResponseCode(status).setBody("{}") else MockResponse().setBody("{\"items\":[]}")
            }
            server.start()
            val expired = AtomicInteger()
            val http = sessionHttp(OkHttpClient()) { expired.incrementAndGet() }
            val cache = java.nio.file.Files.createTempDirectory("files-session-http").toFile()
            try {
                // Match SessionFeatures exactly: the per-module onUnauthorized default stays empty.
                val repository = FileRepository("channel", UnusedWire, http, server.url("/").toString(), cache)
                try { repository.initialize(); fail("Expected OBS failure") }
                catch (failure: HttpFailure) { assertEquals(status, failure.status) }
                assertEquals(expectedExpired, expired.get())
            } finally { cache.deleteRecursively() }
        }
    }

    private object UnusedWire : WireClient {
        override val state = MutableStateFlow<WireState>(WireState.Disconnected("unused", 0))
        override val frames = MutableSharedFlow<DownFrame>()
        override suspend fun request(type: String, payload: JsonObject): JsonObject = error("OBS must not use wire")
        override suspend fun history(payload: JsonObject): HistoryPage = error("OBS must not read history")
    }
}

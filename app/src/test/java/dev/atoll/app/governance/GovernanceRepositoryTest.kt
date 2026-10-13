package dev.atoll.app.governance

import dev.atoll.app.core.*
import dev.atoll.app.net.ObsClient
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

class GovernanceRepositoryTest {
    @Test fun directoryRefreshUsesOnlyParallelObsGetsIncludingAttachedDevices()=runBlocking<Unit> {
        val paths=ConcurrentLinkedQueue<String>()
        MockWebServer().use { server ->
            server.dispatcher=object:Dispatcher() { override fun dispatch(request: RecordedRequest):MockResponse {
                assertEquals("GET",request.method);paths.add(request.path.orEmpty())
                val body=when (request.path) {
                    "/obs/channel/c/profile" -> """{"items":[{"declared":{"id":"c","owner_principal":"root"},"actual":{"measures":[{"name":"open","value":true}]}}]}"""
                    "/obs/space/channels" -> """{"items":[{"declared":{"id":"c0","type":"group"}}]}"""
                    "/obs/space/channels?parent_id=c0" -> """{"items":[{"declared":{"id":"c","parent_id":"c0","type":"group"}}]}"""
                    "/obs/channel/c/devices" -> """{"items":[{"key":"mac","declared":{"device_id":"mac"},"actual":{"measures":[{"name":"online","value":true}]}}]}"""
                    "/obs/space/actor-descriptions" -> """{"items":[{"declared":{"id":"d","version":1}}]}"""
                    else -> """{"items":[],"complete":true}"""
                }
                return MockResponse().setBody(body).setHeader("Content-Type","application/json")
            } }
            val wire=object:WireClient {
                override val state=MutableStateFlow<WireState>(WireState.Attached(1,Envelope.EMPTY))
                override val frames=MutableSharedFlow<DownFrame>()
                override suspend fun request(type: String,payload: JsonObject):JsonObject=error("OBS refresh must never submit")
                override suspend fun history(payload: JsonObject):HistoryPage=error("must not query ledger")
            }
            val directory=GovernanceRepository(wire,ObsClient(server.url("/").toString(),OkHttpClient())).directory("c")
            assertEquals(setOf("/obs/space/channels","/obs/space/channels?parent_id=c0","/obs/space/channels?parent_id=c","/obs/space/principals","/obs/space/daemons","/obs/space/actor-descriptions","/obs/channel/c/actors","/obs/channel/c/devices","/obs/channel/c/profile"),paths.toSet())
            assertEquals("root",directory.profile.declared().text("owner_principal"));assertEquals(true,directory.profile.measure("open"));assertEquals(listOf("c0","c"),directory.channels.map { it.directoryId() });assertEquals("d",directory.descriptions.single().text("id"));assertEquals("mac",directory.channelDevices.single().directoryId());assertEquals(true,directory.channelDevices.single().measure("online"))
        }
    }
}

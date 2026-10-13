package dev.atoll.app.files

import dev.atoll.app.core.*
import dev.atoll.app.net.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class StorageResolverTest {
    @Test fun storageSeatIsProbedThenSignsThePathAndForeignFilesAreRejected() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"items":[{"declared":{"id":"peer:storage","body":"class channel-seat"}}]}"""))
            val requests = mutableListOf<JsonObject>()
            val wire = object : WireClient {
                override val state = MutableStateFlow<WireState>(WireState.Attached(1, Envelope.EMPTY))
                override val frames = MutableSharedFlow<DownFrame>()
                override suspend fun history(payload: JsonObject) = HistoryPage(emptyList(), Envelope.EMPTY)
                override suspend fun request(type: String, payload: JsonObject): JsonObject {
                    requests.add(payload)
                    val value = if (payload["msg_type"].string() == "actor.describe") buildJsonObject { put("words", buildJsonObject { put("storage.get_url", Envelope.EMPTY) }) }
                    else buildJsonObject { put("url", server.url("/signed").toString()); put("size", 42); put("media_type", "text/plain") }
                    val id = payload["id"].string()
                    frames.emit(DownFrame.Feed("", Row("c", requests.size.toLong(), Envelope(buildJsonObject {
                        put("id", "response-$id"); put("parent_id", id); put("kind", "response"); put("payload", buildJsonObject { put("body", buildJsonObject { put("status", "completed"); put("value", value) }) })
                    }))))
                    return Envelope.EMPTY
                }
            }
            val resolver = StorageResolver(wire, ObsClient(server.url("/").toString(), OkHttpClient()))
            val ticket = resolver.resolve("c", "c0.dev", "oss://c0.storage/c0.dev/中文.txt", true)
            assertTrue(ticket.external); assertEquals(42L, ticket.size); assertEquals("text/plain", ticket.mediaType)
            assertEquals(listOf("actor.describe", "storage.get_url"), requests.map { it["msg_type"].string() })
            assertEquals(JsonPrimitive("中文.txt"), requests.last()["payload"]!!.jsonObject["path"])
            assertTrue(runCatching { resolver.resolve("c", "c0.dev", "oss://c0.storage/c0.other/f.txt", true) }.exceptionOrNull() is IllegalArgumentException)
            assertEquals(1, server.requestCount)
        }
    }
}

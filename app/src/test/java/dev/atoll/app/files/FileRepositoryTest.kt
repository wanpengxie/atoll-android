package dev.atoll.app.files

import dev.atoll.app.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import dev.atoll.app.net.PreviewTooLarge
import java.io.File

class FileRepositoryTest {
    private class Wire : WireClient {
        override val state = MutableStateFlow<WireState>(WireState.Attached(1, Envelope.EMPTY))
        override val frames = MutableSharedFlow<DownFrame>()
        val requests = mutableListOf<JsonObject>()
        var reply: (JsonObject) -> JsonObject = { buildJsonObject { put("ticket", "test-ticket") } }
        override suspend fun request(type: String, payload: JsonObject): JsonObject { requests.add(payload); return reply(payload) }
        override suspend fun history(payload: JsonObject) = HistoryPage(emptyList(), Envelope.EMPTY)
    }
    @Test fun previewUsesHeadAndRevalidatesItsDiskCopyWithEtag() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            val text = "原生预览\nline 2"
            var gets = 0
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.method == "HEAD" -> MockResponse().addHeader("Content-Type", "text/plain").addHeader("Content-Length", text.toByteArray().size).addHeader("ETag", "\"one\"")
                    request.getHeader("If-None-Match") == "\"one\"" -> { gets++; MockResponse().setResponseCode(304) }
                    else -> { gets++; MockResponse().setBody(text).addHeader("Content-Type", "text/plain").addHeader("ETag", "\"one\"") }
                }
            }
            val cache = java.nio.file.Files.createTempDirectory("atoll-preview").toFile()
            try {
                val partial = File(cache, "c/orphan.part").apply { parentFile.mkdirs(); writeText("interrupted download") }
                val stale = File(cache, "c/old-copy.txt").apply { writeText("old preview") }
                val repository = FileRepository("c", Wire(), OkHttpClient(), server.url("/").toString(), cache)
                val ref = FileReference("c", "daemon://d/c/file.txt", "file.txt")
                val first = repository.preview(ref)
                val second = repository.preview(ref)
                assertEquals(text, first.text); assertEquals(text, second.text); assertEquals(first.file, second.file)
                assertEquals(2, gets)
                assertFalse(partial.exists()); assertFalse(stale.exists())
                assertTrue(cache.listFiles().orEmpty().none { it.extension == "part" })
            } finally { cache.deleteRecursively() }
        }
    }
    @Test fun largeTextIsRejectedBeforeItsBytesAreRequested() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().addHeader("Content-Type", "text/plain").addHeader("Content-Length", 512 * 1024 + 1))
            val cache = java.nio.file.Files.createTempDirectory("atoll-preview").toFile()
            try {
                val repository = FileRepository("c", Wire(), OkHttpClient(), server.url("/").toString(), cache)
                assertTrue(runCatching { repository.preview(FileReference("c", "file.txt", "file.txt")) }.exceptionOrNull() is PreviewTooLarge)
                assertEquals(1, server.requestCount); assertEquals("HEAD", server.takeRequest().method)
            } finally { cache.deleteRecursively() }
        }
    }
    @Test fun uploadChecksForCollisionsBeyondTheCurrentlyListedPage() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setResponseCode(204))
            val wire = Wire().apply { reply = { payload ->
                if (payload["op"].string() == "stat") buildJsonObject { put("exists", payload["resource_id"].string().endsWith("/file.bin")) }
                else buildJsonObject { put("ticket", "test-ticket"); put("resource_id", payload["address"]!!) }
            } }
            val cache = java.nio.file.Files.createTempDirectory("atoll-preview").toFile()
            try {
                val repository = FileRepository("c", wire, OkHttpClient(), server.url("/").toString(), cache)
                val reference = repository.upload(FileDevice("d", "device", true, true), "", "file.bin", listOf("file-2.bin"), { byteArrayOf(1,2,3,4).inputStream() }, 4, "application/octet-stream")
                assertEquals("file-3.bin", reference.name)
                assertEquals(listOf("stat", "stat", "create"), wire.requests.map { it["op"].string() })
                assertEquals(4L, server.takeRequest().bodySize)
            } finally { cache.deleteRecursively() }
        }
    }
}

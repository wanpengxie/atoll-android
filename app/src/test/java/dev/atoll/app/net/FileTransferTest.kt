package dev.atoll.app.net

import dev.atoll.app.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.*

internal class ResourceWire : WireClient {
    override val state = MutableStateFlow<WireState>(WireState.Attached(1, Envelope.EMPTY))
    override val frames = MutableSharedFlow<DownFrame>()
    val requests = mutableListOf<JsonObject>()
    var reply: JsonObject = buildJsonObject { put("ticket", "ticket+/="); put("resource_id", "daemon://device/channel/file") }
    override suspend fun request(type: String, payload: JsonObject): JsonObject { requests.add(payload); return reply }
    override suspend fun history(payload: JsonObject) = HistoryPage(emptyList(), Envelope.EMPTY)
}
class FileTransferTest {
    @Test fun resourceTicketHeadInlineAndEtagUseExactProtocol() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            val wire = ResourceWire()
            val files = FileTransfer(server.url("/").toString(), wire, OkHttpClient())
            val ticket = files.readTicket("c", "file", inline = true)
            assertEquals(JsonPrimitive("read"), wire.requests.single()["op"])
            assertEquals(JsonPrimitive(true), wire.requests.single()["with_content"])
            server.enqueue(MockResponse().addHeader("Content-Length", 123).addHeader("Content-Type", "text/plain; charset=utf-8").addHeader("ETag", "\"e\""))
            val headers = files.head(ticket)
            assertEquals(123L, headers.size); assertEquals("text/plain", headers.mediaType); assertEquals("\"e\"", headers.etag)
            val request = server.takeRequest()
            assertEquals("HEAD", request.method)
            assertTrue(request.path!!.contains("inline=1"))
            assertEquals("ticket+/=", request.requestUrl!!.queryParameter("t"))
            server.enqueue(MockResponse().setResponseCode(304))
            val cached = files.readText(ticket, etag = "\"e\"")
            assertTrue(cached.first.notModified); assertNull(cached.second)
            assertEquals("\"e\"", server.takeRequest().getHeader("If-None-Match"))
        }
    }
    @Test fun previewStopsAtTheByteLimitIncludingChunkedBodies() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            val files = FileTransfer(server.url("/").toString(), ResourceWire(), OkHttpClient())
            val ticket = FileTicket("c", "f", server.url("/files").toString())
            server.enqueue(MockResponse().setBody("x".repeat(100)))
            assertTrue(runCatching { files.readText(ticket, limit = 10) }.exceptionOrNull() is PreviewTooLarge)
            server.enqueue(MockResponse().setChunkedBody("x".repeat(100), 5))
            assertTrue(runCatching { files.readText(ticket, limit = 10) }.exceptionOrNull() is PreviewTooLarge)
            var reads = 0
            val input = object : InputStream() {
                override fun read(): Int { reads++; return if (reads <= 1000) 120 else -1 }
                override fun read(b: ByteArray, off: Int, len: Int): Int { var i = 0; while (i < len) { val c = read(); if (c < 0) return if (i == 0) -1 else i; b[off + i++] = c.toByte() }; return i }
            }
            assertThrows(PreviewTooLarge::class.java) { FileTransfer.copyBounded(input, ByteArrayOutputStream(), 10) }
            assertEquals(11, reads)
        }
    }
    @Test fun downloadStreamsToDiskAndUploadStreamsFromInputWithProgress() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            val wire = ResourceWire()
            val files = FileTransfer(server.url("/").toString(), wire, OkHttpClient())
            val data = ByteArray(2 * 1024 * 1024) { (it % 127).toByte() }
            val destination = File.createTempFile("atoll-download", ".bin")
            try {
                server.enqueue(MockResponse().setBody(Buffer().write(data)))
                var progress = TransferProgress(0, -1)
                files.download(FileTicket("c", "f", server.url("/files").toString()), { destination.outputStream() }) { progress = it }
                assertEquals(data.size.toLong(), destination.length()); assertEquals(1f, progress.fraction!!, 0f)
                assertArrayEquals(data, destination.readBytes())
                val ticket = files.createTicket("c", "daemon://device/channel/new.bin")
                server.enqueue(MockResponse().setResponseCode(204))
                files.upload(ticket, { destination.inputStream() }, destination.length()) { progress = it }
                server.takeRequest()
                val put = server.takeRequest()
                assertEquals("PUT", put.method); assertEquals(data.size.toLong(), put.bodySize)
                assertEquals(data.size.toLong(), progress.bytes)
            } finally { destination.delete() }
        }
    }
    @Test fun signedUrlsDoNotReceiveNodeAuthentication() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            val authenticated = OkHttpClient.Builder().addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer node-secret").build()) }.build()
            val files = FileTransfer(server.url("/").toString(), ResourceWire(), authenticated)
            server.enqueue(MockResponse().setBody("text"))
            files.readText(FileTicket("c", "oss://s/c/f", server.url("/signed").toString(), external = true))
            assertNull(server.takeRequest().getHeader("Authorization"))
        }
    }
}

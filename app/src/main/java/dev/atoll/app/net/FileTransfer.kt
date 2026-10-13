package dev.atoll.app.net

import dev.atoll.app.core.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okio.BufferedSink
import java.io.*

/** No bytes in these metadata/ticket objects. Tickets stay out of persisted state. */
data class FileTicket(val channelId: String, val resourceId: String, val url: String, val external: Boolean = false, val size: Long = -1, val mediaType: String = "")
data class FileHeaders(val size: Long, val mediaType: String, val etag: String, val notModified: Boolean = false)
data class TransferProgress(val bytes: Long, val total: Long) {
    val fraction: Float? get() = if (total > 0) (bytes.toDouble() / total).toFloat().coerceIn(0f, 1f) else null
}
class PreviewTooLarge(val limit: Long) : IOException("文件超过站内预览上限（${limit / 1024 / 1024.0} MB），请下载后打开。")

class FileTransfer(baseUrl: String, private val wire: WireClient, private val client: OkHttpClient) {
    private val base = baseUrl.toHttpUrl()
    // Signed object-storage URLs must never receive the node's auth interceptors/cookies.
    private val externalClient = OkHttpClient.Builder().build()
    suspend fun readTicket(channelId: String, resourceId: String, inline: Boolean = false): FileTicket = ticket(channelId, wire.request(Up.RESOURCE, buildJsonObject {
        put("channel_id", channelId); put("op", "read"); put("resource_id", resourceId); put("with_content", true)
    }), resourceId, inline)
    suspend fun createTicket(channelId: String, address: String): FileTicket = ticket(channelId, wire.request(Up.RESOURCE, buildJsonObject {
        put("channel_id", channelId); put("op", "create"); put("address", address); put("with_content", true)
    }), address, false)
    private fun ticket(channelId: String, receipt: JsonObject, fallback: String, inline: Boolean): FileTicket {
        val token = receipt["ticket"].string()
        require(token.isNotEmpty()) { "服务端没有返回文件票据" }
        val url = base.resolve("/files")!!.newBuilder().addQueryParameter("channel_id", channelId).addQueryParameter("t", token)
        if (inline) url.addQueryParameter("inline", "1")
        return FileTicket(channelId, receipt["resource_id"].string().ifEmpty { fallback }, url.build().toString())
    }
    suspend fun head(ticket: FileTicket, etag: String = ""): FileHeaders = http(ticket).consume(request(ticket, etag).head().build()) {
        it.requireSuccess(); headers(it)
    }
    suspend fun readText(ticket: FileTicket, limit: Long = 512 * 1024, etag: String = ""): Pair<FileHeaders, ByteArray?> = http(ticket).consume(request(ticket, etag).build()) {
        it.requireSuccess()
        val info = headers(it)
        if (info.notModified) return@consume info to null
        if (info.size > limit) throw PreviewTooLarge(limit)
        val out = ByteArrayOutputStream()
        it.body!!.byteStream().use { input -> copyBounded(input, out, limit) }
        info to out.toByteArray()
    }
    /** Streams to a supplied sink (public download or bounded preview cache). */
    suspend fun download(ticket: FileTicket, destination: () -> OutputStream, limit: Long = Long.MAX_VALUE, etag: String = "", progress: (TransferProgress) -> Unit = {}): FileHeaders = http(ticket).consume(request(ticket, etag).build()) {
        it.requireSuccess()
        val info = headers(it)
        if (info.notModified) return@consume info
        if (info.size > limit) throw PreviewTooLarge(limit)
        it.body!!.byteStream().use { input -> destination().use { output -> copyBounded(input, output, limit) { bytes -> progress(TransferProgress(bytes, info.size)) } } }
        info
    }
    suspend fun upload(ticket: FileTicket, source: () -> InputStream, size: Long = -1, mediaType: String = "application/octet-stream", progress: (TransferProgress) -> Unit = {}) {
        val body = object : RequestBody() {
            override fun contentType() = mediaType.toMediaTypeOrNull()
            override fun contentLength() = size
            override fun writeTo(sink: BufferedSink) { source().use { input ->
                val buffer = ByteArray(32 * 1024)
                var written = 0L
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    sink.write(buffer, 0, n); written += n
                    progress(TransferProgress(written, size))
                }
            } }
        }
        http(ticket).consume(request(ticket).put(body).build()) { it.requireSuccess() }
    }
    private fun http(ticket: FileTicket) = if (ticket.external) externalClient else client
    private fun request(ticket: FileTicket, etag: String = "") = Request.Builder().url(ticket.url).apply {
        // Avoid transparent gzip so progress and preview bounds count actual file bytes.
        header("Accept-Encoding", "identity")
        if (etag.isNotEmpty()) header("If-None-Match", etag)
    }
    private fun headers(response: Response) = FileHeaders(response.header("Content-Length")?.toLongOrNull() ?: response.body?.contentLength() ?: -1, response.header("Content-Type").orEmpty().substringBefore(';'), response.header("ETag").orEmpty(), response.code == 304)
    companion object {
        internal fun copyBounded(input: InputStream, output: OutputStream, limit: Long, progress: (Long) -> Unit = {}): Long {
            val buffer = ByteArray(32 * 1024)
            var total = 0L
            while (true) {
                // Read at most one byte past the limit to prove overflow, even without Content-Length.
                val remaining = if (limit == Long.MAX_VALUE) buffer.size else minOf(buffer.size.toLong(), limit - total + 1).toInt()
                val n = input.read(buffer, 0, remaining)
                if (n < 0) break
                total += n
                if (total > limit) throw PreviewTooLarge(limit)
                output.write(buffer, 0, n); progress(total)
            }
            progress(total)
            return total
        }
    }
}

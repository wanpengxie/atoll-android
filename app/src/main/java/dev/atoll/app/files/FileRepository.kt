package dev.atoll.app.files

import dev.atoll.app.core.*
import dev.atoll.app.net.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import java.io.*
import java.security.MessageDigest

/** Injected per channel; listing/mutations remain resource operations, byte I/O is streaming HTTP. */
class FileRepository(val channelId: String, private val wire: WireClient, http: OkHttpClient, baseUrl: String, cacheDir: File, onUnauthorized: (HttpFailure) -> Unit = {}) {
    val obs = ObsClient(baseUrl, http, onUnauthorized)
    val transfer = FileTransfer(baseUrl, wire, http)
    private val storage = StorageResolver(wire, obs)
    private val previewDir = File(cacheDir, FilePaths.encode(channelId))
    var channelName: String = channelId
        private set
    private var diskReady = false
    private val previews = LinkedHashMap<String, CachedPreview>(8, .75f, true)
    private data class CachedPreview(val file: File, val headers: FileHeaders)
    suspend fun initialize(): List<FileDevice> = coroutineScope {
        val profile = async { obs.channelProfile(channelId) }
        val devices = async { obs.channelDevices(channelId) }
        channelName = (profile.await().items().firstOrNull()?.get("declared") as? JsonObject)?.get("qualified_name").string().ifEmpty { channelId }
        projectDevices(devices.await())
    }
    suspend fun list(device: FileDevice, directory: String, cursor: String = ""): FilePage {
        val prefix = FilePaths.prefix(device.name, channelName, directory)
        val reply = wire.request(Up.RESOURCE, buildJsonObject {
            put("channel_id", channelId); put("op", "list"); put("query", buildJsonObject { put("prefix", prefix); put("limit", 100); if (cursor.isNotEmpty()) put("cursor", cursor) })
        })
        return projectFilePage(reply, channelId, prefix, directory)
    }
    suspend fun mkdir(device: FileDevice, directory: String, name: String) {
        require(name.isNotBlank() && name == FilePaths.availableName(name, emptyList()) && name !in setOf(".", "..")) { "文件夹名称无效" }
        wire.request(Up.RESOURCE, buildJsonObject { put("channel_id", channelId); put("op", "create"); put("address", FilePaths.prefix(device.name, channelName, directory) + FilePaths.encode(name)); put("node_type", "directory") })
    }
    suspend fun delete(reference: FileReference) {
        wire.request(Up.RESOURCE, buildJsonObject { put("channel_id", channelId); put("op", "delete"); put("resource_id", reference.resourceId) })
    }
    suspend fun upload(device: FileDevice, directory: String, name: String, occupiedNames: Collection<String>, source: () -> InputStream, size: Long, mediaType: String, progress: (TransferProgress) -> Unit = {}): FileReference {
        val occupied = occupiedNames.toMutableList()
        var safe = FilePaths.availableName(name, occupied)
        var address: String
        while (true) {
            address = FilePaths.prefix(device.name, channelName, directory) + FilePaths.encode(safe)
            val stat = wire.request(Up.RESOURCE, buildJsonObject { put("channel_id", channelId); put("op", "stat"); put("resource_id", address) })
            if (stat["exists"] != JsonPrimitive(true)) break
            occupied.add(safe); safe = FilePaths.availableName(name, occupied)
        }
        val ticket = transfer.createTicket(channelId, address)
        transfer.upload(ticket, source, size, mediaType, progress)
        return FileReference(channelId, ticket.resourceId, safe, mediaType, size)
    }
    private suspend fun readTicket(ref: FileReference, inline: Boolean): FileTicket {
        require(ref.channelId == channelId) { "文件属于另一个频道" }
        return if (ref.resourceId.startsWith("oss://")) storage.resolve(channelId, channelName, ref.resourceId, inline) else transfer.readTicket(channelId, ref.resourceId, inline)
    }
    suspend fun download(reference: FileReference, destination: () -> OutputStream, progress: (TransferProgress) -> Unit = {}): FileHeaders = transfer.download(readTicket(reference, false), destination, progress = progress)
    suspend fun preview(reference: FileReference, progress: (TransferProgress) -> Unit = {}): LoadedPreview = withContext(Dispatchers.IO) {
        val ticket = readTicket(reference, true)
        val facts = if (ticket.external) FileHeaders(ticket.size, ticket.mediaType, "") else transfer.head(ticket)
        val kind = previewKind(reference.name, facts.mediaType.ifEmpty { reference.mediaType })
        if (facts.size > kind.limit) throw PreviewTooLarge(kind.limit)
        val cacheKey = reference.resourceId
        val prior = synchronized(previews) {
            if (!diskReady) {
                previewDir.mkdirs()
                previewDir.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
                diskReady = true
            }
            previews[cacheKey]
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(cacheKey.toByteArray()).joinToString("") { "%02x".format(it) }
        val file = File(previewDir, digest + "." + reference.name.substringAfterLast('.', "bin"))
        val partial = File.createTempFile("$digest.", ".part", previewDir)
        val etag = if (prior?.file?.isFile == true) prior.headers.etag else ""
        try {
            val response = transfer.download(ticket, { partial.outputStream() }, limit = kind.limit, etag = etag, progress = progress)
            val headers = if (response.notModified) prior!!.headers else response
            if (!response.notModified) { check(partial.renameTo(file)) { "无法写入预览缓存" } }
            synchronized(previews) {
                previews[cacheKey] = CachedPreview(file, headers)
                while (previews.size > 6) { val oldest = previews.keys.first(); previews.remove(oldest)?.file?.delete() }
                // Reopened app processes have no in-memory cache map. Remove
                // old unreferenced preview copies as well, keeping disk bounded.
                val retained = previews.values.map { it.file.name }.toSet()
                previewDir.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") && it.name !in retained }?.forEach { it.delete() }
            }
            val text = if (kind in setOf(PreviewKind.TEXT, PreviewKind.MARKDOWN, PreviewKind.HTML, PreviewKind.UNKNOWN)) sniffText(file.readBytes()) else null
            if (kind == PreviewKind.UNKNOWN && text == null) throw IOException("此文件暂不支持站内预览，请下载后打开。")
            LoadedPreview(reference, if (kind == PreviewKind.UNKNOWN) PreviewKind.TEXT else kind, file, text, headers)
        } finally { partial.delete() }
    }
}
data class LoadedPreview(val reference: FileReference, val kind: PreviewKind, val file: File, val text: String?, val headers: FileHeaders)

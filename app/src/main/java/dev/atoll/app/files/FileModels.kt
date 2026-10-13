package dev.atoll.app.files

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import dev.atoll.app.core.string
import dev.atoll.app.core.long
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.text.Collator
import java.util.Locale

@Serializable
data class FileReference(val channelId: String, val resourceId: String, val name: String, val mediaType: String = "application/octet-stream", val size: Long = -1, val line: Int = 0, val openedAt: Long = 0) {
    val key get() = "$channelId:$resourceId:$line"
    fun attachment(): JsonObject = buildJsonObject {
        put("resource_id", resourceId); put("name", name); put("media_type", mediaType)
        if (size >= 0) put("size", size)
        if (line > 0) put("line", line)
    }
}
data class FileEntry(val reference: FileReference, val directory: String? = null, val modifiedAt: Long = 0, val nodeType: String = "regular")
data class FileDevice(val id: String, val name: String, val online: Boolean?, val default: Boolean)
data class FilePage(val entries: List<FileEntry>, val next: String)
enum class FileSort { NAME, MODIFIED, SIZE, KIND }
enum class PreviewKind(val limit: Long) { TEXT(512 * 1024), MARKDOWN(512 * 1024), HTML(512 * 1024), IMAGE(20 * 1024 * 1024), VIDEO(50 * 1024 * 1024), AUDIO(50 * 1024 * 1024), PDF(25 * 1024 * 1024), UNKNOWN(512 * 1024) }

object FilePaths {
    fun normalize(directory: String): String {
        val parts = mutableListOf<String>()
        directory.split(Regex("[/\\\\]+" )).forEach { when (it) { "", "." -> Unit; ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex); else -> parts.add(it) } }
        return if (parts.isEmpty()) "" else parts.joinToString("/", postfix = "/")
    }
    fun parent(directory: String): String = normalize(directory).trimEnd('/').substringBeforeLast('/', "").let(::normalize)
    fun encode(segment: String): String = URLEncoder.encode(segment, "UTF-8").replace("+", "%20").replace("%7E", "~")
    fun decode(segment: String): String = URLDecoder.decode(segment.replace("+", "%2B"), "UTF-8")
    fun prefix(deviceName: String, channelName: String, directory: String): String = "daemon://${encode(deviceName)}/${encode(channelName)}/" + normalize(directory).split('/').filter { it.isNotEmpty() }.joinToString("/", postfix = if (normalize(directory).isNotEmpty()) "/" else "") { encode(it) }
    fun availableName(name: String, occupied: Collection<String>): String {
        val safe = name.replace(Regex("[^\\p{L}\\p{N}._-]+"), "-").trimStart('-').ifEmpty { "upload" }
        val used = occupied.map { it.lowercase() }.toSet()
        if (safe.lowercase() !in used) return safe
        val dot = safe.lastIndexOf('.')
        val stem = if (dot > 0) safe.substring(0, dot) else safe
        val ext = if (dot > 0) safe.substring(dot) else ""
        var i = 2
        while ("$stem-$i$ext".lowercase() in used) i++
        return "$stem-$i$ext"
    }
    /** Entry point for message links; external web URLs are handled by the app. */
    fun fromLink(channelId: String, href: String): FileReference? {
        val located = Regex("^(.*):([1-9][0-9]*)$").matchEntire(href)
        val raw = located?.groupValues?.get(1) ?: href
        if (!(raw.startsWith("daemon://") || raw.startsWith("oss://") || (raw.startsWith('/') && !raw.startsWith("//")))) return null
        val line = located?.groupValues?.get(2)?.toIntOrNull() ?: if (located != null) return null else 0
        val resource = if (raw.startsWith('/')) runCatching { decode(raw) }.getOrNull() ?: return null else raw
        val name = runCatching { decode(resource.substringAfterLast('/')) }.getOrDefault(resource.substringAfterLast('/'))
        return FileReference(channelId, resource, name, line = line)
    }
}

fun projectDevices(observation: JsonObject): List<FileDevice> = observation.items().map { item ->
    val d = item["declared"] as? JsonObject ?: JsonObject(emptyMap())
    val id = d["device_id"].string().ifEmpty { item["key"].string() }
    val measures = ((item["actual"] as? JsonObject)?.get("measures") as? JsonArray).orEmpty()
    val online = measures.firstOrNull { (it as? JsonObject)?.get("name").string() == "online" } as? JsonObject
    FileDevice(id, d["name"].string().ifEmpty { id }, if (online?.get("unknown") == JsonPrimitive(true)) null else (online?.get("value") as? JsonPrimitive)?.booleanOrNull, d["default"] == JsonPrimitive(true) || id == "local-device")
}.filter { it.id.isNotEmpty() }

fun projectFilePage(receipt: JsonObject, channelId: String, prefix: String, directory: String): FilePage = FilePage(receipt.items().mapNotNull { item ->
    val id = item["id"].string().ifEmpty { item["resource_id"].string().ifEmpty { item["address"].string() } }
    if (!id.startsWith(prefix)) return@mapNotNull null
    val relative = id.removePrefix(prefix)
    if (relative.isEmpty() || relative.contains('/')) return@mapNotNull null
    val name = runCatching { FilePaths.decode(relative) }.getOrDefault(relative)
    val meta = item["meta"] as? JsonObject ?: JsonObject(emptyMap())
    val node = meta["node_type"].string().ifEmpty { "regular" }
    FileEntry(FileReference(channelId, id, name, meta["media_type"].string().ifEmpty { "application/octet-stream" }, (meta["size"] as? JsonPrimitive)?.longOrNull ?: -1), if (node == "directory") FilePaths.normalize(directory) + name + "/" else null, meta.long("modified_at"), node)
}, receipt["next"].string())

internal fun JsonObject.items(): List<JsonObject> = (get("items") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
fun sortedFiles(entries: List<FileEntry>, sort: FileSort, descending: Boolean): List<FileEntry> {
    // Web uses zh-CN, numeric:true, sensitivity:base: ignore case and accents.
    val collator = Collator.getInstance(Locale.CHINA).apply { strength = Collator.PRIMARY }
    val keyCollator = Collator.getInstance()
    return entries.sortedWith { a, b ->
        fun rank(f: FileEntry) = if (f.directory != null) 0 else if (f.nodeType == "regular") 1 else 2
        val kind = rank(a) - rank(b)
        if (sort != FileSort.KIND && kind != 0) return@sortedWith kind
        val missingA = when (sort) { FileSort.SIZE -> a.reference.size < 0; FileSort.MODIFIED -> a.modifiedAt <= 0; else -> false }
        val missingB = when (sort) { FileSort.SIZE -> b.reference.size < 0; FileSort.MODIFIED -> b.modifiedAt <= 0; else -> false }
        if (missingA != missingB) return@sortedWith if (missingA) 1 else -1
        val order = when (sort) {
            FileSort.NAME -> naturalNameCompare(a.reference.name, b.reference.name, collator)
            FileSort.MODIFIED -> a.modifiedAt.compareTo(b.modifiedAt)
            FileSort.SIZE -> a.reference.size.compareTo(b.reference.size)
            FileSort.KIND -> kind
        }
        if (order != 0) order * (if (descending) -1 else 1) else {
            val byName = naturalNameCompare(a.reference.name, b.reference.name, collator)
            fun key(f: FileEntry): String = "${if (f.directory != null) "directory" else if (f.nodeType == "regular") "file" else "other"}:${f.reference.resourceId}"
            if (byName != 0) byName else keyCollator.compare(key(a), key(b))
        }
    }
}
fun previewKind(name: String, mediaType: String): PreviewKind {
    val type = mediaType.substringBefore(';').lowercase()
    val ext = name.substringAfterLast('.', "").lowercase()
    val generic = type in setOf("", "application/octet-stream", "binary/octet-stream")
    return when {
        type.startsWith("image/") || generic && ext in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "avif", "svg") -> PreviewKind.IMAGE
        type.startsWith("video/") || generic && ext in setOf("mp4", "webm", "mov", "m4v") -> PreviewKind.VIDEO
        type.startsWith("audio/") || generic && ext in setOf("mp3", "wav", "m4a", "ogg", "flac", "aac") -> PreviewKind.AUDIO
        type == "application/pdf" || ext == "pdf" -> PreviewKind.PDF
        type == "text/html" || ext in setOf("html", "htm") -> PreviewKind.HTML
        type in setOf("text/markdown", "text/x-markdown") || ext in setOf("md", "markdown", "mdown") -> PreviewKind.MARKDOWN
        type.startsWith("text/") || type.endsWith("+json") || type.endsWith("+xml") || type in setOf("application/json", "application/xml", "application/yaml") || ext in setOf("txt", "json", "kt", "kts", "java", "go", "py", "rs", "js", "ts", "tsx", "jsx", "css", "xml", "yaml", "yml", "toml", "conf", "sh", "log", "csv", "sql", "c", "cpp", "h", "md") -> PreviewKind.TEXT
        else -> PreviewKind.UNKNOWN
    }
}
fun sniffText(bytes: ByteArray): String? {
    if (bytes.any { it == 0.toByte() }) return null
    return runCatching { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }.getOrNull()
}
fun fileSize(bytes: Long): String = when {
    bytes < 0 -> "—"; bytes < 1024 -> "$bytes B"; bytes < 1024*1024 -> "${bytes / 1024} KB"
    bytes < 1024*1024*1024 -> String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024)
    else -> String.format(Locale.ROOT, "%.1f GB", bytes / 1024.0 / 1024 / 1024)
}

private fun naturalNameCompare(a: String, b: String, collator: Collator): Int {
    val partsA = Regex("[0-9]+|[^0-9]+").findAll(a).map { it.value }.toList()
    val partsB = Regex("[0-9]+|[^0-9]+").findAll(b).map { it.value }.toList()
    for (i in 0 until minOf(partsA.size, partsB.size)) {
        val x = partsA[i]; val y = partsB[i]
        val order = if (x.first().isDigit() && y.first().isDigit()) {
            val nx = x.trimStart('0').ifEmpty { "0" }; val ny = y.trimStart('0').ifEmpty { "0" }
            nx.length.compareTo(ny.length).takeIf { it != 0 } ?: nx.compareTo(ny)
        } else collator.compare(x, y)
        if (order != 0) return order
    }
    return partsA.size.compareTo(partsB.size)
}

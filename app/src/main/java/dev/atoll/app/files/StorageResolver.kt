package dev.atoll.app.files

import dev.atoll.app.core.*
import dev.atoll.app.net.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Strict segment decoding prevents an encoded slash from becoming authority/path. */
data class StorageAddress(val storageChannel: String, val hostChannel: String, val path: String) {
    companion object {
        fun parse(address: String): StorageAddress? {
            if (!address.startsWith("oss://") || address.removePrefix("oss://").any { it.isWhitespace() || it == '?' || it == '#' }) return null
            val parts = runCatching { address.removePrefix("oss://").split('/').map(FilePaths::decode) }.getOrNull() ?: return null
            if (parts.size < 3 || parts.any { it.isEmpty() || it == "." || it == ".." || '/' in it }) return null
            val path = parts.drop(2).joinToString("/")
            if (path.toByteArray().size > 1024) return null
            return StorageAddress(parts[0], parts[1], path)
        }
    }
}
class StorageResolver(private val wire: WireClient, private val obs: ObsClient) {
    suspend fun resolve(channelId: String, channelName: String, address: String, inline: Boolean): FileTicket {
        val parsed = StorageAddress.parse(address) ?: throw IllegalArgumentException("存储地址无效")
        require(parsed.hostChannel == channelName || parsed.hostChannel == channelId) { "这个文件属于频道 ${parsed.hostChannel}，请到该频道打开它。" }
        val seats = obs.channelActors(channelId).items().mapNotNull { item ->
            val d = item["declared"] as? JsonObject ?: return@mapNotNull null
            d.takeIf { it["body"].string() == "class channel-seat" }?.get("id").string().ifEmpty { null }
        }
        val offering = coroutineScope { seats.map { seat -> async {
            val reply = runCatching { wire.actorRequest(channelId, seat, "actor.describe", Envelope.EMPTY, 15_000) }.getOrNull()
            seat.takeIf { ((reply?.get("words")) as? JsonObject)?.containsKey("storage.get_url") == true }
        } }.awaitAll().filterNotNull() }
        require(offering.isNotEmpty()) { "当前频道没有通往 ${parsed.storageChannel} 的存储座位" }
        val seat = if (offering.size == 1) offering.single() else {
            val directory = channelTree()
            val storageId = directory.firstOrNull { it["qualified_name"].string() == parsed.storageChannel }?.get("id").string()
            val matching = offering.filter { candidate ->
                val reply = wire.actorRequest(channelId, "system", "system.member.get", buildJsonObject { put("member", candidate) })
                val body = (reply["params"] as? JsonObject)?.get("body").string()
                body == parsed.storageChannel || storageId.isNotEmpty() && body == storageId
            }
            require(matching.size == 1) { "无法确定通往 ${parsed.storageChannel} 的存储座位" }
            matching.single()
        }
        val reply = wire.actorRequest(channelId, seat, "storage.get_url", buildJsonObject { put("path", parsed.path); put("inline", inline) })
        val url = reply["url"].string()
        require(url.toHttpUrlOrNull() != null) { "存储服务没有返回可用的链接" }
        return FileTicket(channelId, address, url, external = true, size = (reply["size"] as? JsonPrimitive)?.longOrNull ?: -1, mediaType = reply["media_type"].string())
    }
    private suspend fun channelTree(parent: String = ""): List<JsonObject> {
        val rows = obs.spaceChannels(parent).items().mapNotNull { it["declared"] as? JsonObject }
        return rows + rows.flatMap { channelTree(it["id"].string()) }
    }
}

package dev.atoll.app.app

import java.net.URI
import java.net.URLDecoder

data class AppRoute(val channel: String, val tab: String = "conversation", val focus: String? = null) {
    companion object {
        fun parse(value: String?): AppRoute? = runCatching {
            if (value.isNullOrBlank()) return null
            val uri = URI(value)
            val route = if (uri.scheme == "atoll" && uri.host == "channels") "/channels${uri.rawPath.orEmpty()}${uri.rawQuery?.let { "?$it" }.orEmpty()}"
                else uri.rawFragment?.removePrefix("#") ?: uri.rawPath.orEmpty()
            val parts = route.substringBefore('?').split('/').filter { it.isNotBlank() }
            if (parts.size < 2 || parts.first() != "channels") return null
            val channel = URLDecoder.decode(parts[1], "UTF-8")
            val tab = parts.getOrNull(2)?.takeIf { it in setOf("conversation", "files", "resources", "tasks", "terminal") } ?: "conversation"
            val query = route.substringAfter('?', uri.rawQuery.orEmpty())
            val focus = query.split('&').firstOrNull { it.startsWith("focus=") }?.substringAfter('=')?.let { URLDecoder.decode(it, "UTF-8") }
            AppRoute(channel, tab, focus)
        }.getOrNull()
    }
}

/** Names are resolved only against declared channels; memberships may arrive first. */
fun AppRoute.resolve(records: Collection<ChannelRecord>, memberIds: Set<String>): AppRoute? {
    val id = records.firstOrNull { it.id == channel || it.qualifiedName == channel }?.id
        ?: channel.takeIf { it in memberIds } ?: return null
    return copy(channel = id)
}

fun fallbackChannelRoute(records: Collection<ChannelRecord>, memberIds: Set<String>): AppRoute? {
    val ordered = sortedChannels(records).map { it.first }
    val record = ordered.firstOrNull { it.id in memberIds } ?: ordered.firstOrNull() ?: return null
    return AppRoute(record.id)
}

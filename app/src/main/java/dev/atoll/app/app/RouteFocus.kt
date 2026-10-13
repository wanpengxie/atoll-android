package dev.atoll.app.app

import java.net.URLEncoder

/** Prefixes belong to routing; the remaining opaque key is never split again. */
enum class FocusKind(val prefix: String, val tab: String) {
    Turn("turn", "conversation"), Artifact("artifact", "files"), WorkItem("work_item", "tasks"),
    Participant("participant", "conversation"), Channel("channel", "conversation"), Panel("panel", "conversation")
}
data class RouteFocus(val kind: FocusKind, val key: String) {
    companion object {
        fun parse(raw: String?, tab: String): RouteFocus? {
            if (raw.isNullOrBlank()) return null
            val explicit = FocusKind.entries.firstOrNull { raw.startsWith("${it.prefix}:") }
            val kind = explicit ?: when (tab) { "files" -> FocusKind.Artifact; "tasks" -> FocusKind.WorkItem; else -> FocusKind.Turn }
            val key = if (explicit != null) raw.substringAfter(':') else raw
            return key.takeIf { it.isNotBlank() }?.let { RouteFocus(kind, it) }
        }
    }
}
fun focusedChannelLink(channel: String, kind: FocusKind, key: String): String {
    fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    return "atoll://channels/${encode(channel)}/${kind.tab}?focus=${encode("${kind.prefix}:$key")}" 
}
internal fun centredScrollDelta(itemOffset: Int, itemSize: Int, viewportStart: Int, viewportEnd: Int): Float {
    // A row taller than the viewport starts at the top, keeping its title visible.
    val target = viewportStart + ((viewportEnd - viewportStart - itemSize).coerceAtLeast(0) / 2)
    return (itemOffset - target).toFloat()
}

enum class WorkspacePanel(val key: String) { Running("running"), Recent("recent"), Timers("timers"), Stars("stars") }

package dev.atoll.app.app

import dev.atoll.app.core.string
import kotlinx.serialization.json.JsonObject
import java.net.URLEncoder

fun taskSourceLink(source: JsonObject): String? {
    val channel = source["channelId"].string().ifEmpty { source["channel_id"].string() }
    if (channel.isEmpty()) return null
    val kind = source["objectType"].string()
    val taskKey = source["workItemKey"].string()
    val view = source["view"].string()
    val tab = when {
        taskKey.isNotEmpty() || kind == "work_item" || view == "tasks" -> "tasks"
        kind == "artifact" || view in setOf("files", "artifacts") -> "files"
        else -> "conversation"
    }
    val objectId = source["objectId"].string()
    val focus = when {
        taskKey.isNotEmpty() -> taskKey
        kind == "work_item" -> objectId
        tab == "files" -> source["resourceId"].string().ifEmpty { objectId }
        else -> source["requestId"].string().ifEmpty { source["messageId"].string().ifEmpty { objectId } }.takeIf { it.isNotEmpty() }?.let { "turn:$it" }.orEmpty()
    }
    fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    return "atoll://channels/${encode(channel)}/$tab" + if (focus.isNotEmpty()) "?focus=${encode(focus)}" else ""
}

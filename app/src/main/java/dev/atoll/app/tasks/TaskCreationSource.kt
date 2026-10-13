package dev.atoll.app.tasks

import kotlinx.serialization.json.*

/** The host supplies the actual message/turn sequence, never an index in the displayed window. */
fun taskCreationSource(channelId: String, objectId: String, seq: Long, requestId: String = ""): JsonObject {
    require(channelId.isNotBlank() && objectId.isNotBlank() && seq > 0) { "任务来源需要频道、消息编号和账本序号" }
    return buildJsonObject {
        put("channelId",channelId);put("view","dynamic")
        put("objectType",if (requestId.isEmpty()) "message" else "turn")
        put("objectId",objectId);put("seq",seq)
        if (requestId.isNotEmpty()) put("requestId",requestId)
    }
}

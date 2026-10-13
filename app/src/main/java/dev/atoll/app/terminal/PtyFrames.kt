package dev.atoll.app.terminal

import kotlinx.serialization.json.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import dev.atoll.app.core.string

/** /pty is deliberately separate from the versioned ledger protocol. */
sealed interface PtyControl {
    val id: Long
    data class Ready(override val id: Long, val session: String) : PtyControl
    data class Exit(override val id: Long, val reason: String) : PtyControl
    data class Error(override val id: Long, val code: String, val detail: String) : PtyControl
}
data class PtyBytes(val id: Long, val bytes: ByteArray)
object PtyFrames {
    fun open(id: Long, channelId: String, deviceId: String, session: String, cols: Int, rows: Int): String = buildJsonObject {
        put("type", "open"); put("id", id); put("channel_id", channelId); put("device", deviceId)
        if (session.isNotEmpty()) put("session", session)
        put("cols", cols); put("rows", rows)
    }.toString()
    fun resize(id: Long, cols: Int, rows: Int): String = buildJsonObject { put("type", "resize"); put("id", id); put("cols", cols); put("rows", rows) }.toString()
    fun end(id: Long, close: Boolean): String = buildJsonObject { put("type", if (close) "close" else "detach"); put("id", id) }.toString()
    fun decode(text: String): PtyControl? {
        val value = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val id = (value["id"] as? JsonPrimitive)?.longOrNull?.takeIf { it in 1..0xffff_ffffL } ?: return null
        return when (value["type"].string()) {
            "ready" -> PtyControl.Ready(id, value["session"].string())
            "exit" -> PtyControl.Exit(id, value["reason"].string().ifEmpty { "会话已结束" })
            "error" -> PtyControl.Error(id, value["code"].string(), value["detail"].string().ifEmpty { "无法打开终端" })
            else -> null
        }
    }
    fun binary(id: Long, bytes: ByteArray): ByteString {
        require(id in 1..0xffff_ffffL)
        val frame = ByteArray(4 + bytes.size)
        for (i in 0..3) frame[i] = (id ushr (24 - 8 * i)).toByte()
        bytes.copyInto(frame, 4)
        return frame.toByteString()
    }
    fun decode(frame: ByteString): PtyBytes? {
        if (frame.size < 4) return null
        var id = 0L
        for (i in 0..3) id = (id shl 8) or (frame[i].toLong() and 255)
        if (id == 0L) return null
        return PtyBytes(id, frame.substring(4).toByteArray())
    }
}
sealed interface PtyStatus {
    data object Connecting : PtyStatus
    data class Ready(val session: String) : PtyStatus
    data class Reconnecting(val attempt: Int, val delayMs: Long) : PtyStatus
    data class Ended(val detail: String) : PtyStatus
    data object Detached : PtyStatus
}

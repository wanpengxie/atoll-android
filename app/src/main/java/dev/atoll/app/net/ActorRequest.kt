package dev.atoll.app.net

import dev.atoll.app.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.util.UUID

/** Actor calls answer on feed; register the response listener before submitting. */
suspend fun WireClient.actorRequest(channelId: String, actorId: String, type: String, payload: JsonObject, timeoutMs: Long = 30_000, messageId: String = UUID.randomUUID().toString()): JsonObject = coroutineScope {
    if (state.value !is WireState.Attached) throw WireException(type, "unavailable", "wire is not attached")
    val id = messageId
    val answer = async(start = CoroutineStart.UNDISPATCHED) {
        withTimeout(timeoutMs) {
            val row = frames.filterIsInstance<DownFrame.Feed>().first {
                val envelope = it.row.envelope
                it.row.channelId == channelId && envelope.isTerminal && (envelope.parentId == id || envelope.correlationId == id)
            }.row.envelope
            val body = row.body
            if (row.status == "failed") {
                val error = body["error"] as? JsonObject ?: body
                throw WireException(type, error["code"].string().ifEmpty { body["error_code"].string().ifEmpty { "actor_failed" } }, error["detail"].string().ifEmpty { error["message"].string().ifEmpty { body["detail"].string() } })
            }
            body["value"] as? JsonObject ?: body
        }
    }
    val connection = launch(start = CoroutineStart.UNDISPATCHED) {
        val lost = state.first { it !is WireState.Attached }
        throw WireException(type, if (lost is WireState.Incompatible) "version_incompatible" else "closed", "connection ended before the actor answered")
    }
    try {
        request(Up.SUBMIT, buildJsonObject {
            put("channel_id", channelId); put("id", id); put("kind", "request"); put("msg_type", type)
            put("payload", payload); put("audience", buildJsonArray { add(actorId) }); put("visibility", "public")
            put("expires_at_ms", System.currentTimeMillis() + timeoutMs)
        })
        answer.await()
    } finally { connection.cancel(); answer.cancel() }
}

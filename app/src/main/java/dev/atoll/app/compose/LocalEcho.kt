package dev.atoll.app.compose

import dev.atoll.app.core.Envelope
import dev.atoll.app.model.TimelineEntry
import dev.atoll.app.model.Turn
import kotlinx.serialization.json.*

/** A view of a durable intent; it is never applied to the ledger replica. */
fun LocalSubmission.localEnvelope(selfId: String): Envelope = Envelope(buildJsonObject {
    put("id", messageId); put("channel_id", channelId); put("type", frame.text("msg_type"))
    put("kind", frame.text("kind").ifEmpty { "request" }); put("ts", createdAt)
    put("sender", buildJsonObject { put("id", selfId); put("kind", "human") })
    put("audience", frame.array("audience")); put("parent_id", frame.text("parent_id"))
    put("visibility", frame.text("visibility").ifEmpty { "public" })
    put("expires_at", expiresAt)
    val payload = frame["payload"] as? JsonObject ?: buildJsonObject { put("text", text) }
    put("payload", if ("body" in payload) payload else buildJsonObject { put("body", payload) })
    put("local_submission_state", state.name.lowercase())
})

fun LocalSubmission.localTimelineEntry(selfId: String): TimelineEntry {
    val envelope = localEnvelope(selfId)
    return if (envelope.kind == "request") TimelineEntry("turn", 0, turn = Turn(envelope, 0), local = true)
        else TimelineEntry("standalone", 0, envelope = envelope, local = true)
}

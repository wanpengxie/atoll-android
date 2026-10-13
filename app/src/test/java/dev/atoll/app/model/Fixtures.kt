package dev.atoll.app.model

import dev.atoll.app.core.*
import kotlinx.serialization.json.*

internal const val CHANNEL = "c0"
internal const val SELF = "human:root:1"
internal const val AGENT = "agent:claude:1"
internal fun row(seq: Long, id: String, kind: String = "request", type: String = "agent.ask", parent: String = "", correlation: String = "", sender: String = SELF, audience: List<String> = listOf(AGENT), body: JsonObject = body("text" to id), visibility: String = "public", expires: Long = 0, ts: Long = seq * 1000, canonical: Boolean = true): Row {
    val json = buildJsonObject {
        put("id", id); put("kind", kind); put("type", type); put("parent_id", parent); put("correlation_id", correlation.ifEmpty { id }); put("visibility", visibility); put("ts", ts); put("expires_at", expires)
        put("sender", buildJsonObject { put("id", sender); put("kind", sender.substringBefore(':')) })
        put("audience", JsonArray(audience.map(::JsonPrimitive)))
        put("payload", if (canonical) buildJsonObject { put("body", body) } else body)
    }
    return Row(CHANNEL, seq, Envelope(json))
}
internal fun body(vararg values: Pair<String, Any?>): JsonObject = buildJsonObject {
    values.forEach { (key, value) -> put(key, when (value) {
        null -> JsonNull
        is JsonElement -> value
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is List<*> -> JsonArray(value.map { JsonPrimitive(it.toString()) })
        else -> JsonPrimitive(value.toString())
    }) }
}
internal fun response(seq: Long, parent: String, status: String, id: String = "$parent-$seq", extra: JsonObject = body()): Row =
    row(seq, id, kind = "response", parent = parent, sender = AGENT, audience = listOf(SELF), body = JsonObject(body("status" to status) + extra))
internal fun note(seq: Long, id: String = "note-$seq") = row(seq, id, kind = "event", type = "human.message")
internal fun ChannelReplica.add(vararg rows: Row): ModelChange = apply(RowBatch(CHANNEL, rows.toList(), Origin.LIVE))
internal fun ChannelReplica.turn(id: String): Turn = snapshot().timeline.flatMap { listOf(it) + it.thread }.first { it.turn?.requestId == id }.turn!!

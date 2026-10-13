package dev.atoll.app.waiting

import dev.atoll.app.core.*
import dev.atoll.app.model.Turn
import kotlinx.serialization.json.*

internal fun JsonObject.text(key: String) = get(key).string()
internal fun JsonObject.flag(key: String) = (get(key) as? JsonPrimitive)?.booleanOrNull == true
internal fun JsonObject.number(key: String) = (get(key) as? JsonPrimitive)?.longOrNull ?: 0L
internal fun payload(vararg fields: Pair<String, String>) = JsonObject(fields.associate { it.first to JsonPrimitive(it.second) })
internal fun Turn.actor() = request.audience.firstOrNull().orEmpty()
internal fun Turn.exactText(): String = listOf("text", "new_text", "body", "answer", "message", "prompt").firstNotNullOfOrNull { key -> request.body.text(key).takeIf { it.isNotBlank() } }.orEmpty()
internal fun Turn.controlFrame(): JsonObject = provisional.asReversed().firstOrNull { it.envelope.status in setOf("queued", "processing") }?.envelope?.body ?: Envelope.EMPTY

data class WaitingControl(val word: String, val label: String, val payload: JsonObject)
data class WaitingActions(val insert: WaitingControl? = null, val canEdit: Boolean = false, val canCancel: Boolean = false,
    val dismiss: WaitingControl? = null, val extras: List<WaitingControl> = emptyList(), val departed: Boolean = false, val steering: Boolean = false)

/** Action availability follows the web dock. Membership always comes from waitingTurns. */
object WaitingRules {
    fun leaseSupported(manifest: JsonObject?): Boolean {
        val words = manifest?.get("words") as? JsonObject ?: return false
        return listOf("agent.replace", "agent.unhold").all { type ->
            val word = words[type] as? JsonObject ?: return@all false
            val raw = word["input_schema"] ?: word["inputSchema"]
            val schema = raw as? JsonObject ?: (raw as? JsonPrimitive)?.content?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
            (schema?.get("properties") as? JsonObject)?.containsKey("expected_hold_id") == true
        }
    }
    fun actions(turn: Turn, selfId: String, writable: Boolean, actorIds: Set<String>?, manifest: JsonObject?): WaitingActions {
        val frame = turn.controlFrame()
        val departed = actorIds != null && turn.actor().isNotEmpty() && turn.actor() !in actorIds
        val open = turn.terminal == null
        val eligible = open && writable && !departed
        val controls = (frame["controls"] as? JsonArray).orEmpty().mapNotNull { raw ->
            val entry = raw as? JsonObject ?: return@mapNotNull null
            val word = entry.text("word").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val declared = (entry["payload"] as? JsonObject)?.takeIf { it.isNotEmpty() }
            WaitingControl(word, entry.text("label").ifEmpty { word.substringAfterLast('.') }, declared ?: payload("target" to turn.requestId))
        }
        val owned = selfId.isNotEmpty() && turn.request.senderId == selfId
        val callerCancel = open && writable && owned && frame.text("status") == "queued"
        val dismiss = controls.find { it.word == "agent.dismiss" }?.takeIf { eligible && !owned && frame.text("status") == "queued" }
        return WaitingActions(
            insert = controls.find { it.word == "agent.steer" }?.takeIf { eligible },
            canEdit = eligible && turn.exactText().isNotEmpty() && controls.any { it.word == "agent.replace" } && leaseSupported(manifest),
            canCancel = callerCancel || dismiss != null, dismiss = dismiss,
            extras = if (eligible) controls.filter { it.word !in setOf("agent.replace", "agent.interrupt", "agent.steer", "agent.dismiss") } else emptyList(),
            departed = departed, steering = frame.flag("steering"),
        )
    }
}

package dev.atoll.app.compose

import dev.atoll.app.core.Envelope
import dev.atoll.app.model.Turn
import dev.atoll.app.waiting.WaitingRules
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

data class TimelineActions(val reply: ReplyTarget? = null, val editTargetId: String = "", val stopTargetId: String = "")

/** Keep row affordances aligned with the composer and safe editor's owner contracts. */
object TimelineActionPolicy {
    fun actions(envelope: Envelope, turn: Turn?, text: String, selfId: String,
        roster: List<ComposerActor>, writable: Boolean, online: Boolean, newerUnloaded: Boolean,
        safeEditActors: Set<String>): TimelineActions {
        val author = roster.firstOrNull { it.id == envelope.senderId && it.kind in setOf("agent", "human") }
        val isRequest = turn?.requestId == envelope.id
        val replyable = writable && envelope.id.isNotEmpty() && envelope.senderId != selfId && author != null &&
            (turn == null || isRequest || envelope.isTerminal)
        val excerpt = text.replace(Regex("\\s+"), " ").trim()
        val reply = if (replyable) ReplyTarget(envelope.id, author!!.id, author.name.ifEmpty { author.id },
            if (excerpt.length > 96) excerpt.take(95) + "…" else excerpt) else null
        val frame = turn?.provisional?.asReversed()?.firstOrNull { it.envelope.status in setOf("queued", "processing") }?.envelope?.body
        val hasReplace = (frame?.get("controls") as? JsonArray).orEmpty().any { (it as? JsonObject)?.text("word") == "agent.replace" }
        val editable = isRequest && turn != null && turn.request.type == "agent.ask" && turn.terminal == null &&
            selfId.isNotEmpty() && envelope.senderId == selfId && text.isNotBlank() && writable && online && !newerUnloaded &&
            turn.request.audience.singleOrNull() in safeEditActors &&
            roster.any { it.id == turn.request.audience.singleOrNull() && it.kind == "agent" } && hasReplace
        val stoppable = isRequest && turn != null && online && !newerUnloaded && WaitingRules.interrupt(turn, writable) != null
        return TimelineActions(reply, if (editable) turn!!.requestId else "", if (stoppable) turn!!.requestId else "")
    }
}

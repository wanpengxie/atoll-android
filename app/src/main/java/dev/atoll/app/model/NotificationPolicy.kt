package dev.atoll.app.model

import dev.atoll.app.core.Envelope
import dev.atoll.app.core.string

data class UnreadCount(val count: Int = 0, val pending: Boolean = false, val unknown: Boolean = false)

/** Same classification and conversation-root counting as the web notification policy. */
object NotificationPolicy {
    fun canonicalTimerFire(envelope: Envelope): Boolean = envelope.kind == "event" && envelope.id.startsWith("timer:") &&
        envelope.parentId.isEmpty() && envelope.json["correlation_id"].string() == envelope.id && envelope.senderKind == "agent" &&
        envelope.senderId.isNotEmpty() && envelope.audience == listOf(envelope.senderId)

    fun disposition(state: TimelineSource, envelope: Envelope): String {
        if (!envelope.hasBody && envelope.visibility != "system") return "not_presented"
        val turn = if (envelope.kind == "response" && envelope.parentId.isNotEmpty())
            state.timeline.asSequence().flatMap { sequenceOf(it) + it.thread.asSequence() }
                .mapNotNull { it.turn }.firstOrNull { it.requestId == envelope.parentId } else null
        val type = turn?.request?.type ?: envelope.type
        if (envelope.visibility == "system" || turn?.request?.visibility == "system") return "system_narration"
        if (type.startsWith("ui.")) return "ui_protocol"
        if (type in ConversationVisibility.hiddenTurnTypes) return "hidden_control"
        if (canonicalTimerFire(envelope)) return "timer_control"
        if (type == "agent.timer.wake" && !(envelope.isTerminal && ConversationVisibility.hasReadableTerminalContent(envelope))) return "timer_control"
        if (envelope.kind == "response" && envelope.parentId.isNotEmpty() && turn == null) return "notification_context_unknown"
        val request = if (envelope.kind == "request") envelope else turn?.request
        if (type.startsWith("tool.") && !request?.parentId.isNullOrEmpty()) return "nested_tool_lifecycle"
        if (envelope.kind == "event") {
            if (!ConversationVisibility.personConversationEventVisible(envelope)) return "not_presented"
            return if (type == "agent.provider.run") "final" else "event"
        }
        if (envelope.kind == "request") {
            val ownTurn = state.timeline.asSequence().flatMap { sequenceOf(it) + it.thread.asSequence() }
                .mapNotNull { it.turn }.firstOrNull { it.requestId == envelope.id }
            return if (ownTurn != null && !ConversationVisibility.personConversationRequestVisible(ownTurn)) "not_presented" else "request"
        }
        if (envelope.kind != "response") return "not_message"
        if (!envelope.isTerminal) return "not_final"
        if (!ConversationVisibility.hasReadableTerminalContent(envelope)) return "not_presented"
        if (turn?.terminal != null && turn.terminal.id != envelope.id) return "terminal_conflict"
        if (turn != null && !ConversationVisibility.personConversationTurnVisible(turn)) return "not_presented"
        return "final"
    }

    fun unread(state: TimelineSource, selfId: String, readPosition: Long, complete: Boolean = true): UnreadCount {
        if (selfId.isEmpty()) return UnreadCount(pending = true, unknown = true)
        val roots = mutableSetOf<String>()
        var unknown = false
        var related: ((Envelope) -> Boolean)? = null
        val rootByEnvelope = mutableMapOf<String, String>()
        for (entry in state.timeline) entry.envelopes().forEach { rootByEnvelope[it.id] = entry.id }
        for (row in state.rows) {
            val envelope = row.envelope
            if (row.seq <= readPosition || envelope.senderId == selfId) continue
            val disposition = disposition(state, envelope)
            if (disposition == "notification_context_unknown") { unknown = true; continue }
            if (disposition !in setOf("request", "final")) continue
            if (related == null) related = ConversationPresentation.conversationRelation(state, selfId)
            if (!related(envelope)) continue
            val root = rootByEnvelope[envelope.id] ?: envelope.parentId.ifEmpty { envelope.id }
            if (root.isEmpty()) unknown = true else roots += root
        }
        return UnreadCount(roots.size, pending = !complete, unknown = unknown)
    }
}

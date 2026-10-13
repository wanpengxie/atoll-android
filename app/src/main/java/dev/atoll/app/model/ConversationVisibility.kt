package dev.atoll.app.model

import dev.atoll.app.core.Envelope
import kotlinx.serialization.json.*

object ConversationVisibility {
    val agentMessageTypes = setOf("agent.ask", "agent.queue", "agent.replace")
    val hiddenTurnTypes = setOf("agent.hold", "agent.unhold", "agent.hold_expired", "agent.interrupt", "agent.dismiss", "agent.context", "agent.options", "agent.fork", "actor.describe", "storage.get_url")
    val terminalTransportFields = setOf("status", "reason", "error_code", "detail", "cancelled", "closed_by", "controls", "process", "usage", "merged_into", "replaced_by", "preempted_by")
    fun isControlOnlyBody(type: String, body: JsonObject): Boolean = type in hiddenTurnTypes ||
        (type == "agent.steer" && body.text("text").ifEmpty { body.text("body") }.ifEmpty { body.text("message") }.isBlank() && attachments(body).isEmpty())
    fun isControlOnlyTurn(turn: Turn) = isControlOnlyBody(turn.request.type, turn.request.body)
    fun requestExpired(request: Envelope, now: Long = System.currentTimeMillis()): Boolean {
        val deadline = request.expiresAt.takeIf { it != 0L } ?: request.body.number("expires_at")
        return deadline > 0 && deadline <= now
    }
    fun hasReadableMessageContent(envelope: Envelope): Boolean {
        val body = envelope.body
        return body.text("text").ifEmpty { body.text("body") }.ifEmpty { body.text("message") }.isNotBlank() || attachments(body).isNotEmpty() || body.array("content").any {
            val item = it as? JsonObject ?: return@any false
            (item.text("type") == "text" && item.text("text").isNotBlank()) || (item.text("type") == "resource_link" && item.text("uri").isNotBlank())
        }
    }
    fun hasReadableTerminalContent(envelope: Envelope) = hasReadableMessageContent(envelope) ||
        (envelope.status == "failed" && envelope.body.text("detail").ifEmpty { envelope.body.text("reason") }.ifEmpty { envelope.body.text("error_code") }.isNotBlank()) || envelope.body.keys.any { it !in terminalTransportFields }
    fun weakNoticeEvent(envelope: Envelope) = envelope.kind == "event" && envelope.visibility != "system" && envelope.type !in setOf("agent.task", "agent.provider.run")
    fun personConversationEventVisible(envelope: Envelope) = envelope.kind == "event" && envelope.visibility != "system" && !envelope.type.startsWith("ui.") && envelope.type !in hiddenTurnTypes && !envelope.type.startsWith("terminal.") && !envelope.body.flag("transient") && hasReadableMessageContent(envelope)
    fun timelineTurnVisible(turn: Turn, editingTargetId: String = ""): Boolean {
        if (isControlOnlyTurn(turn)) return false
        if (turn.request.type in setOf("agent.select", "agent.new")) return turn.terminal?.status == "completed"
        if (turn.requestId == editingTargetId) return true
        return turn.request.type !in agentMessageTypes || turn.terminal != null || turn.latestCoreStatus == "processing"
    }
    fun personConversationRequestVisible(turn: Turn) = !turn.request.type.startsWith("ui.") && turn.request.type !in hiddenTurnTypes && turn.request.type !in agentMessageTypes && turn.request.type !in setOf("agent.select", "agent.new")
    fun personConversationTurnVisible(turn: Turn, editingTargetId: String = "") = !turn.request.type.startsWith("ui.") && timelineTurnVisible(turn, editingTargetId)
    internal fun attachments(body: JsonObject): JsonArray = (body["attachments"] ?: body["files"]) as? JsonArray ?: JsonArray(emptyList())
}

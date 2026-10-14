package dev.atoll.app.compose

import dev.atoll.app.core.Envelope
import dev.atoll.app.model.TimelineEntry
import dev.atoll.app.model.TimelineSource
import dev.atoll.app.model.Turn
import dev.atoll.app.ui.screens.TurnKind
import dev.atoll.app.ui.screens.TurnViewState
import kotlinx.serialization.json.*

/** Historical truncation is not a lost turn; accepted edit facts survive compact closures. */
object TurnStatePolicy {
    fun presentation(turn: Turn, lostReason: String = "", newerUnloaded: Boolean = false,
        source: TimelineSource? = null): TurnViewState {
        val terminal = turn.terminal
        val merged = terminal?.body?.text("merged_into").orEmpty()
        val preempted = terminal?.body?.text("preempted_by").orEmpty()
        val stopped = terminal?.status == "failed" &&
            listOf(terminal.body.text("error_code"), terminal.body.text("reason")).contains("interrupted")
        val kind = when {
            merged.isNotEmpty() -> TurnKind.Merged
            preempted.isNotEmpty() -> TurnKind.Preempted
            turn.terminalClosureOnly -> TurnKind.Normal
            stopped -> TurnKind.Stopped
            terminal?.status == "failed" -> TurnKind.Failed
            terminal == null && !newerUnloaded && lostReason.isNotEmpty() -> TurnKind.Lost
            else -> TurnKind.Normal
        }
        return TurnViewState(kind, turn.requestId, merged.ifEmpty { preempted },
            lostReason.takeIf { kind == TurnKind.Lost }.orEmpty(), edited(turn, source), receiverId = turn.request.audience.firstOrNull().orEmpty())
    }

    private fun edited(turn: Turn, source: TimelineSource?): Boolean {
        if (turn.request.type != "agent.replace" || turn.request.body.text("target").isEmpty()) return false
        if (turn.terminal?.status == "completed" || turn.provisional.any { it.envelope.status in setOf("queued", "processing") }) return true
        // Only the rare replacement without its own admission needs the predecessor proof.
        fun find(entry: TimelineEntry): Turn? = entry.turn?.takeIf { it.requestId == turn.request.body.text("target") }
            ?: entry.thread.firstNotNullOfOrNull(::find)
        val predecessor = source?.timeline?.firstNotNullOfOrNull(::find)
        return predecessor?.terminal?.body?.text("replaced_by") == turn.requestId
    }

    fun canResend(turn: Turn, selfId: String, lostReason: String = "", newerUnloaded: Boolean = false): Boolean {
        if (selfId.isEmpty() || turn.request.senderId != selfId || turn.request.type !in setOf("agent.ask", "agent.replace", "human.message")) return false
        if (turn.request.audience.isEmpty() || turn.terminalClosureOnly || newerUnloaded) return false
        return presentation(turn, lostReason).kind in setOf(TurnKind.Failed, TurnKind.Lost) &&
            turn.terminal?.body?.text("replaced_by").isNullOrEmpty()
    }

    /** A resend is a fresh conversation intent, never a replay of an expired edit lease. */
    fun resendFrame(request: Envelope, id: String, now: Long): JsonObject {
        require(request.type in setOf("agent.ask", "agent.replace", "human.message"))
        val replace = request.type == "agent.replace"
        val body = if (replace) buildJsonObject {
            put("text", request.body.text("new_text"))
            request.body["attachments"]?.let { put("attachments", it) }
        } else request.body
        require(body.text("text").isNotBlank() || body.array("attachments").isNotEmpty()) { "消息内容不能为空" }
        return buildJsonObject {
            put("channel_id", request.channelId); put("id", id); put("kind", "request")
            put("msg_type", if (replace) "agent.ask" else request.type)
            put("audience", JsonArray(request.audience.map(::JsonPrimitive)))
            put("visibility", request.visibility); put("payload", body); put("expires_at_ms", now + 24 * 60 * 60_000)
            request.parentId.takeIf(String::isNotEmpty)?.let { put("parent_id", it) }
        }
    }
}

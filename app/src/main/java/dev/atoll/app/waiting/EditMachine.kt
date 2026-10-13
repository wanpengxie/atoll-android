package dev.atoll.app.waiting

import dev.atoll.app.compose.EditorCommand
import dev.atoll.app.core.*
import dev.atoll.app.model.Turn
import kotlinx.serialization.json.*

enum class EditPhase { IDLE, HOLDING, EDITING, SUBMITTING }
data class EditSession(val id: String, val actorId: String, val targetId: String, val holdId: String,
    val oldText: String, val text: String = oldText, val attachments: JsonArray = JsonArray(emptyList()),
    val originallyProcessing: Boolean = false, val held: Boolean = false, val probed: Boolean = false,
    val admitted: Boolean = false, val replacementId: String = "", val leaseUntil: Long = 0,
    val command: EditorCommand = EditorCommand(text = oldText))
data class EditState(val phase: EditPhase = EditPhase.IDLE, val session: EditSession? = null, val notice: String = "", val error: String = "")
internal sealed interface EditEffect {
    data class Hold(val session: EditSession) : EditEffect
    data class Probe(val session: EditSession) : EditEffect
    data class Replace(val session: EditSession) : EditEffect
    data class Release(val session: EditSession) : EditEffect
}
internal data class EditTransition(val state: EditState, val effects: List<EditEffect> = emptyList())

/** Pure transition logic. Only the VM's event loop owns an instance's state. */
internal object EditMachine {
    fun begin(state: EditState, session: EditSession): EditTransition = if (state.phase != EditPhase.IDLE) EditTransition(state) else
        EditTransition(EditState(EditPhase.HOLDING, session), listOf(EditEffect.Hold(session)))
    fun held(state: EditState, session: EditSession): EditTransition {
        val current = state.session
        if (current?.id != session.id) return EditTransition(state, listOf(EditEffect.Release(session.copy(held = true))))
        val next = current.copy(held = true)
        return EditTransition(state.copy(session = next), listOf(EditEffect.Probe(next)))
    }
    fun probed(state: EditState, id: String, body: JsonObject): EditTransition {
        val session = state.session?.takeIf { it.id == id } ?: return EditTransition(state)
        val frozen = body["frozen"] as? JsonObject
        if (frozen?.text("held_by") != session.holdId) return exit(state, "已退出编辑：编辑暂停已解除或被另一项控制接管")
        return advance(state.copy(session = session.copy(probed = true, leaseUntil = frozen.number("until"))))
    }
    private fun advance(state: EditState): EditTransition {
        val session = state.session ?: return EditTransition(state)
        return EditTransition(if (state.phase == EditPhase.HOLDING && session.held && session.probed && session.admitted) state.copy(phase = EditPhase.EDITING) else state)
    }
    fun target(state: EditState, turn: Turn?): EditTransition {
        val session = state.session ?: return EditTransition(state)
        if (turn == null || turn.requestId != session.targetId) return EditTransition(state)
        val terminal = turn.terminal
        if (terminal != null) {
            val replacement = terminal.body.text("replaced_by")
            if (session.replacementId.isNotEmpty() && replacement == session.replacementId) return accepted(state, session.replacementId)
            return exit(state, "已退出编辑：这条请求已结束")
        }
        val ready = !session.originallyProcessing || turn.provisional.any { row ->
            val body = row.envelope.body
            body.text("status") == "queued" && body.flag("resumed") && body.text("held_by") == session.holdId
        }
        return advance(state.copy(session = session.copy(admitted = ready)))
    }
    fun input(state: EditState, text: String, external: Boolean = false, selectionStart: Int = text.length, selectionEnd: Int = selectionStart): EditTransition {
        val session = state.session?.takeIf { state.phase == EditPhase.EDITING } ?: return EditTransition(state)
        return EditTransition(state.copy(session = session.copy(text = text,
            command = if (external) EditorCommand(session.command.version + 1, text, selectionStart, selectionEnd) else session.command), error = ""))
    }
    fun submit(state: EditState, replacementId: String): EditTransition {
        val session = state.session?.takeIf { state.phase == EditPhase.EDITING && it.text.isNotBlank() } ?: return EditTransition(state)
        val next = session.copy(replacementId = replacementId)
        return EditTransition(state.copy(phase = EditPhase.SUBMITTING, session = next, error = ""), listOf(EditEffect.Replace(next)))
    }
    fun accepted(state: EditState, replacementId: String): EditTransition {
        val session = state.session?.takeIf { state.phase == EditPhase.SUBMITTING && it.replacementId == replacementId } ?: return EditTransition(state)
        return exit(state, "编辑已提交")
    }
    fun failed(state: EditState, id: String, detail: String, definite: Boolean): EditTransition {
        val session = state.session?.takeIf { it.id == id } ?: return EditTransition(state)
        if (state.phase == EditPhase.SUBMITTING && definite) return EditTransition(state.copy(phase = EditPhase.EDITING, session = session.copy(replacementId = ""), error = detail))
        return exit(state, if (state.phase == EditPhase.SUBMITTING) "已退出编辑：修改结果待确认，$detail" else "已退出编辑：$detail")
    }
    fun disconnected(state: EditState): EditTransition = exit(state, if (state.phase == EditPhase.SUBMITTING) "已退出编辑：连接已断开，修改结果待确认" else "已退出编辑：连接已断开，改动没有发出")
    fun exit(state: EditState, notice: String = "已退出编辑"): EditTransition {
        val session = state.session ?: return EditTransition(state.copy(notice = notice, error = ""))
        // The request ID was chosen before sending. Even a lost hold ACK can be released by CAS.
        return EditTransition(EditState(notice = notice), listOf(EditEffect.Release(session)))
    }
}

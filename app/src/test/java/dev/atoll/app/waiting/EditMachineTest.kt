package dev.atoll.app.waiting

import dev.atoll.app.core.*
import dev.atoll.app.model.Turn
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class EditMachineTest {
    private val session = EditSession("session", "agent:a", "target", "hold-request", "old", admitted = true)
    private fun held(state: EditState, owner: String = "hold-request"): EditState {
        val holding = EditMachine.held(state, session).state
        return EditMachine.probed(holding, "session", buildJsonObject { put("frozen", payload("held_by" to owner)) }).state
    }
    private fun editing() = held(EditMachine.begin(EditState(), session).state)
    private fun turn(status: String = "queued", terminalBody: JsonObject? = null, resumed: Boolean = false, owner: String = "hold-request"): Turn {
        val request = Envelope(buildJsonObject { put("id", "target"); put("kind", "request"); put("audience", buildJsonArray { add("agent:a") }) })
        val progress = Envelope(buildJsonObject { put("kind", "response"); put("payload", buildJsonObject { put("body", buildJsonObject { put("status", status); put("resumed", resumed); put("held_by", owner) }) }) })
        val terminal = terminalBody?.let { Envelope(buildJsonObject { put("kind", "response"); put("payload", buildJsonObject { put("body", it) }) }) }
        return Turn(request, 1, listOf(Row("c", 2, progress)), terminal)
    }
    @Test fun holdMustBeAcknowledgedAndContextMustMatchLease() {
        val begin = EditMachine.begin(EditState(), session)
        assertEquals(EditPhase.HOLDING, begin.state.phase)
        assertEquals(session.holdId, (begin.effects.single() as EditEffect.Hold).session.holdId)
        assertEquals(EditPhase.EDITING, held(begin.state).phase)
        val replaced = held(begin.state, owner = "other-hold")
        assertEquals(EditPhase.IDLE, replaced.phase)
        assertTrue(replaced.notice.contains("接管"))
    }
    @Test fun processingTargetWaitsForItsOwnResumedQueuedFrame() {
        val processing = session.copy(originallyProcessing = true, admitted = false)
        var state = EditMachine.begin(EditState(), processing).state
        state = EditMachine.held(state, processing).state
        state = EditMachine.probed(state, session.id, buildJsonObject { put("frozen", payload("held_by" to session.holdId)) }).state
        assertEquals(EditPhase.HOLDING, state.phase)
        assertEquals(EditPhase.HOLDING, EditMachine.target(state, turn(resumed = true, owner = "different")).state.phase)
        assertEquals(EditPhase.EDITING, EditMachine.target(state, turn(resumed = true)).state.phase)
    }
    @Test fun terminalBeforeHoldAckExitsAndLateAckOnlyReleasesThatExactHold() {
        val state = EditMachine.begin(EditState(), session).state
        val closed = EditMachine.target(state, turn(terminalBody = buildJsonObject { put("status", "failed"); put("cancelled", true) }))
        assertEquals(EditPhase.IDLE, closed.state.phase)
        val late = EditMachine.held(closed.state, session)
        assertEquals(EditPhase.IDLE, late.state.phase)
        assertEquals(session.holdId, (late.effects.single() as EditEffect.Release).session.holdId)
    }
    @Test fun disconnectBeforeAdmissionKeepsKnownRequestIdForExactCleanup() {
        val state = EditMachine.begin(EditState(), session).state
        val lost = EditMachine.disconnected(state)
        assertEquals(EditPhase.IDLE, lost.state.phase)
        assertEquals(session.holdId, (lost.effects.single() as EditEffect.Release).session.holdId)
        assertTrue(lost.state.notice.contains("改动没有发出"))
    }
    @Test fun nativeEchoNeverAdvancesExternalCommandAndDraftIsIndependent() {
        var state = editing()
        val command = state.session!!.command
        state = EditMachine.input(state, "/status").state
        assertSame(command, state.session!!.command)
        state = EditMachine.input(state, "external", true, 2, 2).state
        assertEquals(command.version + 1, state.session!!.command.version)
        assertEquals(2, state.session!!.command.selectionStart)
        assertEquals("old", state.session!!.oldText)
    }
    @Test fun acceptanceBeforeLocalAcceptReturnCannotReopenOrDoubleRelease() {
        val sending = EditMachine.submit(EditMachine.input(editing(), "new").state, "replacement")
        assertEquals(EditPhase.SUBMITTING, sending.state.phase)
        val accepted = EditMachine.accepted(sending.state, "replacement")
        assertEquals(EditPhase.IDLE, accepted.state.phase)
        assertEquals(session.holdId, (accepted.effects.single() as EditEffect.Release).session.holdId)
        // A journal callback that reports an error after admission is stale.
        assertEquals(accepted.state, EditMachine.failed(accepted.state, session.id, "late", true).state)
        assertTrue(EditMachine.accepted(accepted.state, "replacement").effects.isEmpty())
    }
    @Test fun originalTargetReplacedByIsAcceptanceButDifferentReplacementExits() {
        val sending = EditMachine.submit(editing(), "replacement").state
        val accepted = EditMachine.target(sending, turn(terminalBody = buildJsonObject { put("status", "completed"); put("replaced_by", "replacement") }))
        assertEquals("编辑已提交", accepted.state.notice)
        assertEquals(EditPhase.IDLE, accepted.state.phase)
        val other = EditMachine.target(sending, turn(terminalBody = buildJsonObject { put("status", "completed"); put("replaced_by", "other") }))
        assertEquals("已退出编辑：这条请求已结束", other.state.notice)
    }
    @Test fun disconnectBeforeAcceptanceExitsWithUncertainOutcomeAndLateAcceptanceIsHarmless() {
        val sending = EditMachine.submit(editing(), "replacement").state
        val lost = EditMachine.disconnected(sending)
        assertEquals(EditPhase.IDLE, lost.state.phase)
        assertTrue(lost.state.notice.contains("修改结果待确认"))
        assertEquals(session.holdId, (lost.effects.single() as EditEffect.Release).session.holdId)
        assertTrue(EditMachine.accepted(lost.state, "replacement").effects.isEmpty())
    }
    @Test fun definiteReplaceRejectionReturnsToEditingButUnknownErrorExits() {
        val sending = EditMachine.submit(editing(), "replacement").state
        val rejected = EditMachine.failed(sending, session.id, "capacity", true).state
        assertEquals(EditPhase.EDITING, rejected.phase)
        assertEquals("capacity", rejected.error)
        assertEquals("", rejected.session!!.replacementId)
        assertEquals(EditPhase.IDLE, EditMachine.failed(sending, session.id, "lost", false).state.phase)
    }
    @Test fun secondEditorAndRepeatedSubmitCannotDuplicateWrites() {
        val editing = editing()
        assertTrue(EditMachine.begin(editing, session.copy(id = "second")).effects.isEmpty())
        val submitted = EditMachine.submit(editing, "replacement").state
        assertTrue(EditMachine.submit(submitted, "duplicate").effects.isEmpty())
        assertEquals("replacement", submitted.session!!.replacementId)
    }
}

package dev.atoll.app.compose

import dev.atoll.app.core.*
import dev.atoll.app.model.*
import dev.atoll.app.ui.screens.TurnKind
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

internal fun resendRequest(type: String = "agent.ask", id: String = "original", body: JsonObject = buildJsonObject { put("text", "/status") }) = Envelope(buildJsonObject {
    put("id", id); put("channel_id", "c0"); put("kind", "request"); put("type", type)
    put("sender", buildJsonObject { put("id", HUMAN.id); put("kind", "human") })
    put("audience", buildJsonArray { add(AGENT.id) }); put("visibility", "private")
    put("payload", buildJsonObject { put("body", body) })
})
internal fun failedResendTurn(request: Envelope = resendRequest(), extra: JsonObject = JsonObject(emptyMap())) = Turn(request, 1, terminal = Envelope(buildJsonObject {
    put("id", "terminal"); put("kind", "response"); put("type", request.type)
    put("payload", buildJsonObject { put("body", JsonObject(buildJsonObject { put("status", "failed"); put("error_code", "receiver_unavailable") } + extra)) })
}))

class TurnStatePolicyTest {
    @Test fun callerCancellationIsACompactStateOnTheRequestNotAFailure() {
        // The ledger shape the node writes when the sender cancels a waiting request.
        val turn = failedResendTurn(extra = buildJsonObject { put("cancelled", true); put("detail", "cancelled by caller"); put("error_code", "unanswered_timeout") })
        val state = TurnStatePolicy.presentation(turn)
        assertEquals(TurnKind.Cancelled, state.kind); assertTrue(state.compact)
        assertEquals(TurnKind.Failed, TurnStatePolicy.presentation(failedResendTurn()).kind)
    }
    @Test fun anEditShowsItsNewWordingInsteadOfControlJson() {
        val edit = resendRequest("agent.replace", "edit", buildJsonObject { put("target", "original"); put("old_text", "before"); put("new_text", "changed") })
        assertEquals("changed", dev.atoll.app.app.messageText(edit))
    }
    @Test fun mergedAndPreemptedAreCompactAndCannotBeResent() {
        for ((field, kind) in listOf("merged_into" to TurnKind.Merged, "preempted_by" to TurnKind.Preempted)) {
            val turn = failedResendTurn(extra = buildJsonObject { put(field, "next") })
            val state = TurnStatePolicy.presentation(turn)
            assertEquals(kind, state.kind); assertEquals("next", state.targetId); assertTrue(state.compact)
            assertFalse(TurnStatePolicy.canResend(turn, HUMAN.id))
        }
    }
    @Test fun detachedHistoryAndCompactClosureDoNotInventLostOrFailedRecovery() {
        val pending = Turn(resendRequest(), 1)
        assertEquals(TurnKind.Lost, TurnStatePolicy.presentation(pending, "restart").kind)
        assertEquals(TurnKind.Normal, TurnStatePolicy.presentation(pending, "restart", newerUnloaded = true).kind)
        assertFalse(TurnStatePolicy.canResend(pending, HUMAN.id, "expired", newerUnloaded = true))
        assertEquals(TurnKind.Normal, TurnStatePolicy.presentation(failedResendTurn().copy(terminalClosureOnly = true)).kind)
        assertFalse(TurnStatePolicy.canResend(failedResendTurn().copy(terminalClosureOnly = true), HUMAN.id))
    }
    @Test fun editNeedsLedgerAcceptanceAndRemainsEditedAfterProviderFailure() {
        val edit = failedResendTurn(resendRequest("agent.replace", "edit", buildJsonObject { put("target", "original"); put("new_text", "changed") }))
        assertFalse(TurnStatePolicy.presentation(edit).edited)
        val progress = Row("c0", 3, Envelope(buildJsonObject { put("kind", "response"); put("payload", buildJsonObject { put("body", buildJsonObject { put("status", "queued") }) }) }))
        assertTrue(TurnStatePolicy.presentation(edit.copy(provisional = listOf(progress))).edited)
        val original = failedResendTurn(extra = buildJsonObject { put("replaced_by", "edit") })
        val source = ReplicaSnapshot("c0", 1, 1, 3, emptyList(), listOf(TimelineEntry("turn", 1, original)), emptyList(), emptyMap())
        assertTrue(TurnStatePolicy.presentation(edit, source = source).edited)
    }
    @Test fun onlyOwnedFailedOrLostConversationsMayBeResent() {
        assertTrue(TurnStatePolicy.canResend(failedResendTurn(), HUMAN.id))
        assertFalse(TurnStatePolicy.canResend(failedResendTurn(), "human:other"))
        assertFalse(TurnStatePolicy.canResend(failedResendTurn(resendRequest("system.member.delete")), HUMAN.id))
        assertFalse(TurnStatePolicy.canResend(failedResendTurn(extra = buildJsonObject { put("reason", "interrupted") }), HUMAN.id))
        assertTrue(TurnStatePolicy.canResend(Turn(resendRequest(), 1), HUMAN.id, "expired"))
    }
    @Test fun replacementResendsNewTextAndResourcesWithoutOldLeaseOrCommandParsing() {
        val files = buildJsonArray { add(buildJsonObject { put("resource_id", "file"); put("name", "a.txt") }) }
        val request = resendRequest("agent.replace", body = buildJsonObject {
            put("target", "old"); put("new_text", "/status"); put("old_text", "before"); put("expected_hold_id", "lease"); put("attachments", files)
        })
        val frame = TurnStatePolicy.resendFrame(request, "new-intent", 1000)
        assertEquals("new-intent", frame.text("id")); assertEquals("agent.ask", frame.text("msg_type"))
        assertEquals("private", frame.text("visibility")); assertEquals(request.json["audience"], frame["audience"])
        assertEquals(buildJsonObject { put("text", "/status"); put("attachments", files) }, frame["payload"])
        assertEquals(1000L + 24 * 60 * 60_000, frame.number("expires_at_ms"))
    }
}

package dev.atoll.app.compose

import dev.atoll.app.core.*
import dev.atoll.app.model.Turn
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TimelineActionPolicyTest {
    private fun envelope(id: String, sender: String, kind: String = "request", status: String = "") = Envelope(buildJsonObject {
        put("id", id); put("type", "agent.ask"); put("kind", kind)
        put("sender", buildJsonObject { put("id", sender) }); put("audience", buildJsonArray { add("agent:a") })
        put("payload", buildJsonObject { put("body", buildJsonObject { put("text", "body"); put("status", status) }) })
    })
    private fun turn() = Turn(envelope("ask", "self"), 1, listOf(Row("c", 2, Envelope(buildJsonObject {
        put("payload", buildJsonObject { put("body", buildJsonObject { put("status", "processing")
            put("controls", buildJsonArray { add(buildJsonObject { put("word", "agent.replace") }) }) }) })
    }))))
    private fun actions(envelope: Envelope, turn: Turn? = null, text: String = "body", writable: Boolean = true,
        online: Boolean = true, detached: Boolean = false, safe: Set<String> = setOf("agent:a")) =
        TimelineActionPolicy.actions(envelope, turn, text, "self", listOf(ComposerActor("agent:a", "A", "agent"),
            ComposerActor("human:b", "B", "human")), writable, online, detached, safe)

    @Test fun replyRequiresCurrentHumanOrAgentAndExcludesSelfAndStreamFragments() {
        assertNull(actions(envelope("x", "self")).reply)
        assertNull(actions(envelope("x", "agent:departed")).reply)
        assertEquals("human:b", actions(envelope("x", "human:b")).reply!!.senderId)
        val terminal = envelope("answer", "agent:a", "response", "completed")
        assertEquals("answer", actions(terminal, turn().copy(terminal = terminal)).reply!!.sourceId)
        assertNull(actions(envelope("partial", "agent:a", "response", "processing"), turn()).reply)
        assertNull(actions(terminal, writable = false).reply)
    }
    @Test fun replyExcerptUsesWebWhitespaceAnd96CharacterLimit() {
        assertEquals("a b c", actions(envelope("x", "human:b"), text = " a\nb\t c ").reply!!.excerpt)
        assertEquals("x".repeat(95) + "…", actions(envelope("x", "human:b"), text = "x".repeat(97)).reply!!.excerpt)
        assertEquals("x".repeat(96), actions(envelope("x", "human:b"), text = "x".repeat(96)).reply!!.excerpt)
    }
    @Test fun editIsOwnedOpenAskWithDeclaredReplaceAndProbedLease() {
        val turn = turn()
        assertEquals("ask", actions(turn.request, turn).editTargetId)
        assertEquals("", actions(turn.request, turn, safe = emptySet()).editTargetId)
        assertEquals("", actions(turn.request, turn, online = false).editTargetId)
        assertEquals("", actions(turn.request, turn, detached = true).editTargetId)
        assertEquals("", actions(turn.request, turn, writable = false).editTargetId)
        assertEquals("", actions(turn.request, turn.copy(provisional = emptyList())).editTargetId)
        assertEquals("", actions(turn.request, turn.copy(terminal = envelope("done", "agent:a", "response", "completed"))).editTargetId)
        assertEquals("", actions(envelope("ask", "human:b"), turn).editTargetId)
    }
}

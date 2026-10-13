package dev.atoll.app.app

import dev.atoll.app.compose.*
import dev.atoll.app.core.*
import dev.atoll.app.model.Turn
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProcessActionsTest {
    @Test fun responseOpenedPanelUsesTheRequestForStopEditAndTaskSource() {
        val request = Envelope(buildJsonObject {
            put("id", "request"); put("channel_id", "channel"); put("kind", "request"); put("type", "agent.ask")
            put("sender", buildJsonObject { put("id", "human:self"); put("kind", "human") })
            put("audience", buildJsonArray { add("agent:a") })
            put("payload", buildJsonObject { put("body", buildJsonObject { put("text", "work") }) })
        })
        val progress = Envelope(buildJsonObject {
            put("id", "progress"); put("kind", "response"); put("type", "agent.ask"); put("parent_id", "request")
            put("sender", buildJsonObject { put("id", "agent:a"); put("kind", "agent") })
            put("payload", buildJsonObject { put("body", buildJsonObject {
                put("status", "processing")
                put("controls", buildJsonArray {
                    add(buildJsonObject { put("word", "agent.replace") })
                    add(buildJsonObject { put("word", "agent.interrupt") })
                })
            }) })
        })
        val target = processActionTarget(TimelineMessageTarget(progress, Turn(request, 17, listOf(Row("channel", 18, progress))), 18))
        val message = processActionMessage(target, "human:self", emptyList())
        val actions = TimelineActionPolicy.actions(target.envelope, target.turn, message.text, "human:self",
            listOf(ComposerActor("agent:a", "A", "agent")), true, true, false, setOf("agent:a"))
        assertEquals("request", target.envelope.id)
        assertEquals(17L, target.seq)
        assertEquals("request", actions.editTargetId)
        assertEquals("request", actions.stopTargetId)
        assertTrue(message.self)
        assertEquals("work", message.text)
    }
}

package dev.atoll.app.waiting

import dev.atoll.app.core.*
import dev.atoll.app.model.Turn
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class WaitingRulesTest {
    internal fun manifest(lease: Boolean = true) = buildJsonObject { put("words", buildJsonObject {
        for (word in listOf("agent.replace", "agent.unhold")) put(word, buildJsonObject { put("input_schema", buildJsonObject { put("properties", if (lease) buildJsonObject { put("expected_hold_id", buildJsonObject { put("type", "string") }) } else Envelope.EMPTY) }) })
    }) }
    internal fun turn(sender: String = "self", status: String = "queued", controls: JsonArray = buildJsonArray {
        for (word in listOf("agent.steer", "agent.replace", "agent.dismiss", "agent.custom")) add(buildJsonObject { put("word", word) })
    }): Turn {
        val request = Envelope(buildJsonObject { put("id", "target"); put("audience", buildJsonArray { add("agent:a") }); put("sender", buildJsonObject { put("id", sender) }); put("payload", buildJsonObject { put("body", payload("text" to "old")) }) })
        val progress = Envelope(buildJsonObject { put("payload", buildJsonObject { put("body", buildJsonObject { put("status", status); put("controls", controls) }) }) })
        return Turn(request, 1, listOf(Row("c", 2, progress)))
    }
    @Test fun ownQueuedCancellationUsesWireAndOtherSendersUseDeclaredDismiss() {
        val own = WaitingRules.actions(turn(), "self", true, null, manifest())
        assertTrue(own.canCancel); assertNull(own.dismiss)
        assertTrue(own.canEdit); assertEquals("target", own.insert!!.payload.text("target"))
        val other = WaitingRules.actions(turn("another"), "self", true, null, manifest())
        assertTrue(other.canCancel); assertEquals("agent.dismiss", other.dismiss!!.word)
        assertFalse(WaitingRules.actions(turn(status = "processing"), "self", true, null, manifest()).canCancel)
    }
    @Test fun departedActorKeepsRowButDisablesTargetActionsAndOwnCancelStillWorks() {
        val actions = WaitingRules.actions(turn(), "self", true, emptySet(), manifest())
        assertTrue(actions.departed); assertTrue(actions.canCancel)
        assertNull(actions.insert); assertFalse(actions.canEdit); assertTrue(actions.extras.isEmpty())
        assertFalse(WaitingRules.actions(turn("another"), "self", true, emptySet(), manifest()).canCancel)
    }
    @Test fun authoredPayloadIsWholeAndBareOrEmptyPayloadUsesRowTarget() {
        val rows = buildJsonArray {
            add(buildJsonObject { put("word", "agent.custom"); put("label", "特殊操作"); put("payload", payload("mode" to "custom")) })
            add(buildJsonObject { put("word", "agent.steer"); put("payload", Envelope.EMPTY) })
        }
        val actions = WaitingRules.actions(turn(controls = rows), "self", true, null, manifest())
        assertEquals(payload("mode" to "custom"), actions.extras.single().payload)
        assertEquals("特殊操作", actions.extras.single().label)
        assertEquals(payload("target" to "target"), actions.insert!!.payload)
    }
    @Test fun leaseRequiredOnBothWordsIncludingStringSchemaAndReadonlyHasNoControls() {
        assertFalse(WaitingRules.leaseSupported(null))
        assertFalse(WaitingRules.leaseSupported(manifest(false)))
        val stringSchema = buildJsonObject { put("words", buildJsonObject {
            for (word in listOf("agent.replace", "agent.unhold")) put(word, buildJsonObject { put("input_schema", """{"properties":{"expected_hold_id":{"type":"string"}}}""") })
        }) }
        assertTrue(WaitingRules.leaseSupported(stringSchema))
        val readOnly = WaitingRules.actions(turn(), "self", false, null, manifest())
        assertFalse(readOnly.canCancel); assertFalse(readOnly.canEdit); assertNull(readOnly.insert); assertTrue(readOnly.extras.isEmpty())
    }
}

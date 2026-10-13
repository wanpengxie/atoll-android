package dev.atoll.app.compose

import org.junit.Assert.*
import org.junit.Test

class ComposerDeliveryTest {
    private val roster = listOf(AGENT, OTHER, HUMAN)
    @Test fun deliveryPriorityMatchesWebAndNeverRedirectsLostExplicitTargets() {
        var draft = ComposerDraft(recipients = listOf(OTHER), reply = ReplyTarget("answer", HUMAN.id, HUMAN.name), manualAgentId = AGENT.id)
        assertEquals("reply", ComposerDelivery.resolve(draft, roster, setOf(AGENT.id), OTHER.id).source)
        assertEquals(listOf(HUMAN), ComposerDelivery.resolve(draft, roster).recipients)
        assertFalse(ComposerDelivery.resolve(draft, roster - HUMAN).valid)
        draft = draft.copy(reply = null)
        assertEquals("mention", ComposerDelivery.resolve(draft, roster, setOf(AGENT.id), AGENT.id).source)
        assertFalse(ComposerDelivery.resolve(draft, roster - OTHER).valid)
        draft = draft.copy(recipients = emptyList())
        assertEquals(OTHER.id, ComposerDelivery.resolve(draft, roster, setOf(OTHER.id), AGENT.id).recipients.single().id)
        assertEquals("manual", ComposerDelivery.resolve(draft, roster, emptySet(), OTHER.id).source)
        assertEquals("recent", ComposerDelivery.resolve(draft.copy(manualAgentId = "gone"), roster, emptySet(), OTHER.id).source)
        assertEquals("only", ComposerDelivery.resolve(ComposerDraft(), listOf(HUMAN, AGENT)).source)
        assertFalse(ComposerDelivery.resolve(ComposerDraft(), roster).valid)
    }
    @Test fun typedBatchOnePerRecipientWithReplyParentAndSlashEscape() {
        val draft = ComposerDraft(text = "//literal", attachments = listOf(ComposerAttachment("c0", "daemon://local-device/c0/a.txt", "a.txt")), reply = ReplyTarget("answer", HUMAN.id, HUMAN.name))
        var id = 0
        val frames = ComposerDelivery.messageFrames("c0", draft, Delivery(listOf(AGENT, HUMAN), "mention"), { "id-${id++}" }, 100)
        assertEquals(listOf("agent.ask", "human.message"), frames.map { it.text("msg_type") })
        assertEquals(2, frames.map { it.text("id") }.distinct().size)
        frames.forEach { assertEquals("answer", it.text("parent_id")); assertEquals("/literal", it.getValue("payload").let { value -> value as kotlinx.serialization.json.JsonObject }.text("text")); assertEquals(100L, it.number("expires_at_ms")) }
    }
    @Test fun mentionMenuIsLimitedAndExcludesSelfAndTools() {
        val roster = (0..20).map { ComposerActor("agent:$it", "agent $it", "agent") } + HUMAN + ComposerActor("tool:t", "tool", "tool")
        assertEquals(8, ComposerDelivery.mention("hello @", 7, roster, HUMAN.id)!!.candidates.size)
        assertEquals(listOf(HUMAN), ComposerDelivery.mention("@human", 6, roster, "me")!!.candidates)
        assertNull(ComposerDelivery.mention("email@example.com after", 23, roster, "me"))
    }
    @Test fun enterImeNewlineMenusAndRestingState() {
        assertEquals(EditorAction.NONE, ComposerEditor.key("Enter", false, true, false, false, false, 3))
        assertEquals(EditorAction.NEWLINE, ComposerEditor.key("Enter", true, false, false, false, false, 3))
        assertEquals(EditorAction.PICK, ComposerEditor.key("Enter", false, false, false, false, false, 3))
        assertEquals(EditorAction.SEND, ComposerEditor.key("Enter", false, false, false, false, false, 0))
        assertEquals(EditorAction.REMOVE_MENTION, ComposerEditor.key("Backspace", false, false, true, true, false, 0))
        assertEquals(EditorAction.CLEAR_REPLY, ComposerEditor.key("Escape", false, false, false, false, true, 0))
        assertTrue(ComposerEditor.rests(ComposerDraft(), false))
        assertFalse(ComposerEditor.rests(ComposerDraft(text = "draft"), false))
        assertFalse(ComposerEditor.rests(ComposerDraft(), true))
    }
}

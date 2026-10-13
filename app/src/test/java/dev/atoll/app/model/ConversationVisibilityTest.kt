package dev.atoll.app.model

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ConversationVisibilityTest {
    @Test fun controlOnlySteerAndProseAreDecidedPerBody() {
        assertTrue(ConversationVisibility.isControlOnlyBody("agent.steer", body("target" to "r1")))
        assertTrue(ConversationVisibility.isControlOnlyBody("agent.steer", body("all" to true)))
        assertFalse(ConversationVisibility.isControlOnlyBody("agent.steer", body("text" to "hello")))
        assertFalse(ConversationVisibility.isControlOnlyBody("agent.steer", body("files" to JsonArray(listOf(JsonPrimitive("file"))))))
        for (type in ConversationVisibility.hiddenTurnTypes) assertTrue(ConversationVisibility.isControlOnlyBody(type, body("text" to "hello")))
    }
    @Test fun terminalBookkeepingDoesNotBecomeReadableAnswer() {
        val terminal = response(2, "r", "completed", extra = body("usage" to body("tokens" to 10), "merged_into" to "other")).envelope
        assertFalse(ConversationVisibility.hasReadableTerminalContent(terminal))
        assertTrue(ConversationVisibility.hasReadableTerminalContent(response(2, "r", "failed", extra = body("detail" to "failed process")).envelope))
        assertTrue(ConversationVisibility.hasReadableTerminalContent(response(2, "r", "completed", extra = body("result" to body("value" to 1))).envelope))
        assertTrue(ConversationVisibility.hasReadableTerminalContent(response(2, "r", "completed", extra = body("content" to JsonArray(listOf(body("type" to "resource_link", "uri" to "daemon://device/channel/a"))))).envelope))
    }
    @Test fun weakNoticeAndNotificationVisibilityAreSeparate() {
        assertTrue(ConversationVisibility.weakNoticeEvent(note(1).envelope))
        assertFalse(ConversationVisibility.weakNoticeEvent(row(1, "task", kind = "event", type = "agent.task").envelope))
        assertFalse(ConversationVisibility.weakNoticeEvent(row(1, "system", kind = "event", visibility = "system").envelope))
        assertTrue(ConversationVisibility.personConversationEventVisible(note(1).envelope))
        for (type in listOf("ui.open", "terminal.command", "agent.hold_expired")) assertFalse(ConversationVisibility.personConversationEventVisible(row(1, type, kind = "event", type = type).envelope))
        assertFalse(ConversationVisibility.personConversationEventVisible(row(1, "transient", kind = "event", body = body("text" to "pulse", "transient" to true)).envelope))
    }
    @Test fun queuedMessageIsNotTimelineNotificationBeforeProcessing() {
        val replica = ChannelReplica(CHANNEL)
        replica.add(row(1, "work"), response(2, "work", "queued"))
        assertFalse(ConversationVisibility.timelineTurnVisible(replica.turn("work")))
        assertFalse(ConversationVisibility.personConversationRequestVisible(replica.turn("work")))
        assertTrue(ConversationVisibility.timelineTurnVisible(replica.turn("work"), "work"))
        replica.add(response(3, "work", "processing"))
        assertTrue(ConversationVisibility.personConversationTurnVisible(replica.turn("work")))
    }
}

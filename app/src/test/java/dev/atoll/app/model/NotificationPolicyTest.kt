package dev.atoll.app.model

import dev.atoll.app.core.*
import org.junit.Assert.*
import org.junit.Test

class NotificationPolicyTest {
    @Test fun anotherPersonsRequestAndAnswerCountOncePerRoot() {
        val replica = ChannelReplica(CHANNEL, SELF)
        replica.add(row(1, "conversation", sender = "human:other:1", audience = listOf(SELF), type = "human.ask"),
            response(2, "conversation", "completed", extra = body("text" to "answer")))
        assertEquals(1, NotificationPolicy.unread(replica.snapshot(), SELF, 0).count)
        assertEquals(0, NotificationPolicy.unread(replica.snapshot(), SELF, 2).count)
    }
    @Test fun ourAgentRequestIsUnreadOnlyWhenSomeoneAnswers() {
        val replica = ChannelReplica(CHANNEL, SELF)
        replica.add(row(1, "ask"), response(2, "ask", "queued"), response(3, "ask", "processing"))
        assertEquals(0, NotificationPolicy.unread(replica.snapshot(), SELF, 0).count)
        replica.add(response(4, "ask", "completed", extra = body("text" to "done")))
        assertEquals(1, NotificationPolicy.unread(replica.snapshot(), SELF, 0).count)
    }
    @Test fun responseFirstContextStaysUnknownUntilItsRequestArrives() {
        val replica = ChannelReplica(CHANNEL, SELF)
        replica.add(response(2, "missing", "completed", extra = body("text" to "answer")))
        assertTrue(NotificationPolicy.unread(replica.snapshot(), SELF, 0).unknown)
        replica.add(row(1, "missing"))
        assertEquals(UnreadCount(1), NotificationPolicy.unread(replica.snapshot(), SELF, 0))
    }
    @Test fun hiddenSystemUiProgressAndOrdinaryEventsAreNeverUnread() {
        val replica = ChannelReplica(CHANNEL, SELF)
        replica.add(row(1, "options", type = "agent.options"), response(2, "options", "completed", extra = body("text" to "config")),
            row(3, "ui", type = "ui.form", sender = AGENT, audience = listOf(SELF)),
            row(4, "notice", kind = "event", type = "human.message", sender = AGENT, audience = listOf(SELF)),
            row(5, "system", kind = "event", type = "system.build.started", sender = "system", audience = listOf(SELF), visibility = "system"))
        assertEquals(0, NotificationPolicy.unread(replica.snapshot(), SELF, 0).count)
        assertFalse(NotificationPolicy.unread(replica.snapshot(), SELF, 0).unknown)
    }
    @Test fun providerReportCountsAsAnAnswerAndAnUnfinishedScanIsPending() {
        val replica = ChannelReplica(CHANNEL, SELF)
        replica.add(row(1, "report", kind = "event", type = "agent.provider.run", sender = AGENT, audience = listOf(SELF)))
        assertEquals(UnreadCount(1, pending = true), NotificationPolicy.unread(replica.snapshot(), SELF, 0, complete = false))
    }
    @Test fun nestedToolRepliesAndConflictingTerminalsDoNotAddRoots() {
        val replica = ChannelReplica(CHANNEL, SELF)
        replica.add(row(1, "root"), row(2, "tool", type = "tool.run", parent = "root", sender = AGENT),
            response(3, "tool", "completed", extra = body("text" to "tool output")),
            response(4, "root", "completed", extra = body("text" to "first")),
            response(5, "root", "completed", extra = body("text" to "conflict")))
        assertEquals("nested_tool_lifecycle", NotificationPolicy.disposition(replica.snapshot(), replica.snapshot().rows[2].envelope))
        assertEquals("terminal_conflict", NotificationPolicy.disposition(replica.snapshot(), replica.snapshot().rows[4].envelope))
        assertEquals(1, NotificationPolicy.unread(replica.snapshot(), SELF, 0).count)
    }
    @Test fun onlyExactActorIdentityIsOurs() {
        val replica = ChannelReplica(CHANNEL, SELF)
        replica.add(row(1, "other-incarnation", type = "human.ask", sender = "human:root:2", audience = listOf(SELF)))
        assertEquals(1, NotificationPolicy.unread(replica.snapshot(), SELF, 0).count)
    }
}

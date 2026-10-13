package dev.atoll.app.app

import dev.atoll.app.core.*
import dev.atoll.app.model.*
import dev.atoll.app.ui.screens.ChannelAccess
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ComposerBridgeTest {
    @Test fun scopeIdentityBecomesUnknownForAnObserverEvenIfAnOldSelfIdIsStillPresent() {
        val oldSelf = "human:self"
        assertTrue(channelIdentityReady(true, ChannelAccess.MemberActive, oldSelf))
        assertTrue(channelIdentityReady(true, ChannelAccess.MemberStale, oldSelf))
        assertFalse(channelIdentityReady(true, ChannelAccess.ObserverActive, oldSelf))
        assertFalse(channelIdentityReady(true, ChannelAccess.ObserverStale, oldSelf))
        assertFalse(channelIdentityReady(false, ChannelAccess.MemberActive, oldSelf))
        assertFalse(channelIdentityReady(true, ChannelAccess.MemberActive, ""))
    }
    @Test fun membershipIsRequiredEvenWhenObserverContentIsAvailable() {
        assertFalse(composerWritable(ChannelAccess.ObserverActive, ""))
        assertFalse(composerWritable(ChannelAccess.ObserverActive, "human:self"))
        assertFalse(composerWritable(ChannelAccess.MemberActive, ""))
        assertTrue(composerWritable(ChannelAccess.MemberStale, "human:self"))
        assertFalse(composerWritable(ChannelAccess.Denied, "human:self"))
    }
    @Test fun landedRequestAndTerminalIdsComeFromTheLedgerAndLatestUsageWins() {
        val replica = ChannelReplica("c", "human:self")
        replica.apply(RowBatch("c", listOf(row(1, "q1", "request"), row(2, "a1", "response", "q1", 100),
            row(3, "q2", "request"), row(4, "a2", "response", "q2", 200)), Origin.CACHE))
        val facts = composerTimelineFacts(replica.timeline(TimelineOptions(selfId = "human:self", scope = ConversationScope.ALL)))
        assertTrue(facts.landed.containsAll(setOf("q1", "a1", "q2", "a2")))
        assertEquals(1, facts.usage.size)
        assertEquals(4L, facts.usage.single().seq)
        assertEquals(JsonPrimitive(200), facts.usage.single().value["output"])
        assertNull(composerUsage(row(1, "q", "request").envelope, 1))
    }
    private fun row(seq: Long, id: String, kind: String, parent: String = "", tokens: Int = 0) = Row("c", seq, Envelope(buildJsonObject {
        put("id", id); put("kind", kind); put("type", "agent.ask"); put("parent_id", parent)
        put("sender", buildJsonObject { put("id", if (kind == "request") "human:self" else "agent:a"); put("kind", if (kind == "request") "human" else "agent") })
        put("audience", buildJsonArray { add("human:self"); add("agent:a") })
        put("payload", buildJsonObject { put("body", buildJsonObject {
            put("text", "test")
            if (kind == "response") { put("status", "completed"); put("usage", buildJsonObject { put("output", tokens) }) }
        }) })
    }))
}

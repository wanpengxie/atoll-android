package dev.atoll.app.model

import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

/** Ports timeline-projection and conversation-presentation behaviour, excluding DOM admission. */
class ConversationPresentationTest {
    private fun options(scope: ConversationScope = ConversationScope.MINE, now: Long = 1000) = TimelineOptions(scope, SELF, now = now, zoneId = ZoneId.of("UTC"))
    @Test fun mineHidesUiRootAndChildrenButAllPreservesLedger() {
        val replica = ChannelReplica(CHANNEL)
        replica.add(row(1, "root", type = "human.ask"), row(2, "ui-child", type = "ui.open", parent = "root"), row(3, "ui-root", type = "ui.navigate"))
        val mine = ConversationPresentation.selectTimelineItems(replica.snapshot(), options())
        assertEquals(listOf("root"), mine.map { it.id })
        assertTrue(mine.first().thread.isEmpty())
        val all = ConversationPresentation.selectTimelineItems(replica.snapshot(), options(ConversationScope.ALL))
        assertEquals(listOf("root", "ui-root"), all.map { it.id })
        assertEquals("ui-child", all.first().thread.single().id)
    }
    @Test fun waitingReplacementRemovesTargetAndDoesNotDuplicateTimelineMessage() {
        val replica = ChannelReplica(CHANNEL)
        replica.add(row(1, "target"), response(2, "target", "queued"), row(3, "edit", type = "agent.replace", body = body("new_text" to "edited")), response(4, "edit", "queued"), response(5, "target", "completed", extra = body("replaced_by" to "edit")))
        val snapshot = replica.timeline(options())
        assertTrue(snapshot.items.isEmpty())
        assertEquals(listOf("edit"), snapshot.waitingTurns.map { it.requestId })
        replica.add(response(6, "edit", "processing"))
        assertEquals(listOf("edit"), replica.timeline(options()).items.map { it.entry.id })
    }
    @Test fun idleReceiverLocalPlacementDoesNotJumpIntoWaiting() {
        val replica = ChannelReplica(CHANNEL)
        replica.add(row(1, "work"), response(2, "work", "queued"))
        val placed = options().copy(timelinePlaced = setOf("work"))
        assertEquals(listOf("work"), replica.timeline(placed).items.map { it.entry.id })
        assertTrue(replica.timeline(placed).waitingTurns.isEmpty())
    }
    @Test fun lostAndExpiredRequestsLeaveWaitingAndRemainInTimeline() {
        val replica = ChannelReplica(CHANNEL)
        replica.add(row(1, "expired", expires = 100), response(2, "expired", "queued"))
        val snapshot = replica.timeline(options(now = 100))
        assertEquals(listOf("expired"), snapshot.items.map { it.entry.id })
        assertTrue(snapshot.waitingTurns.isEmpty())
    }
    @Test fun completedSelectNewOnlyAndControlSteerHasNoMineRow() {
        val replica = ChannelReplica(CHANNEL)
        replica.add(row(1, "select", type = "agent.select"), row(2, "new", type = "agent.new"), row(3, "control", type = "agent.steer", body = body("target" to "select")), row(4, "prose", type = "agent.steer", body = body("text" to "continue")))
        assertEquals(listOf("prose"), replica.timeline(options()).items.map { it.entry.id })
        replica.add(response(5, "select", "completed"), response(6, "new", "failed"))
        assertEquals(listOf("select", "prose"), replica.timeline(options()).items.map { it.entry.id })
    }
    @Test fun mineIsRelationAndIncludesMemberConversationsButNotOtherHumans() {
        val replica = ChannelReplica(CHANNEL)
        replica.add(row(1, "mine", type = "human.ask"), row(2, "call", parent = "mine", sender = AGENT, audience = listOf("tool:x:1"), type = "tool.run"), response(3, "call", "completed"), row(4, "other-person", type = "human.ask", sender = "human:other:1"), row(5, "member-root", sender = AGENT, audience = listOf("agent:other:1")), response(6, "member-root", "processing"), row(7, "operation", type = "system.member.list", sender = AGENT, audience = listOf("system:channel")))
        assertEquals(listOf("mine", "member-root"), replica.timeline(options()).items.map { it.entry }.filter { it.kind != "hidden" }.map { it.id })
        val related = ConversationPresentation.conversationRelation(replica.snapshot(), SELF)
        assertTrue(related(replica.turn("call").terminal!!))
        assertFalse(related(replica.turn("other-person").request))
        assertFalse(related(replica.turn("operation").request))
    }
    @Test fun runsLeftOutByMineBecomeOneCountedMarkerOnlyBesideVisibleEntries() {
        val replica = ChannelReplica(CHANNEL)
        replica.add(row(1, "mine", type = "human.ask"), row(2, "a", type = "human.ask", sender = "human:other:1"), row(3, "b", type = "human.ask", sender = "human:other:1"),
            row(4, "mine-2", type = "human.ask"), row(5, "c", type = "human.ask", sender = "human:other:1"))
        assertEquals(listOf("mine", "hidden×2", "mine-2", "hidden×1"), replica.timeline(options()).items.map {
            if (it.entry.kind == "hidden") "hidden×${it.entry.hiddenCount}" else it.entry.id })
        assertEquals(listOf("mine", "a", "b", "mine-2", "c"), replica.timeline(options(ConversationScope.ALL)).items.map { it.entry.id })
        val onlyOthers = ChannelReplica(CHANNEL)
        onlyOthers.add(row(1, "a", type = "human.ask", sender = "human:other:1"))
        assertTrue(onlyOthers.timeline(options()).items.isEmpty())
    }
    @Test fun actorFilterAppliesToWholeThreadOnlyInMine() {
        val replica = ChannelReplica(CHANNEL)
        replica.add(row(1, "root", type = "human.ask"), row(2, "child", parent = "root", sender = AGENT, audience = listOf("tool:x:1"), type = "tool.run"), note(3))
        assertEquals(listOf("root"), replica.timeline(options().copy(actorFilter = setOf("tool:x:1"))).items.map { it.entry.id })
        assertEquals(2, replica.timeline(options(ConversationScope.ALL).copy(actorFilter = setOf("nobody"))).items.size)
    }
    @Test fun detachedProcessRowsWaitForParentButHumanApprovalsAndProviderProseSurvive() {
        val replica = ChannelReplica(CHANNEL)
        replica.add(row(2, "call", parent = "missing", sender = AGENT, type = "tool.run", audience = listOf("tool:x:1")), row(3, "approval", parent = "missing", sender = AGENT, type = "human.approve", audience = listOf(SELF)), row(4, "provider", parent = "missing", kind = "event", sender = AGENT, type = "agent.provider.run"), row(5, "human-prose", parent = "missing", type = "human.ask"))
        val items = replica.timeline(options(ConversationScope.ALL)).items
        assertEquals(listOf("approval", "provider", "human-prose"), items.map { it.entry.id })
        replica.add(row(1, "missing", type = "human.ask"))
        assertEquals("call", replica.snapshot().timeline.first().thread.first().id)
    }
    @Test fun transientTailAndNarrationAreCompact() {
        val replica = ChannelReplica(CHANNEL)
        replica.add(row(1, "narration", kind = "event", visibility = "system"), row(2, "pulse1", kind = "event", body = body("text" to "pulse", "transient" to true)), row(3, "pulse2", kind = "event", body = body("text" to "pulse", "transient" to true)))
        val items = replica.timeline(options().copy(showNarration = true)).items
        assertEquals(listOf("narration:1", "pulse2"), items.map { it.entry.id })
        assertEquals("compact", items.first().layoutClass)
        assertFalse(items.last().settled)
    }
    @Test fun continuationUsesPreviousAnswerAuthorAndNoticeBreaksIt() {
        val replica = ChannelReplica(CHANNEL)
        replica.add(row(1, "first", type = "human.ask", ts = 1000), response(2, "first", "completed"), row(3, "second", type = "human.ask", ts = 3000, sender = AGENT))
        // Human approval turns never create a continuation; normal agent message turns do.
        assertFalse(replica.timeline(options(ConversationScope.ALL)).items.last().continuation)
        val normal = ChannelReplica(CHANNEL)
        normal.add(row(1, "first"), response(2, "first", "completed"), row(3, "second", sender = AGENT), response(4, "second", "processing"))
        assertTrue(normal.timeline(options(ConversationScope.ALL)).items.last().continuation)
        normal.add(note(5), row(6, "third", sender = AGENT), response(7, "third", "processing"))
        assertFalse(normal.timeline(options(ConversationScope.ALL)).items.last().continuation)
    }
    @Test fun dayBoundariesAndStableKeysUseLedgerIdentity() {
        val replica = ChannelReplica(CHANNEL)
        replica.add(note(1).copy(envelope = row(1, "a", kind = "event", ts = 86400000).envelope), row(2, "b", kind = "event", ts = 172800000))
        val before = replica.timeline(options())
        assertEquals("1970-01-02", before.items.first().dayKey)
        assertEquals(172800000L, before.items.first().boundaryAfterTimestamp)
        replica.add(note(3))
        assertEquals(before.items.take(2).map { it.key }, replica.timeline(options()).items.take(2).map { it.key })
    }
    @Test fun landedAndClosureRequestsSuppressLocalEcho() {
        val replica = ChannelReplica(CHANNEL)
        replica.add(row(1, "work"), response(2, "work", "completed"))
        val echo = replica.snapshot().timeline.first().copy(local = true)
        for (seq in 3L..10L) replica.add(note(seq))
        replica.trim(4)
        assertTrue(replica.timeline(options().copy(localEchoes = listOf(echo), timelinePlaced = setOf("work"))).items.none { it.entry.id == "work" })
    }
}

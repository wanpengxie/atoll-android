package dev.atoll.app.model

import dev.atoll.app.core.*
import org.junit.Assert.*
import org.junit.Test

class TimelineCacheTest {
    private val options = TimelineOptions(selfId = SELF, now = 1)
    @Test fun sameProjectionAndOptionsReturnSameSnapshotDespiteClockAdvancing() {
        val replica = ChannelReplica(CHANNEL, SELF)
        replica.add(row(1, "work"), response(2, "work", "processing"))
        val first = replica.timeline(options)
        assertSame(first, replica.timeline(options.copy(now = 100)))
        assertSame(first, replica.timeline(options.copy(now = 200)))
        replica.add(row(3, "flat", canonical = false))
        assertSame(first, replica.timeline(options.copy(now = 300)))
        replica.add(response(4, "work", "processing"))
        assertNotSame(first, replica.timeline(options.copy(now = 400)))
        assertSame(first.items.first().entry.turn!!.request, replica.timeline(options.copy(now = 500)).items.first().entry.turn!!.request)
    }
    @Test fun immutableReplicaSnapshotIsCopiedOncePerRevisionAndNeverChangesAfterApply() {
        val replica = ChannelReplica(CHANNEL)
        replica.add(row(1, "work"))
        val first = replica.snapshot()
        assertSame(first, replica.snapshot())
        replica.add(response(2, "work", "processing"))
        val second = replica.snapshot()
        assertSame(second, replica.snapshot())
        assertNotSame(first, second)
        assertEquals(1, first.rows.size)
        assertTrue(first.timeline.single().turn!!.provisional.isEmpty())
        assertEquals(2, second.rows.size)
    }
    @Test fun expiryRecomputesOnlyAtDeadlineAndSupportsClockRewind() {
        val replica = ChannelReplica(CHANNEL, SELF)
        replica.add(row(1, "early", expires = 100), row(2, "later", expires = 200))
        val initial = replica.timeline(options)
        assertEquals(100L, replica.nextTimelineExpiryAt)
        assertSame(initial, replica.timeline(options.copy(now = 99)))
        val expired = replica.timeline(options.copy(now = 100))
        assertNotSame(initial, expired)
        assertEquals(listOf("early"), expired.items.map { it.entry.id })
        assertEquals(listOf("later"), expired.waitingTurns.map { it.requestId })
        assertEquals(200L, replica.nextTimelineExpiryAt)
        assertSame(expired, replica.timeline(options.copy(now = 199)))
        val both = replica.timeline(options.copy(now = 200))
        assertTrue(both.waitingTurns.isEmpty())
        assertNull(replica.nextTimelineExpiryAt)
        assertSame(both, replica.timeline(options.copy(now = 1000)))
        val rewound = replica.timeline(options.copy(now = 50))
        assertEquals(2, rewound.waitingTurns.size)
        assertEquals(100L, replica.nextTimelineExpiryAt)
    }
    @Test fun closingEarliestDeadlineAdvancesExpiryFrontier() {
        val replica = ChannelReplica(CHANNEL, SELF)
        replica.add(row(1, "early", expires = 100), row(2, "later", expires = 200))
        replica.timeline(options)
        replica.add(response(3, "early", "completed"))
        val completed = replica.timeline(options)
        assertEquals(200L, replica.nextTimelineExpiryAt)
        assertSame(completed, replica.timeline(options.copy(now = 100)))
    }
    @Test fun optionChangesInvalidateCacheIncludingEditingEchoesAndPlacement() {
        val replica = ChannelReplica(CHANNEL, SELF)
        replica.add(row(1, "waiting"), row(2, "ui", type = "ui.open"))
        val mine = replica.timeline(options)
        assertNotSame(mine, replica.timeline(options.copy(scope = ConversationScope.ALL)))
        val placed = replica.timeline(options.copy(timelinePlaced = setOf("waiting")))
        assertEquals(listOf("waiting"), placed.items.map { it.entry.id })
        assertNotSame(placed, replica.timeline(options.copy(actorFilter = setOf("agent:other:1"))))
        val replacementHidden = replica.timeline(options.copy(timelinePlaced = setOf("waiting"), editingReplacementId = "waiting"))
        assertTrue(replacementHidden.items.isEmpty())
        val echo = TimelineEntry("standalone", 0, envelope = note(4, "local").envelope, local = true)
        val withEcho = replica.timeline(options.copy(localEchoes = listOf(echo)))
        assertEquals("local", withEcho.items.last().entry.id)
    }
    @Test fun progressFastPathHandlesWaitingProcessingAndTerminalTransitions() {
        val replica = ChannelReplica(CHANNEL, SELF)
        replica.add(row(1, "work"), response(2, "work", "queued"), note(3))
        replica.timeline(options)
        replica.add(response(4, "work", "processing"))
        val running = replica.timeline(options)
        assertEquals(listOf("work", "note-3"), running.items.map { it.entry.id })
        assertTrue(running.waitingTurns.isEmpty())
        replica.add(response(5, "work", "processing"))
        val progress = replica.timeline(options)
        assertEquals(listOf("work"), progress.changes.updated)
        assertSame(running.items.last(), progress.items.last())
        replica.add(response(6, "work", "completed"))
        assertEquals("completed", replica.timeline(options).items.first().entry.turn!!.status)
        replica.add(response(7, "work", "queued"))
        assertTrue(replica.timeline(options).waitingTurns.isEmpty())
    }
    @Test fun nestedProgressAndFilteredChildRetainCorrectRootSnapshots() {
        val replica = ChannelReplica(CHANNEL, SELF)
        replica.add(row(1, "root", type = "human.ask"), row(2, "child", parent = "root", type = "tool.run", sender = AGENT), row(3, "ui", parent = "root", type = "ui.open"))
        val before = replica.timeline(options)
        replica.add(response(4, "ui", "processing"))
        val hiddenChange = replica.timeline(options)
        assertSame(before.items.first(), hiddenChange.items.first())
        replica.add(response(5, "child", "processing"))
        val childChange = replica.timeline(options)
        assertEquals(listOf("root"), childChange.changes.updated)
        assertEquals(1, childChange.items.first().entry.thread.single().turn!!.provisional.size)
        assertEquals(0, before.items.first().entry.thread.single().turn!!.provisional.size)
    }
    @Test fun completedRestartAndTrimInvalidateAuxiliaryInputs() {
        val replica = ChannelReplica(CHANNEL, SELF)
        replica.add(row(1, "work"), response(2, "work", "queued"), row(3, "restart", type = "system.member.restart", body = body("member" to AGENT)))
        replica.timeline(options)
        replica.add(response(4, "restart", "completed"))
        assertTrue(replica.timeline(options).waitingTurns.isEmpty())
        replica.trim(2, false)
        assertTrue(replica.timeline(options).items.none { it.entry.id == "work" })
        replica.add(row(1, "work"))
        assertTrue(replica.timeline(options).waitingTurns.isEmpty())
        assertEquals("work", replica.timeline(options).items.first().entry.id)
    }
    @Test fun aLateParentResponseAdmitsAnEarlierDetachedProcessRoot() {
        val replica = ChannelReplica(CHANNEL, SELF)
        replica.add(row(1, "work", type = "tool.run", sender = AGENT, parent = "late-answer", audience = listOf("tool:x:1")))
        assertTrue(replica.timeline(options).items.isEmpty())
        replica.add(response(2, "unknown-request", "completed", id = "late-answer"))
        assertEquals("work", replica.timeline(options).items.first().entry.id)
    }
    @Test fun lateSeedAdmitsEarlierStandaloneRelationRows() {
        val replica = ChannelReplica(CHANNEL, SELF)
        val unrelated = row(1, "earlier", kind = "event", parent = "seed", correlation = "conversation", sender = "agent:other:1", audience = emptyList())
        replica.add(unrelated)
        assertTrue(replica.timeline(options).items.isEmpty())
        replica.add(row(2, "seed", type = "human.ask", correlation = "conversation"))
        val result = replica.timeline(options)
        assertEquals(listOf("earlier", "seed"), result.items.map { it.entry.id })
    }
}

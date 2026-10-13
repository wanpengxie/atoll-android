package dev.atoll.app.model

import org.junit.Assert.*
import org.junit.Test

class QueueProjectionTest {
    private val options = TimelineOptions(selfId = SELF, now = 1)
    private fun history(started: Int, sender: String = SELF, receiver: String = AGENT): ChannelReplica {
        val replica = ChannelReplica(CHANNEL, SELF)
        replica.add(row(1, "waiting"), response(2, "waiting", "queued"))
        repeat(started) { index ->
            val seq = 10L + index * 10
            val id = "later-$index"
            replica.add(row(seq, id, sender = sender, audience = listOf(receiver)), response(seq + 1, id, "processing"))
        }
        return replica
    }
    @Test fun fullProjectionKeepsNineButDropsTenLaterHumanStarts() {
        assertEquals(listOf("waiting"), ConversationPresentation.project(history(9).snapshot(), options).waitingTurns.map { it.requestId })
        assertTrue(ConversationPresentation.project(history(10).snapshot(), options).waitingTurns.isEmpty())
        assertEquals(listOf("waiting"), history(10, sender = AGENT).timeline(options).waitingTurns.map { it.requestId })
        assertEquals(listOf("waiting"), history(10, receiver = "agent:other:1").timeline(options).waitingTurns.map { it.requestId })
    }
    @Test fun incrementalProjectionCannotResurrectPassedWaitingOnLateProgress() {
        val replica = history(9)
        replica.add(row(100, "tenth"), response(101, "tenth", "queued"))
        assertEquals(listOf("waiting", "tenth"), replica.timeline(options).waitingTurns.map { it.requestId })
        replica.add(response(102, "tenth", "processing"))
        assertTrue(replica.timeline(options).waitingTurns.isEmpty())
        replica.add(response(103, "waiting", "queued", extra = body("text" to "a late queue update")))
        val incremental = replica.timeline(options)
        assertTrue(incremental.waitingTurns.isEmpty())
        assertEquals("unchanged", incremental.changes.kind)
        assertEquals(ConversationPresentation.project(replica.snapshot(), options).waitingTurns, incremental.waitingTurns)
    }
    @Test fun nestedStartsCountAndDetachedWindowNeverShowsWaiting() {
        val replica = ChannelReplica(CHANNEL, SELF)
        replica.add(row(1, "waiting"), response(2, "waiting", "queued"), row(3, "root", type = "human.message"))
        repeat(10) { index ->
            val seq = 10L + index * 10
            replica.add(row(seq, "nested-$index", parent = "root"), response(seq + 1, "nested-$index", "processing"))
        }
        assertTrue(replica.timeline(options).waitingTurns.isEmpty())
        val detached = history(9).timeline(options.copy(newerUnloaded = true))
        assertTrue(detached.waitingTurns.isEmpty())
    }
    @Test fun indexDoesNotRecountLiveProgressAndRefoldDropsRemovedStarts() {
        val replica = history(10)
        val index = QueueProgressIndex()
        val turns = replica.snapshot().timeline.mapNotNull { it.turn }
        turns.forEach(index::note)
        val boundary = index.snapshot()
        assertEquals(10L, boundary[AGENT])
        repeat(40) { assertFalse(index.note(turns.last())) }
        assertSame(boundary, index.snapshot())
        index.clear()
        turns.take(10).forEach(index::note)
        assertTrue(index.snapshot().isEmpty())
        // Starts can be removed when the replica rebuilds a trimmed window.
        replica.trim(5, keepOpenTurns = false)
        replica.add(row(102, "fresh"), response(103, "fresh", "queued"))
        assertEquals(listOf("fresh"), replica.timeline(options).waitingTurns.map { it.requestId })
    }
}

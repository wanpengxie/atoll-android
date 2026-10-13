package dev.atoll.app.model

import org.junit.Assert.*
import org.junit.Test

class NewerUnloadedTest {
    @Test fun aHistoricalWindowDoesNotClaimThatMissingTerminalsAreRunning() {
        val replica = ChannelReplica(CHANNEL, SELF)
        replica.add(row(1, "queued"), response(2, "queued", "queued"), row(3, "running"), response(4, "running", "processing"),
            row(5, "task", kind = "event", type = "agent.task", parent = "running", sender = AGENT,
                body = body("call_id" to "call", "task_id" to "task", "phase" to "started", "title" to "work")))
        val tail = replica.timeline()
        assertEquals(1, tail.waitingTurns.size)
        assertEquals(1, tail.runningTasks.size)
        val history = replica.timeline(TimelineOptions(selfId = SELF, newerUnloaded = true))
        assertTrue(history.waitingTurns.isEmpty())
        assertTrue(history.runningTasks.isEmpty())
        assertTrue(history.items.filter { it.entry.turn != null }.all { it.settled && it.layoutClass != "reserved" })
        replica.add(response(6, "running", "processing"))
        val revised = replica.timeline(TimelineOptions(selfId = SELF, newerUnloaded = true))
        assertTrue(revised.waitingTurns.isEmpty())
        assertTrue(revised.runningTasks.isEmpty())
        assertEquals(1, replica.timeline().waitingTurns.size)
    }
}

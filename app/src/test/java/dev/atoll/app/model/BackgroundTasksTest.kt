package dev.atoll.app.model

import dev.atoll.app.core.*
import org.junit.Assert.*
import org.junit.Test

class BackgroundTasksTest {
    private fun task(seq: Long, call: String, phase: String, task: String = "", parent: String = "", sender: String = AGENT, text: String = "") = row(seq, "task-$seq", kind = "event", type = "agent.task", parent = parent, sender = sender, body = body("call_id" to call, "task_id" to task, "phase" to phase, "kind" to "agent", "title" to "research", "text" to text))
    @Test fun taskIdentitySurvivesResumeUnderDifferentCallAndEndAttachesToOriginalCard() {
        val replica = ChannelReplica(CHANNEL)
        replica.add(row(1, "first"), response(2, "first", "processing"), task(3, "start-call", "started", "task", "first"), task(4, "start-call", "progress", parent = "first", text = "working"), row(5, "later"), response(6, "later", "processing"), task(7, "resume-call", "message", "task", "later", text = "resumed"))
        val running = replica.timeline().runningTasks
        assertEquals(1, running.size)
        assertEquals("working", replica.timeline().items.first().entry.subTasks[1].body.text("text"))
        assertEquals("first", running.single().requestId)
        assertEquals("resumed", running.single().lastText)
        replica.add(task(8, "resume-call", "completed", parent = "later"))
        assertTrue(replica.timeline().runningTasks.isEmpty())
        assertEquals("completed", replica.timeline().items.first().entry.subTasks.last().body.text("phase"))
        assertEquals(listOf("first", "later"), replica.timeline().items.map { it.entry.id })
    }
    @Test fun stepsWithoutRecordedStartNeverClaimRunningAndActorsAreIndependent() {
        val replica = ChannelReplica(CHANNEL)
        replica.add(task(1, "call", "progress", "task"), task(2, "call", "started", "task", sender = "agent:other:1"), task(3, "other", "started", "other"))
        val running = BackgroundTasks.runningBackgroundTasks(replica.snapshot())
        assertEquals(2, running.size)
        assertEquals(1, BackgroundTasks.runningBackgroundTasks(replica.snapshot(), AGENT).size)
        assertEquals(3000L, running.first().startedAt)
    }
    @Test fun orphanTasksGroupByRequestOrConsecutiveUnparentedBurst() {
        val replica = ChannelReplica(CHANNEL)
        replica.add(task(1, "a", "started", "a"), task(2, "b", "started", "b"), task(3, "a", "progress", "a"), note(4), task(5, "c", "started", "c"))
        val items = replica.timeline().items
        assertEquals(3, items.size)
        assertEquals(3, items.first().entry.subTasks.size)
        assertEquals("task-1", items.first().key)
        assertEquals(1, items.last().entry.subTasks.size)
    }
    @Test fun progressRevisionIsThrottledButMessageAndCompletionAreImmediate() {
        val replica = ChannelReplica(CHANNEL)
        replica.add(row(1, "work"), response(2, "work", "processing"), task(3, "a", "started", "a", "work"))
        val initial = replica.timeline().items.first().entry.subTaskRevision
        replica.add(task(4, "a", "progress", "a", "work"))
        assertEquals(initial, replica.timeline().items.first().entry.subTaskRevision)
        replica.add(task(16, "a", "progress", "a", "work"))
        assertNotEquals(initial, replica.timeline().items.first().entry.subTaskRevision)
        val progress = replica.timeline().items.first().entry.subTaskRevision
        replica.add(task(17, "a", "message", "a", "work"))
        assertNotEquals(progress, replica.timeline().items.first().entry.subTaskRevision)
    }
}

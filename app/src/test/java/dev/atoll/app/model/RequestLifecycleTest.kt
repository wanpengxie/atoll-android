package dev.atoll.app.model

import org.junit.Assert.*
import org.junit.Test

/** Port of src/model/request-lifecycle.test.js. */
class RequestLifecycleTest {
    private fun ask(seq: Long = 10, status: String? = null, expires: Long = 0): Turn {
        val replica = ChannelReplica(CHANNEL)
        replica.add(row(seq, "r$seq", expires = expires))
        if (status != null) replica.add(response(seq + 1, "r$seq", status))
        return replica.turn("r$seq")
    }
    @Test fun readsLatestCoreStatusAndTerminal() {
        assertEquals(Lifecycle.PENDING, RequestLifecycle.of(ask()))
        for (status in listOf("received", "queued", "deferred")) assertEquals(Lifecycle.WAITING, RequestLifecycle.of(ask(status = status)))
        assertEquals(Lifecycle.PROCESSING, RequestLifecycle.of(ask(status = "processing")))
        assertEquals(Lifecycle.CLOSED, RequestLifecycle.of(ask(status = "completed")))
        assertEquals(Lifecycle.CLOSED, RequestLifecycle.of(ask(status = "failed")))
        val replica = ChannelReplica(CHANNEL)
        replica.add(row(10, "r10"), response(13, "r10", "queued"), response(12, "r10", "processing"))
        assertEquals(Lifecycle.WAITING, RequestLifecycle.of(replica.turn("r10")))
    }
    @Test fun completedMemberRestartMarksEarlierWorkLost() {
        val replica = ChannelReplica(CHANNEL)
        replica.add(row(10, "queued"), response(11, "queued", "queued"), row(12, "running"), response(13, "running", "processing"),
            row(20, "restart", type = "system.member.restart", body = body("member" to AGENT)), response(21, "restart", "completed"))
        val restarts = RequestLifecycle.memberRestarts(replica.snapshot())
        assertEquals(mapOf(AGENT to 21L), restarts)
        assertEquals(Lifecycle.LOST, RequestLifecycle.of(replica.turn("queued"), restarts))
        assertEquals("restart", RequestLifecycle.lostReason(replica.turn("running"), restarts))
        replica.add(response(30, "queued", "queued"))
        assertEquals(Lifecycle.WAITING, RequestLifecycle.of(replica.turn("queued"), restarts))
        assertEquals(Lifecycle.WAITING, RequestLifecycle.of(ask(status = "queued"), mapOf("agent:other:1" to 100)))
        assertEquals(Lifecycle.WAITING, RequestLifecycle.of(ask(40, "queued"), restarts))
    }
    @Test fun restartAllAndNestedRestartAreReadAndFailedRestartIgnored() {
        val replica = ChannelReplica(CHANNEL)
        replica.add(row(1, "root"), row(2, "all", type = "system.member.restart_all", parent = "root"), response(3, "all", "completed", extra = body("restarted" to listOf(AGENT, "tool:x:1"))),
            row(4, "failed", type = "system.member.restart", body = body("member" to AGENT)), response(5, "failed", "failed"))
        assertEquals(mapOf(AGENT to 3L, "tool:x:1" to 3L), RequestLifecycle.memberRestarts(replica.snapshot()))
    }
    @Test fun deadlineClosesWaitingWithoutTerminalAtExactInstant() {
        val turn = ask(status = "queued", expires = 1000)
        assertEquals(Lifecycle.WAITING, RequestLifecycle.of(turn, now = 999))
        assertEquals(Lifecycle.LOST, RequestLifecycle.of(turn, now = 1000))
        assertEquals("expired", RequestLifecycle.lostReason(turn, now = 1000))
        assertEquals("", RequestLifecycle.lostReason(turn, now = 999))
    }
    @Test fun tenLaterHumanStartsPassEarlierRequestToSameReceiver() {
        val earlier = ask(status = "queued")
        val later = (100L..190L step 10).map { ask(it, "processing") }
        assertFalse(RequestLifecycle.passedInQueue(listOf(earlier) + later.take(9))(earlier))
        val passed = RequestLifecycle.passedInQueue(listOf(earlier) + later)
        assertTrue(passed(earlier))
        assertFalse(passed(later.first()))
        val other = earlier.copy(request = row(5, "other", audience = listOf("agent:codex:1")).envelope)
        assertFalse(passed(other))
    }
    @Test fun agentStartsAndQueuedHumanMessagesDoNotPassQueue() {
        val earlier = ask(status = "queued")
        val fromAgents = (100L..190L step 10).map { seq -> ask(seq, "processing").let { it.copy(request = row(seq, "r$seq", sender = AGENT).envelope) } }
        val queued = (300L..390L step 10).map { ask(it, "queued") }
        assertFalse(RequestLifecycle.passedInQueue(listOf(earlier) + fromAgents + queued)(earlier))
    }
}

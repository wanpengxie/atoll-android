package dev.atoll.app.model

import dev.atoll.app.core.*
import java.util.Random
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Behavioural ports of channel-replica-* and replica-incremental-fold web tests. */
class ChannelReplicaTest {
    @Test fun duplicateAndFlatRowsDoNotAdvanceProjectionClock() {
        val replica = ChannelReplica(CHANNEL)
        assertEquals(1, replica.add(row(1, "flat", canonical = false)).accepted)
        assertEquals(0, replica.snapshot().projectionVersion)
        assertTrue(replica.snapshot().timeline.isEmpty())
        assertEquals(0, replica.add(row(1, "other")).accepted)
        replica.add(row(2, "canonical"))
        val version = replica.snapshot().projectionVersion
        assertEquals(0, replica.add(row(3, "canonical")).accepted)
        assertEquals(version, replica.snapshot().projectionVersion)
        assertEquals(2, replica.rowCount)
    }
    @Test fun flatTerminalIsNotLifecycleOrClosure() {
        val replica = ChannelReplica(CHANNEL)
        replica.add(row(1, "work"), row(2, "flat-final", kind = "response", parent = "work", body = body("status" to "completed"), canonical = false))
        assertNull(replica.turn("work").terminal)
        replica.trim(1, false)
        replica.add(row(1, "work"))
        assertNull(replica.turn("work").terminal)
    }
    @Test fun bodylessSystemNarrationIsAcceptedButFlatNarrationIsNotProjected() {
        val replica = ChannelReplica(CHANNEL)
        replica.add(row(1, "bodyless", kind = "event", visibility = "system", body = body(), canonical = false), row(2, "flat", visibility = "system", canonical = false), row(3, "public-system-word", type = "system.member.list"))
        assertEquals(listOf("bodyless"), replica.snapshot().narration.map { it.envelope.id })
        assertEquals(listOf("public-system-word"), replica.snapshot().timeline.map { it.id })
    }
    @Test fun responseFirstAndAncestorAdoptionUseParentNotCorrelation() {
        val replica = ChannelReplica(CHANNEL)
        replica.add(response(7, "grandchild", "completed"), row(4, "grandchild", parent = "child"), row(3, "child", parent = "root"), row(5, "reply", parent = "answer", correlation = "root"), response(2, "root", "completed", id = "answer"), row(1, "root"))
        assertEquals(listOf("root", "reply"), replica.snapshot().timeline.map { it.id })
        assertEquals(listOf("child", "grandchild"), replica.snapshot().timeline.first().thread.map { it.id })
        assertEquals("completed", replica.turn("grandchild").status)
    }
    @Test fun earliestTerminalWinsEvenWhenArrivingLast() {
        val replica = ChannelReplica(CHANNEL)
        replica.add(row(1, "work"), response(6, "work", "failed"), response(3, "work", "completed"), response(2, "work", "processing"))
        val turn = replica.turn("work")
        assertEquals("completed", turn.status)
        assertEquals(3L, turn.terminalSeq)
        assertEquals(6L, turn.lastSeq)
        assertEquals(listOf(2L), turn.provisional.map { it.seq })
    }
    @Test fun immutableSnapshotsAndUnrelatedEntriesArePreserved() {
        val replica = ChannelReplica(CHANNEL)
        replica.add(row(1, "root"), row(2, "unrelated"), row(3, "child", parent = "root"))
        val before = replica.snapshot()
        val unaffected = before.timeline.last()
        val changed = replica.add(response(4, "child", "processing"))
        assertEquals(setOf("root"), changed.changedIds)
        assertFalse(changed.structureChanged)
        assertTrue(before.timeline.first().thread.first().turn!!.provisional.isEmpty())
        assertSame(unaffected, replica.snapshot().timeline.last())
        try { (before.timeline as MutableList).clear(); fail("snapshot list is writable") } catch (_: UnsupportedOperationException) { }
    }
    @Test fun trimRetainsExactClosuresBeyond512TurnsAndSuppressesEchoes() {
        val replica = ChannelReplica(CHANNEL, SELF)
        for (index in 0..600) {
            val seq = index * 3L + 1
            replica.add(row(seq, "r$index"), response(seq + 1, "r$index", "queued"), response(seq + 2, "r$index", "completed"))
        }
        assertTrue(replica.trim(24) > 0)
        assertTrue(replica.snapshot().envelopesById.containsKey("r0"))
        replica.add(row(1, "r0"), response(2, "r0", "queued"))
        assertEquals("completed", replica.turn("r0").status)
        assertTrue(replica.turn("r0").terminalClosureOnly)
        assertTrue(replica.timeline().waitingTurns.none { it.requestId == "r0" })
        replica.add(response(3, "r0", "completed", extra = body("text" to "full answer")))
        // Same id was compacted without text; the full exact row is readmitted and displayed.
        assertEquals("full answer", replica.turn("r0").terminal!!.body.text("text"))
        assertFalse(replica.turn("r0").terminalClosureOnly)
    }
    @Test fun survivingTerminalRecoversItsCompactParent() {
        val replica = ChannelReplica(CHANNEL)
        replica.add(row(1, "work"))
        for (seq in 2L..99L) replica.add(note(seq))
        replica.add(response(100, "work", "completed"))
        replica.trim(8)
        assertFalse(replica.snapshot().rows.any { it.seq == 1L })
        assertEquals("completed", replica.turn("work").status)
        assertEquals(1L, replica.turn("work").requestSeq)
    }
    @Test fun responseFirstProofCrossesTrimAndEarlierTerminalSupersedesIt() {
        val replica = ChannelReplica(CHANNEL)
        replica.add(response(5, "work", "failed"))
        for (seq in 6L..12L) replica.add(note(seq))
        replica.trim(4)
        replica.add(response(2, "work", "completed"), row(1, "work"))
        assertEquals(2L, replica.turn("work").terminalSeq)
        replica.trim(2, false)
        replica.add(row(1, "work"))
        assertEquals("completed", replica.turn("work").status)
    }
    @Test fun responseFirstClosureNeverManufacturesRequest() {
        val replica = ChannelReplica(CHANNEL)
        replica.add(response(2, "missing", "completed"))
        for (seq in 3L..10L) replica.add(note(seq))
        replica.trim(4)
        assertTrue(replica.snapshot().timeline.none { it.turn != null })
        replica.add(row(1, "missing"), response(11, "missing", "queued"))
        assertEquals(Lifecycle.CLOSED, RequestLifecycle.of(replica.turn("missing")))
    }
    @Test fun openChildPinsAncestorsEvenWhenParentIsClosed() {
        val replica = ChannelReplica(CHANNEL)
        replica.add(row(1, "root"), response(2, "root", "completed"), row(3, "child", parent = "root"))
        for (seq in 4L..60L) replica.add(note(seq))
        assertEquals(0, replica.trim(8))
        replica.add(response(61, "child", "completed"))
        assertTrue(replica.trim(8) > 0)
    }
    @Test fun nestedTerminalControlFactsSurviveCompactClosure() {
        val replica = ChannelReplica(CHANNEL)
        replica.add(row(1, "work"), response(2, "work", "completed", extra = body("value" to body("replaced_by" to "successor", "merged_into" to "other"), "text" to "large answer")))
        for (seq in 3L..10L) replica.add(note(seq))
        replica.trim(4)
        replica.add(row(1, "work"))
        assertEquals("successor", replica.turn("work").terminal!!.body.text("replaced_by"))
        assertEquals("other", replica.turn("work").terminal!!.body.text("merged_into"))
        assertEquals("", replica.turn("work").terminal!!.body.text("text"))
    }
    @Test fun provisionalIsNotRetainedAsTerminalProof() {
        val replica = ChannelReplica(CHANNEL)
        replica.add(response(2, "missing", "processing"))
        for (seq in 3L..10L) replica.add(note(seq))
        replica.trim(4)
        replica.add(row(1, "missing"))
        assertNull(replica.turn("missing").terminal)
        assertTrue(replica.turn("missing").provisional.isEmpty())
    }
    @Test fun trimmingOnlyFlatRowsDoesNotInvalidateProjection() {
        val replica = ChannelReplica(CHANNEL)
        replica.add(row(1, "flat1", canonical = false), row(2, "flat2", canonical = false), note(3))
        val before = replica.snapshot().projectionVersion
        assertEquals(2, replica.trim(1))
        assertEquals(before, replica.snapshot().projectionVersion)
    }
    @Test fun randomArrivalOrdersAndPartialDeliveriesMatchLedgerOrder() {
        val random = Random(42)
        repeat(400) {
            val rows = (1L..24L).map { seq ->
                val target = "r${random.nextInt(26) + 1}"
                when (random.nextInt(4)) {
                    0 -> row(seq, "r$seq", parent = if (target == "r$seq") "" else target)
                    1 -> row(seq, "r$seq", correlation = target)
                    2 -> response(seq, target, if (random.nextBoolean()) "completed" else "processing", "r$seq")
                    else -> note(seq, "r$seq")
                }
            }.filter { random.nextBoolean() }
            val ledger = ChannelReplica(CHANNEL).also { it.add(*rows.toTypedArray()) }.snapshot()
            val shuffled = ChannelReplica(CHANNEL).also { it.add(*rows.shuffled(kotlin.random.Random(random.nextLong())).toTypedArray()) }.snapshot()
            assertEquals(ledger.timeline, shuffled.timeline)
            assertEquals(ledger.narration, shuffled.narration)
        }
    }
    @Test fun incrementalResponseCostDoesNotRebuildUnrelatedTurns() {
        val replica = ChannelReplica(CHANNEL)
        replica.add(*(1L..10000L).map { row(it, "r$it") }.toTypedArray())
        val before = replica.snapshot()
        val change = replica.add(response(10001, "r5000", "processing"))
        val after = replica.snapshot()
        assertEquals(setOf("r5000"), change.changedIds)
        assertEquals(9999, before.timeline.indices.count { before.timeline[it] === after.timeline[it] })
    }
}

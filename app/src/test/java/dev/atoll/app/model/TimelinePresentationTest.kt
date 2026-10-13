package dev.atoll.app.model

import org.junit.Assert.*
import org.junit.Test

/** Non-DOM ports of tests/timeline-presentation-identity.test.js. */
class TimelinePresentationTest {
    private val options = TimelineOptions(ConversationScope.ALL, SELF, now = 1000)
    private fun message(seq: Long, id: String) = row(seq, id, kind = "response", type = "human.message", sender = AGENT)
    @Test fun prependAppendAndMixedChangesPreservePublishedIdentity() {
        val replica = ChannelReplica(CHANNEL)
        val owner = TimelinePresentation()
        replica.add(message(2, "b"), message(3, "c"))
        val initial = owner.project(replica.snapshot(), options)
        assertEquals("rebase", initial.changes.kind)
        replica.add(message(1, "a"))
        val prepend = owner.project(replica.snapshot(), options)
        assertEquals("prepend", prepend.changes.kind)
        assertEquals(listOf("a"), prepend.changes.frontInsertedIds)
        assertSame(initial.items.first(), prepend.items[1])
        assertFalse(prepend.items[1].continuation)
        replica.add(message(4, "d"))
        val append = owner.project(replica.snapshot(), options)
        assertEquals("append", append.changes.kind)
        assertEquals(listOf("d"), append.changes.backInsertedIds)
    }
    @Test fun contentOnlyChangeRetainsUnrelatedRenderedRows() {
        val replica = ChannelReplica(CHANNEL)
        replica.add(row(1, "root"), response(2, "root", "processing"), row(3, "child", parent = "root", type = "tool.run", sender = AGENT), message(4, "unrelated"))
        val owner = TimelinePresentation()
        val before = owner.project(replica.snapshot(), options)
        replica.add(response(5, "child", "processing"))
        val after = owner.project(replica.snapshot(), options)
        assertEquals(listOf("root"), after.changes.updated)
        assertSame(before.items.last(), after.items.last())
        assertTrue(before.items.first().entry.thread.first().turn!!.provisional.isEmpty())
        assertEquals(1, after.items.first().entry.thread.first().turn!!.provisional.size)
    }
    @Test fun filteredUiChildDoesNotReviseMineCard() {
        val replica = ChannelReplica(CHANNEL)
        replica.add(row(1, "root", type = "human.ask"), row(2, "ui", parent = "root", type = "ui.open"))
        val owner = TimelinePresentation()
        val before = owner.project(replica.snapshot(), options.copy(scope = ConversationScope.MINE))
        replica.add(response(3, "ui", "processing"))
        val after = owner.project(replica.snapshot(), options.copy(scope = ConversationScope.MINE))
        assertTrue(after.changes.updated.isEmpty())
        assertSame(before.items.first(), after.items.first())
    }
    @Test fun reciprocalReplacementAtSamePositionInheritsKeyAcrossChain() {
        val replica = ChannelReplica(CHANNEL)
        val owner = TimelinePresentation()
        replica.add(message(1, "before"), row(2, "a", type = "project.task"), message(3, "after"))
        val initial = owner.project(replica.snapshot(), options)
        replica.add(response(4, "a", "completed", extra = body("replaced_by" to "b")), row(5, "b", type = "agent.replace", body = body("target" to "a")), response(6, "b", "processing"))
        // Keeping both neighbours in ledger order is required: successor appended after
        // 'after' cannot inherit a geometry slot that belonged between those neighbours.
        val moved = owner.project(replica.snapshot(), options)
        assertEquals("b", moved.items.last().key)
        val simple = ChannelReplica(CHANNEL)
        val simpleOwner = TimelinePresentation()
        simple.add(row(1, "a", type = "project.task"))
        simpleOwner.project(simple.snapshot(), options)
        simple.add(response(2, "a", "completed", extra = body("replaced_by" to "b")), row(3, "b", type = "agent.replace", body = body("target" to "a")), response(4, "b", "processing"))
        assertEquals("a", simpleOwner.project(simple.snapshot(), options).items.single().key)
        simple.add(response(5, "b", "completed", extra = body("replaced_by" to "c")), row(6, "c", type = "agent.replace", body = body("target" to "b")), response(7, "c", "processing"))
        assertEquals("a", simpleOwner.project(simple.snapshot(), options).items.single().key)
        assertEquals("a", initial.items[1].key)
    }
    @Test fun oneWayReplacementAndEpochChangesNeverTransferKey() {
        val replica = ChannelReplica(CHANNEL)
        val owner = TimelinePresentation()
        replica.add(row(1, "a", type = "project.task"))
        owner.project(replica.snapshot(), options, "epoch1")
        replica.add(response(2, "a", "completed", extra = body("replaced_by" to "b")), row(3, "b", type = "agent.replace", body = body("target" to "a")), response(4, "b", "processing"))
        assertEquals("b", owner.project(replica.snapshot(), options, "epoch2").items.single().key)
    }
    @Test fun viewChangesRebaseContinuationAndCurrentCandidateExcludesControls() {
        val replica = ChannelReplica(CHANNEL)
        replica.add(message(1, "a"), row(2, "select", type = "agent.select"), response(3, "select", "completed"))
        val owner = TimelinePresentation()
        val all = owner.project(replica.snapshot(), options)
        assertEquals("a", all.currentEntryCandidate!!.id)
        val mine = owner.project(replica.snapshot(), options.copy(scope = ConversationScope.MINE))
        assertEquals("rebase", mine.changes.kind)
    }
    @Test fun acceptedRowsOwnDetachedJsonEvenIfCallerMutatesOriginalMap() {
        val original = row(1, "a", type = "project.task")
        val backing = original.envelope.json.toMutableMap()
        val replica = ChannelReplica(CHANNEL)
        replica.add(original.copy(envelope = dev.atoll.app.core.Envelope(kotlinx.serialization.json.JsonObject(backing))))
        val snapshot = replica.snapshot()
        backing["type"] = kotlinx.serialization.json.JsonPrimitive("ui.open")
        assertEquals("project.task", snapshot.timeline.first().turn!!.request.type)
    }
}

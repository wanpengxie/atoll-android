package dev.atoll.app.app

import dev.atoll.app.core.*
import dev.atoll.app.model.*
import dev.atoll.app.ui.screens.TimelineEntry as UiEntry
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TimelineMessageTargetTest {
    @Test fun messageActionsResolveOpaqueIdsAndRepliesToTheOwningTurn() {
        val replica = ChannelReplica("c", "human:self")
        fun row(seq: Long, id: String, kind: String, parent: String = "", status: String = "") = Row("c", seq, Envelope(buildJsonObject {
            put("id", id); put("kind", kind); put("type", "agent.ask"); put("parent_id", parent)
            put("sender", buildJsonObject { put("id", if (kind == "request") "human:self" else "agent:a"); put("kind", if (kind == "request") "human" else "agent") })
            put("audience", buildJsonArray { add("agent:a"); add("human:self") })
            put("payload", buildJsonObject { put("body", buildJsonObject { put("text", id); if (status.isNotEmpty()) put("status", status) }) })
        }))
        replica.apply(RowBatch("c", listOf(row(1,"opaque:request:answer","request"), row(2,"progress:x","response","opaque:request:answer","processing"),
            row(3,"nested:request","request","opaque:request:answer"), row(4,"nested:answer","response","nested:request","completed")), Origin.CACHE))
        val snapshot = replica.timeline(TimelineOptions(selfId = "human:self"))
        val entries = TimelineMapper().map(snapshot, "human:self", emptyList()).filterIsInstance<UiEntry.Message>()
        assertTrue(entries.isNotEmpty())
        val targets = entries.map { timelineMessageTarget(snapshot, it.key) }
        assertTrue(targets.all { it != null })
        assertTrue(targets.any { it?.envelope?.id == "opaque:request:answer" && it.turn?.requestId == "opaque:request:answer" })
        assertTrue(targets.any { it?.envelope?.id == "progress:x" && it.turn?.requestId == "opaque:request:answer" })
        assertEquals(2,entries.size)
        assertEquals("nested:request",entries.last().value.relatedCalls.single().id)
        val nestedKey="${snapshot.items.single().key}:nested:request:answer"
        val nested=timelineMessageTarget(snapshot,nestedKey)!!
        assertEquals("nested:answer",nested.envelope.id);assertEquals("nested:request",nested.turn!!.requestId)
        assertEquals(4L,nested.seq)
        val index=timelineMessageTargets(snapshot)
        val rootProcess=processTarget(entries.first().value.id,index,replica.snapshot())!!
        assertEquals("opaque:request:answer",rootProcess.turn!!.requestId)
        val rootAnswerProcess=processTarget(entries.last().value.id,index,replica.snapshot())!!
        assertEquals("opaque:request:answer",rootAnswerProcess.turn!!.requestId)
        val childProcess=processTarget("nested:request",index,replica.snapshot())!!
        assertEquals("nested:request",childProcess.turn!!.requestId)
        assertEquals(3L,childProcess.seq)
        val rootIndex=timelineFocusIndex(entries,index,replica.snapshot(),"opaque:request:answer")
        assertEquals(0,rootIndex)
        assertEquals(rootIndex,timelineFocusIndex(entries,index,replica.snapshot(),"nested:request"))
        assertEquals(rootIndex,timelineFocusIndex(entries,index,replica.snapshot(),"nested:answer"))
        assertEquals(-1,timelineFocusIndex(entries,index,replica.snapshot(),"not-loaded"))
        assertNull(timelineMessageTarget(snapshot, "wrong:request"))
        val sequences = timelineRowSequences(snapshot)
        entries.forEach { entry ->
            val target = timelineMessageTarget(snapshot, entry.key)!!
            val expected = mapOf("opaque:request:answer" to 1L, "progress:x" to 2L, "nested:request" to 3L, "nested:answer" to 4L)
            assertEquals(expected[target.envelope.id], sequences[entry.key])
            assertEquals(expected[target.envelope.id], target.seq)
        }
    }
}

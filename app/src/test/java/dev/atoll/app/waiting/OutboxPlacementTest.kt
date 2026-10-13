package dev.atoll.app.waiting

import dev.atoll.app.compose.*
import dev.atoll.app.core.*
import dev.atoll.app.model.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class OutboxPlacementTest {
    private fun submission(id: String = "local", type: String = "agent.ask", state: SendState = SendState.QUEUED) =
        LocalSubmission(id, "c", buildJsonObject {
            put("id", id); put("channel_id", "c"); put("msg_type", type); put("kind", "request")
            put("audience", buildJsonArray { add("agent:a") }); put("parent_id", "parent")
            put("payload", buildJsonObject { put("text", "hello"); put("attachments", buildJsonArray { add(buildJsonObject { put("resource_id", "file-ref") }) }) })
        }, "hello", "A", state, 100)
    private fun turn(id: String, seq: Long, status: String, actor: String = "agent:a", expiresAt: Long = 0): Turn {
        val request = Envelope(buildJsonObject { put("id", id); put("type", "agent.ask"); put("kind", "request"); put("expires_at", expiresAt)
            put("audience", buildJsonArray { add(actor) }); put("sender", buildJsonObject { put("id", "self"); put("kind", "human") }) })
        val response = Envelope(buildJsonObject { put("kind", "response"); put("payload", buildJsonObject { put("body", buildJsonObject { put("status", status) }) }) })
        return if (status == "completed") Turn(request, seq, provisional = listOf(Row("c", seq + 1,
            Envelope(buildJsonObject { put("kind", "response"); put("payload", buildJsonObject { put("body", payload("status" to "processing")) }) }))),
            terminal = response, terminalSeq = seq + 2)
            else Turn(request, seq, listOf(Row("c", seq + 1, response)))
    }
    private fun source(turns: List<Turn>) = ReplicaSnapshot("c", 1, 1, 100, emptyList(),
        turns.map { TimelineEntry("turn", it.requestSeq, turn = it) }, emptyList(), emptyMap())

    @Test fun busyUsesUnfilteredNestedLedgerAndOmitsExpiredClosedAndPending() {
        val child = turn("nested", 5, "processing", "agent:nested")
        val root = TimelineEntry("turn", 1, turn = turn("root", 1, "queued"), thread = listOf(TimelineEntry("turn", 5, turn = child)))
        val snapshot = source(listOf(turn("expired", 7, "processing", "agent:expired", 50), turn("closed", 9, "completed", "agent:closed"), turn("pending", 11, "", "agent:pending")))
        assertEquals(setOf("agent:a", "agent:nested"), busyActors(snapshot.copy(timeline = listOf(root) + snapshot.timeline), 100))
    }
    @Test fun tenLaterStartedRequestsRemoveZombieBusyReceiver() {
        val queued = turn("old", 1, "queued")
        val completed = (1..10).map { turn("done$it", 10L + it * 2, "completed") }
        assertEquals(setOf("agent:a"), busyActors(source(listOf(queued) + completed.take(9)), 100))
        assertTrue(busyActors(source(listOf(queued) + completed), 100).isEmpty())
    }
    @Test fun firstPlacementIsStableAcrossBusyChangesAndCanonicalAdmissionClearsIt() {
        val placement = OutboxPlacement(); val row = submission()
        val busy = placement.project("c", "self", listOf(row), emptyMap(), setOf("agent:a"), 101)
        assertEquals(listOf(row), busy.waiting); assertTrue(busy.localEchoes.isEmpty())
        assertEquals(listOf(row), placement.project("c", "self", listOf(row), emptyMap(), emptySet(), 102).waiting)
        val admitted = placement.project("c", "self", listOf(row), mapOf(row.messageId to turn(row.messageId, 2, "processing")), setOf("agent:a"), 103)
        assertTrue(admitted.waiting.isEmpty()); assertTrue(admitted.timelinePlaced.isEmpty())
        val idle = OutboxPlacement().project("c", "self", listOf(row), emptyMap(), emptySet(), 101)
        assertEquals(setOf(row.messageId), idle.timelinePlaced); assertEquals("local", idle.localEchoes.single().id)
    }
    @Test fun echoPreservesPayloadAttachmentsParentAndSenderWithoutLedgerSequence() {
        val row = submission()
        val entry = row.localTimelineEntry("self"); val envelope = entry.turn!!.request
        assertTrue(entry.local); assertEquals(0L, entry.seq); assertEquals("self", envelope.senderId)
        assertEquals("parent", envelope.parentId); assertEquals("hello", envelope.body.text("text"))
        assertEquals("file-ref", envelope.body.getValue("attachments").jsonArray.single().jsonObject.text("resource_id"))
    }
    @Test fun placementSurvivesLedgerLandingBeforeFirstReceiverStatus() {
        val owner = OutboxPlacement(); val row = submission()
        owner.project("c", "self", listOf(row), emptyMap(), emptySet(), 101)
        val pending = turn(row.messageId, 2, "")
        val landed = owner.project("c", "self", emptyList(), mapOf(row.messageId to pending), emptySet(), 102)
        assertEquals(setOf(row.messageId), landed.timelinePlaced)
        assertTrue(landed.localEchoes.isEmpty()); assertTrue(landed.waiting.isEmpty())
        assertTrue(owner.project("c", "self", emptyList(), mapOf(row.messageId to pending.copy(terminal = pending.request)), emptySet(), 103).timelinePlaced.isEmpty())
    }
    @Test fun otherChannelsExpiredControlsAndReplacementDoNotMakeEchoesOrDockRows() {
        val rows = listOf(submission(type = "agent.replace"), submission("control", "agent.hold"), submission("ui", "ui.form"),
            submission("other").copy(channelId = "other"), submission("expired").let { it.copy(frame = JsonObject(it.frame + ("expires_at_ms" to JsonPrimitive(100)))) })
        val projection = OutboxPlacement().project("c", "self", rows, emptyMap(), emptySet(), 101)
        assertTrue(projection.waiting.isEmpty()); assertTrue(projection.localEchoes.isEmpty())
    }
    @Test fun allRejectedTypesBelongOnlyToComposerRecovery() {
        val rejected = listOf("agent.ask", "agent.queue", "human.message", "agent.steer").mapIndexed { index, type ->
            submission("rejected$index", type, SendState.REJECTED)
        }
        val projection = OutboxPlacement().project("c", "self", rejected, emptyMap(), setOf("agent:a"), 101)
        assertTrue(projection.localEchoes.isEmpty()); assertTrue(projection.waiting.isEmpty()); assertTrue(projection.timelinePlaced.isEmpty())
    }
}

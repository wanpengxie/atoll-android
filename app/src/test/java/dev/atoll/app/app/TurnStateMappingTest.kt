package dev.atoll.app.app

import dev.atoll.app.core.*
import dev.atoll.app.model.*
import dev.atoll.app.ui.screens.TimelineEntry as UiEntry
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TurnStateMappingTest {
    private fun row(seq: Long, id: String, kind: String, type: String, body: JsonObject, parent: String = "") = Row("c", seq, Envelope(buildJsonObject {
        put("id", id); put("channel_id", "c"); put("kind", kind); put("type", type); put("parent_id", parent)
        put("sender", buildJsonObject { put("id", if (kind == "request") "human:self" else "agent:a"); put("kind", if (kind == "request") "human" else "agent") })
        put("audience", buildJsonArray { add(if (kind == "request") "agent:a" else "human:self") })
        put("payload", buildJsonObject { put("body", body) })
    }))
    @Test fun predecessorReplacementProofInvalidatesOnlyTheEditedCachedCard() {
        val replica = ChannelReplica("c", "human:self"); val mapper = TimelineMapper()
        replica.apply(RowBatch("c", listOf(
            row(1,"old","request","agent.ask",buildJsonObject { put("text","before") }),
            row(2,"edit","request","agent.replace",buildJsonObject { put("target","old"); put("new_text","after") })), Origin.LIVE))
        fun edited() = mapper.map(replica.timeline(TimelineOptions(scope = ConversationScope.ALL, selfId = "human:self", timelinePlaced = setOf("edit"))), "human:self", emptyList(), source = replica.snapshot())
            .filterIsInstance<UiEntry.Message>().first { it.value.turnState?.requestId == "edit" }.value
        assertFalse(edited().turnState!!.edited)
        replica.apply(RowBatch("c", listOf(row(3,"replacement-proof","response","agent.ask",
            buildJsonObject { put("status","failed"); put("replaced_by","edit") }, "old")), Origin.LIVE))
        val result = edited()
        assertTrue(result.turnState!!.edited); assertSame(result, edited())
    }
    @Test fun collapsingTheParentDoesNotHideAnInteractiveHumanApproval() {
        val replica = ChannelReplica("c", "human:self")
        val approval = row(2,"approve","request","human.approve",buildJsonObject { put("title","Check release") }, "root")
        val addressed = approval.copy(envelope = Envelope(JsonObject(approval.envelope.json + mapOf(
            "audience" to buildJsonArray { add("human:self") }, "sender" to buildJsonObject { put("id","agent:a"); put("kind","agent") }))))
        replica.apply(RowBatch("c", listOf(row(1,"root","request","agent.ask",buildJsonObject { put("text","release") }), addressed,
            row(3,"merged","response","agent.ask",buildJsonObject { put("status","completed"); put("merged_into","next") },"root")), Origin.LIVE))
        val snapshot = replica.timeline(TimelineOptions(scope = ConversationScope.ALL, selfId = "human:self"))
        val result = TimelineMapper().map(snapshot,"human:self",emptyList()).filterIsInstance<UiEntry.Message>()
        assertTrue(result.any { it.value.text == "Check release" })
        assertTrue(replica.snapshot().timeline.any { it.turn?.requestId == "root" })
    }
}

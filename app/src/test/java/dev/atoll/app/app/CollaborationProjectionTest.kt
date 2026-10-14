package dev.atoll.app.app

import dev.atoll.app.core.*
import dev.atoll.app.model.*
import dev.atoll.app.ui.screens.CollaborationIndex
import dev.atoll.app.ui.screens.TimelineEntry as UiEntry
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CollaborationProjectionTest {
    private fun row(seq: Long, id: String, parent: String = "", sender: String = "agent:builder",
        audience: String = "agent:reviewer", type: String = "agent.ask", kind: String = "request", text: String = id) = Row("c", seq,
        Envelope(buildJsonObject {
            put("id", id); put("parent_id", parent); put("kind", kind); put("type", type); put("ts", seq * 1000)
            put("sender", buildJsonObject { put("id", sender); put("kind", sender.substringBefore(':')) })
            put("audience", buildJsonArray { add(audience) })
            put("payload", buildJsonObject { put("body", buildJsonObject { put("text", text); if (kind == "response") put("status", "completed") }) })
        }))

    @Test fun openingAChildUsesTheRootThreadAndKeepsSelfApprovalsOutsideTheReadOnlyTimelineList() {
        val replica = ChannelReplica("c", "human:self")
        replica.apply(RowBatch("c", listOf(row(1, "root", sender = "human:self", audience = "agent:builder"),
            row(2, "review", "root"), row(3, "test", "review", "agent:reviewer", "agent:test-writer"),
            row(4, "ask", "review", "agent:reviewer", "human:self", "human.ask"),
            row(5, "answer", "ask", "human:self", "agent:reviewer", "human.ask", "response", "先不用"),
            row(6, "root-answer", "root", "agent:builder", "human:self", kind = "response")), Origin.CACHE))
        val source = replica.snapshot()
        val target = processTarget("review", emptyMap(), source)!!
        val view = collaborationView(target, source, mapOf("agent:builder" to "builder", "agent:reviewer" to "reviewer", "agent:test-writer" to "test-writer"), selfId = "human:self")!!
        assertEquals("root", view.root.id)
        assertEquals("builder", view.owner)
        assertEquals(listOf("review", "test", "ask"), view.calls.map { it.id })
        assertEquals(listOf("root", "review", "ask"), CollaborationIndex(view).path("ask").map { it.id })
        assertEquals("human.ask", view.calls.last().requestType)
        assertEquals(listOf("我"), view.calls.last().recipients)
        assertEquals("先不用", view.calls.last().text)

        val mapped = TimelineMapper().map(replica.timeline(TimelineOptions(selfId = "human:self")), "human:self", emptyList(), source = source)
            .filterIsInstance<UiEntry.Message>()
        assertTrue(mapped.any { it.value.id.endsWith("ask:request") })
        assertFalse(mapped.flatMap { it.value.relatedCalls }.any { it.id == "ask" })
        assertEquals("root", mapped.flatMap { it.value.relatedCalls }.first { it.id == "review" }.parentId)
    }

    @Test fun aStandaloneTargetDoesNotInventATurnAndARootWithoutLoadedThreadIsStillReadable() {
        val request = row(1, "root").envelope
        assertNull(collaborationView(TimelineMessageTarget(request, null), null, emptyMap()))
        val turn = Turn(request, 1)
        val view = collaborationView(TimelineMessageTarget(request, turn), null, emptyMap())!!
        assertEquals("root", view.root.id)
        assertTrue(view.calls.isEmpty())
    }
}

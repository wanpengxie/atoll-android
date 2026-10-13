package dev.atoll.app.app

import dev.atoll.app.core.*
import dev.atoll.app.model.*
import dev.atoll.app.ui.screens.TimelineEntry as UiEntry
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class WorkspaceMappingTest {
    @Test fun readsActualObservationStructureAndOrdersTheTree() {
        val response = Json.parseToJsonElement("""{"items":[{"key":"dev-id","declared":{"id":"dev-id","name":"dev","qualified_name":"c0.dev","parent_id":"c0","type":"group"},"actual":{"measures":[{"name":"open","value":true,"unknown":false}]}},{"declared":{"id":"c0","name":"c0","qualified_name":"c0","type":"group"}},{"declared":{"id":"agent-channel","name":"agent","parent_id":"c0","type":"actor"}}]}""").jsonObject
        val records = channelRecords(response)
        assertEquals(true, records.first().open)
        assertEquals(listOf("c0", "dev-id"), sortedChannels(records).map { it.first.id })
        assertEquals(listOf(0, 1), sortedChannels(records).map { it.second })
    }
    @Test fun appAndWebDeepLinksPreserveTheOpaqueChannelAndFocus() {
        assertEquals(AppRoute("channel/id", "tasks", "turn:message"), AppRoute.parse("atoll://channels/channel%2Fid/tasks?focus=turn%3Amessage"))
        assertEquals(AppRoute("abc", "files"), AppRoute.parse("https://node.example/#/channels/abc/files"))
        assertNull(AppRoute.parse("https://node.example/auth/callback?code=abc"))
        assertNotEquals(accountNamespace("https://one", "root"), accountNamespace("https://two", "root"))
        assertNotEquals(accountNamespace("https://one", "root"), accountNamespace("https://one", "other"))
    }
    @Test fun mappingPreservesCompletedAnswerAndHidesHistoricalProcessingMarker() {
        val replica = ChannelReplica("c", "human:self")
        val request = row(1, "q", "request", "human:self", "question")
        val progress = row(2, "p", "response", "agent:a", "", "q", "processing")
        replica.apply(RowBatch("c", listOf(request, progress), Origin.CACHE))
        val mapper = TimelineMapper()
        val actors = listOf(ActorRecord("agent:a", "Agent A", "agent"))
        val normal = mapper.map(replica.timeline(TimelineOptions(selfId = "human:self")), "human:self", actors)
        assertTrue(normal.filterIsInstance<UiEntry.Message>().any { it.value.status != null })
        val history = TimelineMapper().map(replica.timeline(TimelineOptions(selfId = "human:self", newerUnloaded = true)), "human:self", actors, true)
        assertTrue(history.filterIsInstance<UiEntry.Message>().all { it.value.status == null })
        replica.apply(RowBatch("c", listOf(row(3, "answer", "response", "agent:a", "done", "q", "completed")), Origin.LIVE))
        val answer = mapper.map(replica.timeline(TimelineOptions(selfId = "human:self")), "human:self", actors)
            .filterIsInstance<UiEntry.Message>().last().value
        assertEquals("done", answer.text)
        assertEquals("Agent A", answer.sender)
    }
    private fun row(seq: Long, id: String, kind: String, sender: String, text: String, parent: String = "", status: String = "") = Row("c", seq, Envelope(buildJsonObject {
        put("id", id); put("kind", kind); put("type", "agent.ask"); put("parent_id", parent)
        put("sender", buildJsonObject { put("id", sender); put("kind", sender.substringBefore(':')) })
        put("audience", buildJsonArray { add("human:self"); add("agent:a") })
        put("payload", buildJsonObject { put("body", buildJsonObject { put("text", text); if (status.isNotEmpty()) put("status", status) }) })
    }))
}

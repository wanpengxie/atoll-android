package dev.atoll.app.app

import dev.atoll.app.core.*
import dev.atoll.app.stars.StarredMessage
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class FavoritesProjectionTest {
    private fun row(body: JsonObject, channel: String = "c", kind: String = "response") = Row(channel, 1, Envelope(buildJsonObject {
        put("id", "r"); put("channel_id", channel); put("kind", kind); put("type", "agent.ask")
        put("payload", buildJsonObject { put("body", body) })
    }))
    private val star = StarredMessage("r", "human:self", "{\"notEvidence\":true}", 1)
    private fun project(rows: List<Row>) = favoriteStars(listOf(star), "c", emptyMap(), "human:self", rows) { "今天" }.single()

    @Test fun onlyLoadedCompletedResponseBusinessFieldsProveTheBadge() {
        val response = row(buildJsonObject { put("status", "completed"); put("a", 1); put("b", 2); put("c", 3); put("closed_by", "receiver") })
        assertEquals(3, project(listOf(response)).resultFields)
        assertNull(project(emptyList()).resultFields)
        assertNull(project(listOf(response.copy(channelId = "other"))).resultFields)
        assertNull(project(listOf(row(response.envelope.body, kind = "request"))).resultFields)
        assertNull(project(listOf(row(buildJsonObject { put("status", "failed"); put("data", 1) }))).resultFields)
        assertNull(project(listOf(row(buildJsonObject { put("status", "completed") }))).resultFields)
    }
    @Test fun parsedJsonRequiresTheOriginalTextRatherThanTheStoredSnippet() {
        assertNull(project(emptyList()).resultFields)
        val json = row(buildJsonObject { put("status", "completed"); put("text", "{\"result\":1,\"count\":2}") })
        assertEquals(2, project(listOf(json)).resultFields)
    }
    @Test fun identityAndPersistedSnippetArePreservedWithoutChangingTheStore() {
        val result = project(emptyList())
        assertEquals("我", result.sender); assertTrue(result.self); assertFalse(result.agent); assertEquals(star.sender, result.senderId)
        assertEquals(star.text, result.text); assertEquals("今天", result.time)
    }
}

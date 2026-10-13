package dev.atoll.app.compose

import dev.atoll.app.core.Envelope
import dev.atoll.app.core.Row
import dev.atoll.app.model.ReplicaSnapshot
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RecentAgentTest {
    private val self = "human:self"
    private val a = ComposerActor("agent:a", "A", "agent")
    private val b = ComposerActor("agent:b", "B", "agent")
    private val person = ComposerActor("human:p", "P", "human")
    private val roster = listOf(a, b, person)
    private fun row(seq: Long, targets: List<String> = listOf(a.id), sender: String = self,
        kind: String = "request", type: String = "agent.ask", ts: Long = seq) = Row("c", seq, Envelope(buildJsonObject {
        put("id", "id:$seq"); put("channel_id", "c"); put("kind", kind); put("type", type); put("ts", ts)
        put("sender", buildJsonObject { put("id", sender) }); put("audience", JsonArray(targets.map(::JsonPrimitive)))
    }))
    // The visible timeline may be filtered/empty. Selection must still use the raw cached rows.
    private fun source(vararg rows: Row) = ReplicaSnapshot("c", 1, 1, rows.maxOfOrNull { it.seq } ?: 0,
        rows.toList(), emptyList(), emptyList(), rows.associate { it.envelope.id to it.envelope })
    @Test fun choosesHighestSequenceInUnfilteredCacheRatherThanIterationOrTimestampOrder() {
        val cache = source(row(10, listOf(b.id), ts = 1), row(2, listOf(a.id), ts = 99999))
        assertEquals(b.id, recentAgentId(cache, self, roster))
        assertEquals("recent", ComposerDelivery.resolve(ComposerDraft(), roster, recentAgentId = recentAgentId(cache, self, roster)).source)
    }
    @Test fun onlyOwnSingleTargetAgentAsksCountAsConversationEvidence() {
        val cache = source(row(1), row(2, listOf(b.id), sender = "human:other"), row(3, listOf(b.id), kind = "response"),
            row(4, listOf(b.id), type = "agent.queue"), row(5, listOf(a.id, b.id)), row(6, listOf(person.id)), row(7, emptyList()))
        assertEquals(a.id, recentAgentId(cache, self, roster))
    }
    @Test fun removedAgentsDoNotEraseEarlierValidInteractionAndAccountIdentityIsExact() {
        val cache = source(row(1), row(2, listOf(b.id)))
        assertEquals(a.id, recentAgentId(cache, self, listOf(a, person)))
        assertEquals("", recentAgentId(cache, "human:new-incarnation", roster))
        assertEquals("", recentAgentId(cache, "", roster))
        assertEquals("", recentAgentId(cache, self, listOf(person)))
    }
    @Test fun controlsFiltersAndRepliesRetainTheirExistingDeliveryPriority() {
        val recent = recentAgentId(source(row(1, listOf(b.id))), self, roster)
        assertEquals(a.id, ComposerDelivery.resolve(ComposerDraft(), roster, setOf(a.id), recent).recipients.single().id)
        assertEquals(a.id, ComposerDelivery.resolve(ComposerDraft(manualAgentId = a.id), roster, recentAgentId = recent).recipients.single().id)
        assertEquals(a.id, ComposerDelivery.resolve(ComposerDraft(recipients = listOf(a)), roster, recentAgentId = recent).recipients.single().id)
        assertEquals(person.id, ComposerDelivery.resolve(ComposerDraft(reply = ReplyTarget("message", person.id, person.name)), roster, recentAgentId = recent).recipients.single().id)
    }
    @Test fun emptyCacheFallsBackToTheOnlyAgentInsteadOfInventingARecentTarget() {
        val recent = recentAgentId(source(), self, roster)
        assertEquals("", recent)
        assertEquals("only", ComposerDelivery.resolve(ComposerDraft(), listOf(a, person), recentAgentId = recent).source)
        assertFalse(ComposerDelivery.resolve(ComposerDraft(), roster, recentAgentId = recent).valid)
    }
}

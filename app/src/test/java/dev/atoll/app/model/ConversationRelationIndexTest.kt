package dev.atoll.app.model

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationRelationIndexTest {
    @Test fun incrementalRelationMatchesWebRelationForShuffledAndPartialLedger() {
        val random = Random(345)
        repeat(100) {
            val replica = ChannelReplica(CHANNEL)
            val index = ConversationRelationIndex(SELF)
            val rows = (1L..30L).map { seq -> row(seq, "r$seq", kind = if (random.nextBoolean()) "event" else "request", type = listOf("agent.ask", "tool.run", "system.member.list", "terminal.command")[random.nextInt(4)], parent = if (random.nextBoolean()) "r${random.nextInt(35)}" else "", correlation = "conversation-${random.nextInt(5)}", sender = listOf(SELF, AGENT, "human:other:1", "tool:x:1")[random.nextInt(4)], audience = listOf(listOf(SELF), listOf(AGENT), listOf("system"), emptyList())[random.nextInt(4)], canonical = random.nextBoolean()) }.shuffled(random)
            for (row in rows) {
                replica.add(row)
                index.admit(row.envelope)
                assertEquals(ConversationPresentation.relatedEnvelopeIds(replica.snapshot(), SELF), index.ids)
            }
        }
    }
}

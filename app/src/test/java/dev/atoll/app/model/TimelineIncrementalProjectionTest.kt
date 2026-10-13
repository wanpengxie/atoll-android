package dev.atoll.app.model

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineIncrementalProjectionTest {
    @Test fun cachedIncrementalProjectionMatchesFullSemanticProjectionAfterEachArrival() {
        val random = Random(789)
        repeat(30) { test ->
            val replica = ChannelReplica(CHANNEL, SELF)
            val rows = (1L..30L).map { seq ->
                val parent = if (random.nextBoolean()) "r${random.nextInt(35)}" else ""
                val kind = if (random.nextBoolean()) "request" else "response"
                row(seq, "r$seq", kind = kind, type = listOf("agent.ask", "tool.run", "ui.open", "system.member.list")[random.nextInt(4)], parent = parent,
                    correlation = "conversation-${random.nextInt(5)}", sender = listOf(SELF, AGENT, "human:other:1")[random.nextInt(3)],
                    audience = listOf(listOf(SELF), listOf(AGENT), emptyList())[random.nextInt(3)],
                    body = if (kind == "response") body("status" to listOf("queued", "processing", "completed", "failed")[random.nextInt(4)]) else body("text" to "request $seq"))
            }.shuffled(random)
            for ((index, row) in rows.withIndex()) {
                replica.add(row)
                if (index == 20 && test % 3 == 0) replica.trim(15, false)
                val options = TimelineOptions(selfId = SELF, scope = if (test % 2 == 0) ConversationScope.MINE else ConversationScope.ALL, now = 1)
                val incremental = replica.timeline(options)
                val reference = ConversationPresentation.project(replica.snapshot(), options)
                assertEquals("test $test arrival $index", reference.items.map { it.entry }, incremental.items.map { it.entry })
                assertEquals(reference.waitingTurns, incremental.waitingTurns)
                assertEquals(reference.runningTasks, incremental.runningTasks)
            }
        }
    }
}

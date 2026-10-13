package dev.atoll.app.app

import dev.atoll.app.core.*
import dev.atoll.app.model.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProcessLifecycleTest {
    private fun target(deadline: Long = 0): TimelineMessageTarget {
        val request = Envelope(buildJsonObject {
            put("id", "q"); put("kind", "request"); put("type", "agent.ask"); put("expires_at", deadline)
            put("audience", buildJsonArray { add("agent:a") })
            put("payload", buildJsonObject { put("body", buildJsonObject { put("text", "work") }) })
        })
        val processing = Envelope(buildJsonObject {
            put("id", "p"); put("kind", "response"); put("parent_id", "q")
            put("payload", buildJsonObject { put("body", buildJsonObject { put("status", "processing") }) })
        })
        return TimelineMessageTarget(request, Turn(request, 1, listOf(Row("c", 2, processing))))
    }

    @Test fun completedRestartMarksTheProcessLostUsingCanonicalFacts() {
        val target = target()
        val restartRequest = Envelope(buildJsonObject {
            put("id", "restart"); put("kind", "request"); put("type", "system.member.restart")
            put("payload", buildJsonObject { put("body", buildJsonObject { put("member", "agent:a") }) })
        })
        val terminal = Envelope(buildJsonObject { put("id", "done"); put("kind", "response")
            put("payload", buildJsonObject { put("body", buildJsonObject { put("status", "completed") }) }) })
        val entry = TimelineEntry("turn", 3, Turn(restartRequest, 3, terminal = terminal, terminalSeq = 4))
        val source = ReplicaSnapshot("c", 1, 1, 4, emptyList(), listOf(entry), emptyList(), emptyMap())
        assertEquals("未完成 · Agent 已重启", processPanel(target, source, now = 100).status)
        val finished = target.copy(turn = target.turn!!.copy(terminal = terminal, terminalSeq = 5))
        assertEquals("completed", processPanel(finished, source, now = 100).status)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun expiryRefreshesAnOpenPanelWithoutAnotherLedgerRowOrARepeatingTick() = runTest {
        val target = target(1001)
        val states = processPanelUpdates(target, null, false, now = { 1000 + testScheduler.currentTime }).toList()
        assertEquals(listOf("processing", "未完成 · 已过期"), states.map { it.status })
        assertEquals(1L, testScheduler.currentTime)
        assertEquals("终态未加载", processPanel(target, newerUnloaded = true, now = 1002).status)
    }
}

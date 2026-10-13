package dev.atoll.app.app

import dev.atoll.app.core.*
import dev.atoll.app.model.*
import dev.atoll.app.governance.ChannelCreation
import dev.atoll.app.tasks.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ActivityProjectionTest {
    @Test fun retainedWindowFactsKeepBackgroundApprovalsWithoutRetainingTheTurnTree() {
        val replica = ChannelReplica("c", "human:self")
        replica.apply(RowBatch("c", listOf(Row("c", 1, Envelope(buildJsonObject {
            put("id", "approval"); put("kind", "request"); put("type", "human.approve"); put("channel_id", "c")
            put("audience", buildJsonArray { add("human:self") }); put("sender", buildJsonObject { put("id", "agent:a") })
            put("payload", buildJsonObject { put("body", buildJsonObject { put("text", "Ship this change?") }) })
        }))), Origin.CACHE))
        val facts = activityFacts(replica.snapshot(), "human:self", 1)
        assertEquals("Ship this change?", facts.single().title)
        assertTrue(facts.single().needsYou)
        assertNull(facts.single().turn)
        val activity = selectActivity(listOf(ChannelActivityInput("c", "channel", facts)), listOf(ActivityAgentRun("c", "run:id", "agent:a")))
        assertEquals("approval:c:approval", activity.activities.single().source["workItemKey"]!!.string())
        assertEquals(AppRoute("c", "conversation", "turn:run:id"), AppRoute.parse(taskSourceLink(activity.operations.single().source)))
    }
    @Test fun creationOperationNavigatesToItsRequestUntilAllReadinessFactsAreConfirmed() {
        val initial = ChannelCreation("req", "parent", "child", 123, accepted = true)
        assertEquals(AppRoute("parent", "conversation", "turn:req"), AppRoute.parse(taskSourceLink(creationActivity(initial).source)))
        assertEquals("uncertain", creationActivity(initial.copy(uncertain = true)).state)
        val ready = initial.copy(ledger = true, targetId = "child:id", observable = true, membership = true, serving = true)
        assertEquals("completed", creationActivity(ready).state)
        assertEquals(AppRoute("child:id", "conversation"), AppRoute.parse(taskSourceLink(creationActivity(ready).source)))
    }
}

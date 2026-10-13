package dev.atoll.app.tasks

import dev.atoll.app.compose.*
import dev.atoll.app.core.*
import dev.atoll.app.model.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Ports web feature-task-{facts,compact-closure,filter}.test.js and feature-tasks.test.js. */
class TaskFactsTest {
    private val channel = "c1"
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun envelope(id: String, type: String = "agent.ask", body: JsonObject = json("""{"text":"do work"}"""), kind: String = "request", parent: String = "", audience: List<String> = listOf("agent"), sender: String = "me", ts: Long = 100, expires: Long = 0) = Envelope(buildJsonObject {
        put("id",id);put("type",type);put("channel_id",channel);put("kind",kind);put("parent_id",parent);put("ts",ts);put("expires_at",expires)
        put("sender",buildJsonObject { put("id",sender);put("kind",if (sender == "me") "human" else "agent") });put("audience",JsonArray(audience.map(::JsonPrimitive)))
        put("payload",buildJsonObject { put("body",body) });put("visibility","public")
    })
    private fun add(replica: ChannelReplica, seq: Long, env: Envelope) { replica.apply(RowBatch(channel,listOf(Row(channel,seq,env)),Origin.LIVE)) }
    private fun pending(id: String, state: SendState = SendState.UNCERTAIN, frame: JsonObject = json("""{"msg_type":"agent.ask","audience":["agent"]}""")) = LocalSubmission(id,channel,frame,"重要提交","Agent",state,1,2)
    @Test fun tc1495CombinesLedgerTasksApprovalsRunsRecoveryAndLocalTimersWithNativeIdentity() {
        val replica = ChannelReplica(channel,"me")
        add(replica,1,envelope("approval","human.approve",json("""{"title":"发布生产","impact":"线上流量"}"""),audience=listOf("me"),sender="agent",expires=9999))
        add(replica,2,envelope("run",body=json("""{"text":"整理报告"}""")))
        add(replica,3,envelope("task-request","task.create",json("""{"title":"跟进报告","source":{"channelId":"c1","objectId":"run","seq":2}}""")))
        add(replica,4,envelope("task-response","task.create",json("""{"status":"completed","value":{"task_id":"task-7","status":"active","title":"跟进报告"}}"""),"response","task-request",sender="agent",ts=200))
        val facts = selectTaskFacts(replica.snapshot(),selfId="me",now=100,pending=listOf(pending("retry-1")),automationRecords=listOf(AutomationRecord("timer-1",channel,1000,"agent.ask",json("""{"text":"周报提醒"}"""),1)))
        assertEquals(setOf("approval","agent_run","task","recovery","automation"),facts.map { it.kind }.toSet())
        val task = facts.first { it.kind == "task" };assertEquals("task:c1:task-7",task.key);assertEquals("task-7",task.nativeId);assertEquals("ledger",task.provenance);assertEquals("run",task.source.text("objectId"))
        val timer = facts.first { it.kind == "automation" };assertEquals("waiting",timer.state);assertEquals("this_device",timer.localScope);assertEquals(listOf(TaskAction.CANCEL_TIMER),timer.actions)
        val recovery = facts.first { it.kind == "recovery" };assertEquals("uncertain",recovery.state);assertTrue(recovery.actionableBySelf)
    }
    @Test fun tc1497CompactClosureCannotInventStableTaskIdOrACompletedReadableResult() {
        val replica = ChannelReplica(channel,"me")
        add(replica,2,envelope("task-response","task.create",json("""{"status":"completed","value":{"task_id":"task-7","status":"active"}}"""),"response","task-request",sender="agent",ts=200))
        for (seq in 3L..10L) add(replica,seq,envelope("note-$seq","human.note",kind="event",ts=seq))
        assertTrue(replica.trim(4,false) > 0)
        add(replica,1,envelope("task-request","task.create",json("""{"title":"跟进报告"}""")))
        val fact = selectTaskFacts(replica.snapshot(),selfId="me").single()
        assertEquals("agent_run:c1:task-request",fact.key);assertEquals("uncertain",fact.state);assertEquals("跟进报告",fact.title)
        assertEquals("终态详情不可用，请刷新或重新进入频道",fact.waitingFor);assertFalse(fact.actionableBySelf)
        assertEquals(JsonPrimitive(true),fact.diagnostic["resultUnavailable"]);assertEquals("unavailable",fact.diagnostic.text("resultPhase"))
    }
    @Test fun tc1498FiltersResponsibilityStateKindAndKeepsAutomationSeparate() {
        val items = listOf(TaskFact("a","a",channel,"approval","审批","waiting",assigneeActorIds=listOf("me"),actionableBySelf=true,priority="high",updatedAt=1),
            TaskFact("b","b",channel,"automation","自动动作","waiting",actionableBySelf=true,updatedAt=2),TaskFact("c","c",channel,"agent_run","完成","completed",assigneeActorIds=listOf("agent"),updatedAt=3))
        assertEquals(listOf("a","b"),filterTasks(items,selfId="me").map { it.key })
        assertEquals(listOf("c"),filterTasks(items,scope="all",status="completed",selfId="me").map { it.key })
        assertEquals("automation",taskGroup(items[1]));assertEquals("history",taskGroup(items[2]))
        assertEquals(listOf("a"),filterTasks(items,kind="approval",selfId="me").map { it.key })
    }
    @Test fun waitingOnlyUsesExplicitLedgerControlsAndBuildsTypedSteerInterruptPayloads() {
        val replica = ChannelReplica(channel,"me");add(replica,1,envelope("request-1"))
        add(replica,2,envelope("progress",body=json("""{"status":"processing","controls":[{"word":"agent.steer"},{"word":"agent.interrupt"}]}"""),kind="response",parent="request-1",sender="agent"))
        val authority=json("""{"current":true,"actorIDs":["agent"]}""")
        val item=selectWaitingFacts(replica.snapshot(),targetAuthority=authority).single()
        assertEquals(listOf("agent.steer","agent.interrupt"),item.actions)
        val steer=waitingControlSubmission(item,"agent.steer");assertEquals(json("""{"target":"request-1"}"""),steer.payload)
        assertEquals(json("""{"all":true}"""),waitingControlSubmission(item,"agent.steer",all=true).payload)
        assertEquals(Envelope.EMPTY,waitingControlSubmission(item,"agent.interrupt").payload)
        assertEquals(authority,steer.controlContext!!["targetAuthority"])
        assertEquals("agent",steer.controlContext.objectAt("turn").text("actorId"))
    }
    @Test fun unknownWaitingControlDoesNotGuessSchemaAndTerminalRemovesWaitingFact() {
        val replica=ChannelReplica(channel,"me");add(replica,1,envelope("r"));add(replica,2,envelope("p",body=json("""{"status":"queued","controls":[{"word":"agent.future"}]}"""),kind="response",parent="r",sender="agent"))
        val fact=selectWaitingFacts(replica.snapshot()).single();assertEquals(listOf("agent.future"),fact.actions)
        try { waitingControlSubmission(fact,"agent.future");fail("must reject unknown schema") } catch (e:IllegalArgumentException) { assertEquals("该控制词尚无可靠的请求构造器",e.message) }
        add(replica,3,envelope("done",body=json("""{"status":"completed"}"""),kind="response",parent="r",sender="agent"))
        assertTrue(selectWaitingFacts(replica.snapshot()).isEmpty())
    }
    @Test fun newestDeclaredControlFactWinsAndLocalEchoDisappearsOnceCanonicalRequestLands() {
        val replica=ChannelReplica(channel,"me");add(replica,1,envelope("r"))
        add(replica,4,envelope("new",body=json("""{"status":"processing","controls":[{"word":"agent.interrupt"}]}"""),kind="response",parent="r",sender="agent"))
        add(replica,2,envelope("old",body=json("""{"status":"queued","controls":[{"word":"agent.steer"}]}"""),kind="response",parent="r",sender="agent"))
        assertEquals(listOf("agent.interrupt"),selectWaitingFacts(replica.snapshot(),pending=listOf(pending("r"))).single().actions)
        assertTrue(selectTaskFacts(replica.snapshot(),pending=listOf(pending("r"))).none { it.kind=="recovery" })
        val queued=pending("local",SendState.QUEUED)
        assertEquals("local",selectWaitingFacts(replica.snapshot(),pending=listOf(queued)).first().id)
    }
    @Test fun expiryActionsControlRequestsAndForeignChannelsAreHandledWithoutInventingActions() {
        val replica=ChannelReplica(channel,"me");add(replica,1,envelope("a","human.ask",audience=listOf("me"),expires=100));add(replica,2,envelope("control","agent.select"))
        val pendingOther=pending("other").copy(channelId="other")
        val facts=selectTaskFacts(replica.snapshot(),selfId="me",now=100,pending=listOf(pendingOther),automationRecords=listOf(AutomationRecord("t","other",5,"wake",createdAt=1)))
        assertEquals(1,facts.size);assertEquals("expired",facts[0].state);assertFalse(facts[0].needsYou);assertTrue(facts[0].actions.isEmpty())
    }
    @Test fun boundedPagesPreserveWebWindowAndClampAfterTheDatasetShrinks() {
        val items=(0 until 250).map { TaskFact("$it","$it",channel,"agent_run","$it") }
        val first=taskPage(items);assertEquals(130,first.start);assertEquals(250,first.end);assertTrue(first.hasOlder);assertFalse(first.hasNewer)
        val oldest=taskPage(items,99);assertEquals(2,oldest.page);assertEquals(10,oldest.end);assertFalse(oldest.hasOlder);assertTrue(oldest.hasNewer)
        assertEquals(0,taskPage(items.take(2),99).page);assertEquals(1,taskPage(emptyList()).pageCount)
    }
    @Test fun creationAndProvidersKeepStableAuthorshipSourceAndDeclaredCapabilities() {
        val submission=createTaskSubmission(" c1 "," agent ","  推进任务  ","  note ",source=json("""{"channelId":"other","objectId":"r"}"""))
        assertEquals("task.create",submission.msgType);assertEquals(listOf("agent"),submission.audience);assertEquals("推进任务",submission.payload.text("title"));assertEquals("c1",submission.payload.objectAt("source").text("channelId"))
        assertEquals(listOf(TaskProvider("a","Agent")),selectTaskProviders(mapOf("a" to setOf("task.create"),"missing" to setOf("task.create"),"b" to setOf("agent.ask")),mapOf("a" to "Agent","b" to "Other")))
        try { createTaskSubmission(channel,"a"," ");fail("title required") } catch (_: IllegalArgumentException) { }
    }
    @Test fun activityUsesOnlyProvidedVisibleChannelsAndKeepsNativeSourceAnchors() {
        val fact=TaskFact("agent_run:c:r","r","c","agent_run","Run",source=json("""{"channelId":"c","requestId":"r"}"""),updatedAt=10)
        val result=selectActivity(listOf(ChannelActivityInput("c","频道",listOf(fact))),agentRuns=listOf(ActivityAgentRun("c","r","Agent",updatedAt=15),ActivityAgentRun("hidden","x","Other")),operations=listOf(ActivityOperation("create","c","创建频道","completed",updatedAt=20)),connected=false)
        assertEquals("activity:agent_run:c:r",result.activities.single().key);assertEquals(fact.key,result.activities.single().source.text("workItemKey"))
        assertEquals(listOf("create","operation:c:r"),result.operations.map { it.key });assertTrue(result.operationsUnavailable)
    }
}

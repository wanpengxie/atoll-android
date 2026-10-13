package dev.atoll.app.tasks

import dev.atoll.app.app.WorkspaceServices
import dev.atoll.app.compose.*
import dev.atoll.app.core.*
import dev.atoll.app.model.ChannelReplica
import dev.atoll.app.net.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class,DelicateCoroutinesApi::class)
class TaskCommandsTest {
    private fun obj(text: String)=Json.parseToJsonElement(text).jsonObject
    private fun services(wire: WireClient)=WorkspaceServices("http://localhost","owner",mapOf("c" to "me"),wire,ObsClient("http://localhost",OkHttpClient()),OkHttpClient())
    private fun event(id: String,type: String="agent.ask",sender: String="me",channel: String="c")=Envelope(buildJsonObject {
        put("id","timer:$id");put("correlation_id","timer:$id");put("audience",JsonArray(listOf(JsonPrimitive(sender))));put("channel_id",channel);put("kind","event");put("type",type);put("ts",10);put("sender",buildJsonObject { put("id",sender);put("kind",if (sender=="me") "human" else sender) });put("payload",buildJsonObject { put("body",buildJsonObject {}) })
    })
    @Test fun nestedHumanRequestsKeepActionableApprovalFactsAndResolveTheirOwnRequestId()=runBlocking<Unit> {
        newSingleThreadContext("nested-approval-test").use { dispatcher -> withContext(dispatcher) {
            for(type in listOf("human.ask","human.approve")) {
                val wire=TaskTestWire();val vm=TasksViewModel(services(wire),"c",dispatcher=dispatcher)
                val replica=ChannelReplica("c","me")
                fun row(seq: Long,id: String,kind: String,msgType: String,parent: String,sender: String,audience: String,body: JsonObject)=Row("c",seq,Envelope(buildJsonObject {
                    put("id",id);put("kind",kind);put("type",msgType);put("channel_id","c");put("parent_id",parent)
                    put("sender",buildJsonObject { put("id",sender);put("kind",if(sender=="me") "human" else "agent") })
                    put("audience",buildJsonArray { add(audience) });put("payload",buildJsonObject { put("body",body) })
                }))
                replica.apply(RowBatch("c",listOf(
                    row(1,"root","request","agent.ask","","me","agent:a",obj("""{"text":"Work"}""")),
                    row(2,"progress","response","agent.ask","root","agent:a","me",obj("""{"status":"processing"}""")),
                    row(3,"approval","request",type,"root","agent:a","me",obj("""{"title":"Please decide","detail":"Details"}"""))
                ),Origin.LIVE))
                try {
                    vm.update(replica.snapshot(),selfId="me",writable=true)
                    val fact=withTimeout(5000) { vm.state.first { it.items.any { item -> item.id=="approval" } }.items.single { it.id=="approval" } }
                    assertTrue(fact.actionableBySelf);assertTrue(TaskAction.APPROVE in fact.actions)
                    if(type=="human.approve") assertTrue(TaskAction.REJECT in fact.actions)
                    vm.execute(fact,TaskAction.APPROVE,note="Reviewed",text="Answer")
                    val payload=wire.requests.single { it.first==Up.RESOLVE }.second
                    assertEquals("approval",payload.text("req_id"))
                    assertEquals(if(type=="human.ask") "Answer" else "approve",payload.text(if(type=="human.ask") "text" else "decision"))
                } finally { vm.close() }
            }
        } }
    }
    @Test fun timersFollowExactFeedIdentityAndSurviveChannelModelsOnlyInTheSameSession()=runBlocking<Unit> {
        newSingleThreadContext("timer-test").use { dispatcher -> withContext(dispatcher) {
            val wire=TaskTestWire();val session=AutomationSession("");val vm=TasksViewModel(services(wire),"c",automation=session,now={100},dispatcher=dispatcher)
            vm.update(ChannelReplica("c").snapshot(),selfId="me",writable=true)
            try {
                val id=vm.schedule(5,"agent.ask",obj("""{"text":"timer"}"""));assertEquals("uuid-1",id)
                withTimeout(5000) { vm.state.first { it.timers.isNotEmpty() } }
                assertEquals("scheduled",vm.state.value.timers.single().state)
                wire.frames.emit(DownFrame.Feed("",Row("c",1,event("another-timer"))))
                wire.frames.emit(DownFrame.Feed("",Row("c",2,event(id,sender="agent"))))
                wire.frames.emit(DownFrame.Feed("",Row("c",4,Envelope(obj("""{"id":"uuid-1","channel_id":"c","kind":"event","type":"agent.ask","sender":{"kind":"system","id":"system"},"payload":{"body":{}}}""")))))
                yield();assertEquals("scheduled",vm.state.value.timers.single().state)
                wire.frames.emit(DownFrame.Feed("",Row("c",3,event(id))))
                withTimeout(5000) { vm.state.first { it.timers.singleOrNull()?.state=="fired" } }
                vm.cancelTimer(id);yield();assertEquals("fired",vm.state.value.timers.single().state)
                assertEquals(Up.AFTER,wire.requests.first().first);assertEquals(JsonPrimitive(5),wire.requests.first().second["duration_ms"])
                vm.close()
                val restored=TasksViewModel(services(wire),"c",automation=session,dispatcher=dispatcher)
                try { withTimeout(5000) { restored.state.first { it.timers.isNotEmpty() } };assertEquals("fired",restored.state.value.timers.single().state) } finally { restored.close() }
            } finally { vm.close() }
        } }
    }
    @Test fun immediateTimerFeedBeforeAfterReceiptIsJoinedAndDisconnectBlocksScheduling()=runBlocking<Unit> {
        newSingleThreadContext("timer-race-test").use { dispatcher -> withContext(dispatcher) {
            val wire=TaskTestWire();wire.handle={ type,_ -> if (type==Up.AFTER) wire.frames.emit(DownFrame.Feed("",Row("c",1,event("early"))));obj("""{"timer_id":"early"}""") }
            val vm=TasksViewModel(services(wire),"c",dispatcher=dispatcher)
            try {
                vm.update(ChannelReplica("c").snapshot(),selfId="me",writable=true)
                vm.schedule(1,"agent.ask",Envelope.EMPTY)
                withTimeout(5000) { vm.state.first { it.timers.singleOrNull()?.state=="fired" } }
                wire.state.value=WireState.Disconnected("down",0)
                try { vm.schedule(1,"agent.ask",Envelope.EMPTY);fail("offline schedule") } catch (_:IllegalStateException) { }
                assertEquals(1,wire.requests.size)
            } finally { vm.close() }
        } }
    }
    @Test fun currentNodeHumanTimerFireUsesTimerPrefixAndSelfAuthorship()=runBlocking<Unit> {
        newSingleThreadContext("timer-contract-test").use { dispatcher -> withContext(dispatcher) {
            val wire=TaskTestWire();wire.handle={ _,_ -> obj("""{"timer_id":"uuid-1"}""") }
            val vm=TasksViewModel(services(wire),"c",dispatcher=dispatcher)
            try {
                vm.update(ChannelReplica("c").snapshot(),selfId="me",writable=true)
                vm.schedule(100,"human.note",Envelope.EMPTY)
                val fired=Envelope(obj("""{"id":"timer:uuid-1","correlation_id":"timer:uuid-1","channel_id":"c","kind":"event","type":"human.note","sender":{"kind":"human","id":"me"},"audience":["me"],"payload":{"body":{}}}"""))
                wire.frames.emit(DownFrame.Feed("",Row("c",3,fired)))
                withTimeout(5000) { vm.state.first { it.timers.singleOrNull()?.state=="fired" } }
                assertEquals("uuid-1",vm.state.value.timers.single().timerId)
            } finally { vm.close() }
        } }
    }
    @Test fun uncertainLocalCancellationUsesDurableOwnerEvenWhileOffline()=runBlocking<Unit> {
        newSingleThreadContext("task-cancel-test").use { dispatcher -> withContext(dispatcher) {
            val wire=TaskTestWire();wire.state.value=WireState.Disconnected("offline",0)
            val row=LocalSubmission("uncertain","c",obj("""{"msg_type":"agent.ask","audience":["agent"]}"""),"pending","Agent",SendState.UNCERTAIN,1,2)
            val cancelled=mutableListOf<String>()
            val owner=object:TaskSubmissionOwner {
                override val submissions=MutableStateFlow(listOf(row))
                override suspend fun submitFrame(frame: JsonObject,text: String,targetLabel: String)="unused"
                override fun retry(id: String)=Unit
                override fun cancel(id: String) { cancelled.add(id) }
            }
            val vm=TasksViewModel(services(wire),"c",submissionOwner=owner,dispatcher=dispatcher)
            try {
                vm.update(ChannelReplica("c").snapshot(),selfId="me",writable=false)
                val fact=withTimeout(5000) { vm.state.first { it.items.isNotEmpty() }.items.single() }
                vm.execute(fact,TaskAction.CANCEL)
                assertEquals(listOf("uncertain"),cancelled);assertTrue(wire.requests.isEmpty())
            } finally { vm.close() }
        } }
    }
    @Test fun formalTaskCreationUsesDeclaredProviderAndSameDurableOwnerWithoutTouchingDrafts()=runBlocking<Unit> {
        newSingleThreadContext("task-owner-test").use { dispatcher -> withContext(dispatcher) {
            val wire=TaskTestWire();val submitted=mutableListOf<JsonObject>()
            val owner=object:TaskSubmissionOwner {
                override val submissions=MutableStateFlow<List<LocalSubmission>>(emptyList())
                override suspend fun submitFrame(frame: JsonObject,text: String,targetLabel: String): String { submitted.add(frame);assertEquals("标题",text);assertEquals("Executor",targetLabel);return "stable-id" }
                override fun retry(id: String)=Unit;override fun cancel(id: String)=Unit
            }
            val vm=TasksViewModel(services(wire),"c",submissionOwner=owner,dispatcher=dispatcher)
            try {
                vm.update(ChannelReplica("c").snapshot(),selfId="me",roster=listOf(TaskActor("agent","Executor")),writable=true)
                withTimeout(5000) { vm.state.first { it.creationReady } }
                assertEquals("stable-id",vm.createTask("agent","标题","说明"))
                assertEquals("task.create",submitted.single().text("msg_type"));assertEquals("request",submitted.single().text("kind"));assertFalse(submitted.single().containsKey("id"))
                assertEquals("标题",submitted.single().objectAt("payload").text("title"))
            } finally { vm.close() }
            val missing=TasksViewModel(services(wire),"c",dispatcher=dispatcher)
            try { missing.update(ChannelReplica("c").snapshot(),selfId="me",writable=true);try { missing.createTask("agent","标题");fail("must use owner") } catch (_:IllegalStateException) { } } finally { missing.close() }
        } }
    }
}

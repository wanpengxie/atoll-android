package dev.atoll.app.tasks

import android.content.Context
import androidx.lifecycle.ViewModel
import dev.atoll.app.app.WorkspaceServices
import dev.atoll.app.compose.LocalSubmission
import dev.atoll.app.compose.SendState
import dev.atoll.app.core.*
import dev.atoll.app.model.*
import dev.atoll.app.net.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.util.UUID

/** Adapter over the existing composer owner; creating tasks must not bypass its durable outbox. */
interface TaskSubmissionOwner {
    val submissions: StateFlow<List<LocalSubmission>>
    suspend fun submitFrame(frame: JsonObject, text: String, targetLabel: String): String
    fun retry(id: String)
    fun cancel(id: String)
}
data class TaskActor(val id: String, val name: String, val kind: String = "agent")
data class TaskCommandState(val state: String = "ready", val reason: String = "", val error: String = "")
data class TasksState(
    val items: List<TaskFact> = emptyList(), val waiting: List<WaitingFact> = emptyList(), val providers: List<TaskProvider> = emptyList(),
    val actorNames: Map<String,String> = emptyMap(), val selfId: String = "", val writable: Boolean = false, val connected: Boolean = false,
    val creationReady: Boolean = false, val commandStates: Map<String,TaskCommandState> = emptyMap(), val error: String = "",
    val timers: List<AutomationRecord> = emptyList(), val selectedKey: String = "", val source: JsonObject? = null, val creating: Boolean = false,
) { val selectedItem: TaskFact? get() = items.firstOrNull { it.key == selectedKey } }
private data class TaskInputs(val snapshot: TimelineSnapshot?, val source: TimelineSource?, val selfId: String, val roster: List<TaskActor>, val writable: Boolean)

@OptIn(ExperimentalCoroutinesApi::class)
class TasksViewModel(
    val services: WorkspaceServices,
    val channelId: String,
    snapshot: TimelineSnapshot? = null,
    private val store: KeyValueStore,
    private val submissionOwner: TaskSubmissionOwner? = null,
    private val now: ()->Long = System::currentTimeMillis,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : ViewModel(), AutoCloseable {
    private val scope=CoroutineScope(SupervisorJob()+dispatcher)
    private val inputs=MutableStateFlow(TaskInputs(snapshot,null,services.selfIds[channelId].orEmpty(),emptyList(),false))
    private val mutable=MutableStateFlow(TasksState())
    val state: StateFlow<TasksState> = mutable.asStateFlow()
    private val timerKey="tasks.timers:${services.server}:${services.principalId}:$channelId"
    private val timers=MutableStateFlow(runCatching { store.get(timerKey)?.let { Json.decodeFromString<List<AutomationRecord>>(it) } }.getOrNull().orEmpty())
    private val pending=submissionOwner?.submissions ?: MutableStateFlow(emptyList())
    private val manifests=MutableStateFlow<Map<String,Set<String>>>(emptyMap())
    private val probing=mutableSetOf<String>()
    private val probes=Semaphore(4)
    private val clock=MutableStateFlow(now())
    private var expiry: Job? = null
    private val firings=linkedMapOf<String,Envelope>()
    init {
        scope.launch { combine(inputs,pending,timers,manifests,services.wire.state) { input,local,automation,capabilities,connection ->
            ProjectionInput(input,local,automation,capabilities,connection is WireState.Attached,clock.value)
        }.combine(clock) { value,time -> value.copy(time=time) }.mapLatest { value -> withContext(Dispatchers.Default) { project(value) } }.collect { projection ->
            mutable.value=mutable.value.copy(items=projection.items,waiting=projection.waiting,providers=projection.providers,actorNames=projection.names,selfId=projection.selfId,writable=projection.writable,connected=projection.connected,
                creationReady=projection.writable && submissionOwner!=null && projection.providers.isNotEmpty(),timers=projection.timers)
        } }
        scope.launch(start=CoroutineStart.UNDISPATCHED) { services.wire.frames.collect { frame -> if (frame is DownFrame.Feed && frame.row.channelId==channelId) observeFiring(frame.row.envelope) } }
    }
    private data class ProjectionInput(val input: TaskInputs,val local: List<LocalSubmission>,val timers: List<AutomationRecord>,val capabilities: Map<String,Set<String>>,val connected: Boolean,val time: Long)
    private data class Projection(val items: List<TaskFact>,val waiting: List<WaitingFact>,val providers: List<TaskProvider>,val names: Map<String,String>,val selfId: String,val writable: Boolean,val connected: Boolean,val timers: List<AutomationRecord>)
    private fun project(value: ProjectionInput): Projection {
        val input=value.input;val write=input.writable && value.connected
        val turns=input.source?.let { timelineTurns(it.timeline) } ?: input.snapshot?.let { (timelineTurns(it.items.map { i -> i.entry })+it.waitingTurns).distinctBy { t -> t.requestId } }.orEmpty()
        val actionFacts=mutableMapOf<String,List<String>>()
        if (write) turns.forEach { turn ->
            if (turn.terminal==null && input.selfId in turn.request.audience && (turn.request.expiresAt<=0 || turn.request.expiresAt>value.time)) {
                when (turn.request.type) {
                    "human.approve" -> actionFacts[turn.requestId]=listOf(TaskAction.APPROVE,TaskAction.REJECT)
                    "human.ask" -> actionFacts[turn.requestId]=listOf(TaskAction.APPROVE)
                }
            }
            val control=turn.provisional.sortedByDescending { it.seq }.firstOrNull { it.envelope.status in setOf("queued","processing") && it.envelope.body["controls"] is JsonArray }
            if (turn.terminal==null && control?.envelope?.status=="queued" && turn.request.senderId==input.selfId) actionFacts[turn.requestId]=actionFacts[turn.requestId].orEmpty()+TaskAction.CANCEL
        }
        if (submissionOwner!=null) value.local.filter { it.channelId==channelId }.forEach { row ->
            if (row.state==SendState.REJECTED) actionFacts[row.messageId]=listOf(TaskAction.RETRY)
            else if (row.state in setOf(SendState.QUEUED,SendState.TRANSMITTING,SendState.ACCEPTED,SendState.DELAYED,SendState.UNCERTAIN)) actionFacts[row.messageId]=listOf(TaskAction.CANCEL)
        }
        val facts=selectTaskFacts(turns,channelId,input.selfId,value.time,value.local,value.timers,actionFacts)
        val authority=buildJsonObject { put("current",write);put("actorIDs",JsonArray(input.roster.map { JsonPrimitive(it.id) })) }
        val waiting=input.source?.let { selectWaitingFacts(it,value.local,actionFacts,authority) } ?: input.snapshot?.let { selectWaitingFacts(it,value.local,actionFacts,authority) }.orEmpty()
        val names=input.roster.associate { it.id to it.name }
        return Projection(facts,waiting,selectTaskProviders(value.capabilities,names),names,input.selfId,write,value.connected,value.timers)
    }
    /** Host updates this on its normal snapshot/roster publication; no extra history fetch. */
    fun update(snapshot: TimelineSnapshot, selfId: String = services.selfIds[channelId].orEmpty(), roster: List<TaskActor> = inputs.value.roster, writable: Boolean = inputs.value.writable) {
        require(snapshot.channelId==channelId)
        inputs.value=TaskInputs(snapshot,null,selfId,roster.toList(),writable);clock.value=now();scheduleExpiry();probeProviders()
        snapshot.items.forEach { it.entry.envelopes().forEach(::observeFiring) }
    }
    fun update(source: TimelineSource, selfId: String = services.selfIds[channelId].orEmpty(), roster: List<TaskActor> = inputs.value.roster, writable: Boolean = inputs.value.writable) {
        require(source.channelId==channelId)
        inputs.value=TaskInputs(null,source,selfId,roster.toList(),writable);clock.value=now();scheduleExpiry();probeProviders()
        source.rows.forEach { observeFiring(it.envelope) }
    }
    private fun scheduleExpiry() {
        expiry?.cancel()
        val turns=inputs.value.source?.let { timelineTurns(it.timeline) } ?: inputs.value.snapshot?.let { timelineTurns(it.items.map { i -> i.entry })+it.waitingTurns }.orEmpty()
        val next=turns.filter { it.terminal==null && it.request.type in setOf("human.approve","human.ask") && it.request.expiresAt>now() }.minOfOrNull { it.request.expiresAt } ?: return
        expiry=scope.launch { delay((next-now()).coerceAtLeast(1));clock.value=now();scheduleExpiry() }
    }
    fun refreshProviders() { manifests.value=emptyMap();probeProviders() }
    private fun probeProviders() {
        if (!inputs.value.writable || services.wire.state.value !is WireState.Attached) return
        inputs.value.roster.filter { it.kind in setOf("agent","tool") && it.id !in manifests.value && probing.add(it.id) }.forEach { actor -> scope.launch {
            try { probes.withPermit {
                val describe=services.wire.actorRequest(channelId,actor.id,"actor.describe",Envelope.EMPTY)
                val words=(describe["words"] as? JsonObject)?.keys ?: (describe["types"] as? JsonObject)?.keys ?: emptySet()
                manifests.value=manifests.value+(actor.id to words.toSet())
            } } catch (e:CancellationException) { throw e } catch (_:Exception) { /* Explicit refresh can retry an unavailable provider. */ }
            finally { probing.remove(actor.id) }
        } }
    }
    private fun persistTimers() { store.put(timerKey,Json.encodeToString(timers.value));clock.value=now() }
    private fun observeFiring(envelope: Envelope) {
        if (envelope.kind!="event") return
        val canonical=envelope.id.startsWith("timer:") && envelope.parentId.isEmpty() && envelope.correlationId==envelope.id &&
            envelope.senderKind in setOf("agent","human") && envelope.senderId.isNotEmpty() && envelope.audience==listOf(envelope.senderId)
        if (!canonical || envelope.senderId!=inputs.value.selfId) return
        firings[envelope.id]=envelope
        if (firings.size>128) firings.remove(firings.keys.first())
        val next=timers.value.map { row ->
            val current = canonical && envelope.id=="timer:${row.timerId}" && envelope.senderId==inputs.value.selfId
            if (row.state=="scheduled" && current) row.copy(state="fired",firedAt=envelope.ts.takeIf { it>0 } ?: now()) else row
        }
        if (next!=timers.value) { timers.value=next;persistTimers() }
    }
    suspend fun schedule(durationMs: Long, msgType: String, payload: JsonObject): String {
        requireWritable();require(durationMs>0) { "延迟必须是正整数毫秒" };require(msgType.isNotBlank()) { "消息类型不能为空" }
        val receipt=services.wire.request(Up.AFTER,buildJsonObject { put("channel_id",channelId);put("duration_ms",durationMs);put("msg_type",msgType.trim());put("payload",payload) })
        val id=receipt.text("timer_id").ifEmpty { receipt.text("timerId") };require(id.isNotEmpty()) { "服务端没有返回 timer_id" }
        timers.value=timers.value.filterNot { it.timerId==id }+AutomationRecord(id,channelId,durationMs,msgType.trim(),payload,now())
        firings["timer:$id"]?.let(::observeFiring);persistTimers();return id
    }
    suspend fun cancelTimer(id: String) {
        requireWritable();require(id.isNotBlank()) { "timer_id 不能为空" }
        services.wire.request(Up.CANCEL_TIMER,buildJsonObject { put("channel_id",channelId);put("timer_id",id) })
        timers.value=timers.value.map { if (it.timerId==id && it.state=="scheduled") it.copy(state="cancelled",cancelledAt=now()) else it };persistTimers()
    }
    suspend fun createTask(provider: String,title: String,description: String="",dueAt: String="",source: JsonObject?=mutable.value.source): String {
        requireWritable();check(submissionOwner!=null) { "持久提交队列尚未接入" }
        val record=mutable.value.providers.firstOrNull { it.actorId==provider } ?: throw IllegalArgumentException("执行者没有声明 task.create")
        val task=createTaskSubmission(channelId,provider,title,description,dueAt,source,record.name)
        return submissionOwner.submitFrame(buildJsonObject { put("channel_id",channelId);put("kind","request");put("msg_type",task.msgType);put("audience",JsonArray(task.audience.map(::JsonPrimitive)));put("visibility","public");put("payload",task.payload) },task.text,task.targetLabel)
    }
    private fun requireWritable() { check(inputs.value.writable && services.wire.state.value is WireState.Attached && inputs.value.selfId.isNotEmpty()) { "连接可用且频道可写时才能执行此操作" } }
    fun select(key: String) { mutable.value=mutable.value.copy(selectedKey=key) }
    fun creationSource(source: JsonObject?) { mutable.value=mutable.value.copy(source=source) }
    fun beginCreation(source: JsonObject?=null) { mutable.value=mutable.value.copy(source=source,creating=true) }
    fun endCreation() { mutable.value=mutable.value.copy(creating=false,source=null) }
    fun clearError() { mutable.value=mutable.value.copy(error="") }
    fun perform(block: suspend TasksViewModel.()->Unit): Job = scope.launch {
        try { block() } catch (e:CancellationException) { throw e } catch (e:Exception) { mutable.value=mutable.value.copy(error=e.message.orEmpty()) }
    }
    suspend fun execute(item: TaskFact,action: String,note: String="",text: String="") {
        require(action in item.actions) { "当前事实没有声明可用操作" }
        command("${item.key}:$action") {
            when (action) {
                TaskAction.APPROVE,TaskAction.REJECT -> { requireWritable();check(item.state=="waiting" && inputs.value.selfId in item.assigneeActorIds)
                    services.wire.request(Up.RESOLVE,approvalResolvePayload(item,if (action==TaskAction.APPROVE) "approve" else "reject",note,text)) }
                TaskAction.RETRY -> { check(item.submission?.state==SendState.REJECTED && submissionOwner!=null);submissionOwner.retry(item.submission.messageId) }
                TaskAction.CANCEL -> { if (item.submission!=null) { check(submissionOwner!=null);submissionOwner.cancel(item.submission.messageId) } else { requireWritable();services.wire.request(Up.CANCEL,buildJsonObject { put("channel_id",channelId);put("req_id",item.requestId.ifEmpty { item.id }) }) } }
                TaskAction.CANCEL_TIMER -> cancelTimer(item.nativeId)
                else -> throw IllegalArgumentException("该动作尚未接入命令端口")
            }
        }
    }
    suspend fun executeWaiting(item: WaitingFact,type: String,all: Boolean=false) {
        require(type in item.actions) { "该控制词未由当前账本声明" }
        command("${item.key}:$type") {
            if (type==TaskAction.CANCEL && item.submission!=null) { check(submissionOwner!=null);submissionOwner.cancel(item.id);return@command }
            requireWritable()
            if (type==TaskAction.CANCEL) { services.wire.request(Up.CANCEL,buildJsonObject { put("channel_id",channelId);put("req_id",item.id) });return@command }
            check(item.turn!=null && item.turn.terminal==null && item.actorId in inputs.value.roster.map { it.id }) { "正在核验收件人" }
            val record=waitingControlSubmission(item,type,all)
            services.wire.request(Up.SUBMIT,buildJsonObject {
                put("channel_id",channelId);put("id",UUID.randomUUID().toString());put("kind","request");put("msg_type",type);put("payload",record.payload);put("visibility","public");put("audience",JsonArray(record.audience.map(::JsonPrimitive)));put("expires_at_ms",now()+30_000)
            })
        }
    }
    private suspend fun command(key: String,block: suspend()->Unit) {
        if (mutable.value.commandStates[key]?.state in setOf("submitting","disabled")) return
        mutable.value=mutable.value.copy(commandStates=mutable.value.commandStates+(key to TaskCommandState("submitting")))
        try { block();mutable.value=mutable.value.copy(commandStates=mutable.value.commandStates+(key to TaskCommandState("disabled","操作已提交"))) }
        catch (e:CancellationException) { throw e }
        catch (e:Exception) { mutable.value=mutable.value.copy(commandStates=mutable.value.commandStates+(key to TaskCommandState("failed",error=e.message.orEmpty())),error=e.message.orEmpty());throw e }
    }
    override fun close() { scope.cancel() }
    override fun onCleared() { close() }
}
fun approvalResolvePayload(item: TaskFact,decision: String,note: String="",text: String=""): JsonObject = buildJsonObject {
    put("channel_id",item.channelId);put("req_id",item.id)
    when (item.turn?.request?.type ?: item.diagnostic.text("requestType")) {
        "human.ask" -> put("text",text)
        "human.approve" -> { require(decision in setOf("approve","reject"));put("decision",decision);if (note.isNotEmpty()) put("note",note) }
        else -> throw IllegalArgumentException("此请求不是可回答的 human.ask/human.approve")
    }
}
fun createTasksViewModel(context: Context,services: WorkspaceServices,channelId: String,snapshot: TimelineSnapshot?=null,owner: TaskSubmissionOwner?=null): TasksViewModel =
    TasksViewModel(services,channelId,snapshot,AndroidKeyValueStore(context.getSharedPreferences("tasks",Context.MODE_PRIVATE)),owner)

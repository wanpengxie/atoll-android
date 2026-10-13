package dev.atoll.app.tasks

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import dev.atoll.app.app.WorkspaceServices
import dev.atoll.app.compose.*
import dev.atoll.app.core.*
import dev.atoll.app.model.ChannelReplica
import dev.atoll.app.net.*
import dev.atoll.app.ui.theme.AtollTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import java.util.UUID

/** Offline fixture entry point only: exercises production screens without credentials or node writes. */
class TasksDemoActivity : ComponentActivity() {
    private lateinit var model: TasksDemoModel
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent);model.show(intent.getStringExtra("mode") ?: "tasks") }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState);enableEdgeToEdge()
        model=ViewModelProvider(this,object:ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T:ViewModel> create(modelClass: Class<T>): T=TasksDemoModel() as T
        })[TasksDemoModel::class.java]
        model.show(intent.getStringExtra("mode") ?: "tasks")
        setContent { AtollTheme { Surface(Modifier.fillMaxSize()) {
            val state by model.tasks.state.collectAsState()
            Column(Modifier.systemBarsPadding().imePadding()) {
                Text("c0.dev.web · 离线演示",Modifier.padding(horizontal=12.dp,vertical=8.dp),style=MaterialTheme.typography.titleSmall)
                Row { listOf("tasks" to "任务","activity" to "活动","form" to "表单").forEach { (mode,label) -> TextButton(onClick={ model.show(mode) }) { Text(label) } } }
                if (model.notice.isNotEmpty()) Text(model.notice,Modifier.padding(horizontal=12.dp),style=MaterialTheme.typography.bodySmall)
                if (model.mode=="activity") ActivityCenterScreen(selectActivity(listOf(ChannelActivityInput("demo","c0.dev.web",state.items)),listOf(ActivityAgentRun("demo","run","Codex",updatedAt=System.currentTimeMillis())),listOf(ActivityOperation("channel-create","demo","创建频道","completed","成员已就绪"))),Modifier.weight(1f),onSource={ model.notice="返回来源：${it.text("objectId")}" })
                else TasksScreen(model.tasks,Modifier.weight(1f),onSource={ model.notice="返回来源：${it.text("objectId")}" },onTurn={ model.notice="打开回合：$it" })
            }
            UiFormsHost(model.forms,mapOf("codex" to "Codex"),mapOf("demo" to "c0.dev.web"))
        } } }
    }
}
private class TasksDemoModel: ViewModel() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val wire=DemoTaskWire(scope)
    private val replica=ChannelReplica("demo","me")
    private val local=MutableStateFlow<List<LocalSubmission>>(emptyList())
    private val owner=object:TaskSubmissionOwner {
        override val submissions=local
        override suspend fun submitFrame(frame: JsonObject,text: String,targetLabel: String): String {
            val id=UUID.randomUUID().toString();wire.request(Up.SUBMIT,JsonObject(frame+("id" to JsonPrimitive(id))));return id
        }
        override fun retry(id: String) { local.value=local.value.filterNot { it.messageId==id };notice="已用原编号重试 $id" }
        override fun cancel(id: String) { local.value=local.value.filterNot { it.messageId==id };notice="已取消 $id" }
    }
    private val services=WorkspaceServices("http://offline.invalid","demo",mapOf("demo" to "me"),wire,ObsClient("http://offline.invalid",OkHttpClient()),OkHttpClient())
    val tasks=TasksViewModel(services,"demo",submissionOwner=owner)
    val forms=UiFormController(wire,{ "me" })
    var mode by mutableStateOf("tasks");private set
    var notice by mutableStateOf("")
    private var formNumber=0
    init {
        publish()
        scope.launch(start=CoroutineStart.UNDISPATCHED) { wire.frames.collect { frame -> if (frame is DownFrame.Feed) { replica.apply(RowBatch("demo",listOf(frame.row),Origin.LIVE));publish() } } }
        scope.launch {
            wire.add("approval","human.approve",json("""{"title":"发布竞品研究报告","text":"请确认报告可以发给团队。","impact":"团队会看到最终版本","priority":"high"}"""),sender="codex",audience=listOf("me"))
            wire.add("question","human.ask",json("""{"text":"报告里要优先比较哪些产品？"}"""),sender="codex",audience=listOf("me"))
            wire.add("run","agent.ask",json("""{"text":"整理竞品资料和附件"}"""))
            wire.add("run-progress","agent.ask",json("""{"status":"processing","controls":[{"word":"agent.steer"},{"word":"agent.interrupt"}]}"""),kind="response",parent="run",sender="codex")
            wire.add("formal","task.create",json("""{"title":"核对研究结论","description":"检查关键结论和引用来源","source":{"channelId":"demo","objectId":"run","seq":3}}"""))
            wire.add("formal-response","task.create",json("""{"status":"completed","value":{"task_id":"research-7","status":"active","title":"核对研究结论"}}"""),kind="response",parent="formal",sender="codex")
            local.value=listOf(LocalSubmission("recover","demo",json("""{"msg_type":"agent.ask","audience":["codex"]}"""),"发送失败的跟进问题","Codex",SendState.REJECTED,System.currentTimeMillis(),System.currentTimeMillis(),"离线示例：提交被拒绝"))
            tasks.schedule(3600000,"human.note",json("""{"text":"稍后检查研究报告"}"""))
        }
    }
    private fun publish() { tasks.update(replica.snapshot(),"me",listOf(TaskActor("codex","Codex")),true);forms.scan(replica.snapshot()) }
    fun show(next: String) {
        mode=next
        if (next=="form" && forms.state.value.forms.isEmpty()) scope.launch { wire.add("form-${++formNumber}","ui.form",json("""{"session":"demo-screen","title":"填写研究参数","schema":{"type":"object","required":["topic","count"],"properties":{"topic":{"type":"string","title":"研究主题","minLength":2},"count":{"type":"integer","title":"样本数量","minimum":1,"maximum":20,"default":5},"market":{"enum":["中国","全球"],"title":"市场","default":"中国"},"notify":{"type":"boolean","title":"完成后提醒","default":true},"key":{"type":"string","title":"演示密钥"},"options":{"type":"object","title":"补充参数 JSON","default":{"depth":2}}}},"secret":{"key":"global/demo_key"}}"""),sender="codex",audience=listOf("me")) }
    }
    override fun onCleared() { forms.close();tasks.close();scope.cancel() }
}
private fun json(value: String)=Json.parseToJsonElement(value).jsonObject
private class DemoTaskWire(private val scope: CoroutineScope):WireClient {
    override val state=MutableStateFlow<WireState>(WireState.Attached(1,json("""{"session":"demo-screen"}""")))
    override val frames=MutableSharedFlow<DownFrame>(extraBufferCapacity=64)
    private var seq=0L
    private val requests=mutableMapOf<String,Envelope>()
    private val timers=mutableMapOf<String,Job>()
    suspend fun add(id: String,type: String,body: JsonObject,kind: String="request",parent: String="",sender: String="me",audience: List<String> = listOf("codex")) {
        val env=Envelope(buildJsonObject {
            put("id",id);put("channel_id","demo");put("kind",kind);put("type",type);put("ts",System.currentTimeMillis());put("parent_id",parent)
            put("sender",buildJsonObject { put("id",sender);put("kind",if (sender=="me") "human" else "agent") });put("audience",JsonArray(audience.map(::JsonPrimitive)))
            put("payload",buildJsonObject { put("body",body) })
        })
        if (kind=="request") requests[id]=env
        frames.emit(DownFrame.Feed("",Row("demo",++seq,env)))
    }
    override suspend fun request(type: String,payload: JsonObject):JsonObject = when (type) {
        Up.SUBMIT -> {
            val id=payload.text("id");val word=payload.text("msg_type");add(id,word,payload.objectAt("payload"))
            when (word) {
                "actor.describe" -> add("describe:$id",word,json("""{"status":"completed","value":{"words":{"task.create":{},"agent.ask":{}}}}"""),"response",id,"codex")
                "task.create" -> add("done:$id",word,buildJsonObject { put("status","completed");put("value",buildJsonObject { put("task_id","task:$id");put("title",payload.objectAt("payload").text("title"));put("status","active") }) },"response",id,"codex")
                "agent.interrupt" -> add("interrupted:run","agent.ask",json("""{"status":"failed","reason":"interrupted"}"""),"response","run","codex")
            }
            json("""{"status":"accepted"}""")
        }
        Up.RESOLVE -> { val id=payload.text("req_id");add("answer:$id",requests[id]?.type ?: "ui.form",json("""{"status":"completed"}"""),"response",id);json("{}") }
        Up.CANCEL -> { val id=payload.text("req_id");add("cancel:$id",requests[id]?.type ?: "agent.ask",json("""{"status":"failed","reason":"cancelled"}"""),"response",id);json("{}") }
        Up.AFTER -> {
            val id=UUID.randomUUID().toString()
            timers[id]=scope.launch { delay(payload.number("duration_ms"));val env=Envelope(buildJsonObject { put("id","timer:$id");put("correlation_id","timer:$id");put("channel_id","demo");put("kind","event");put("type",payload.text("msg_type"));put("sender",json("""{"id":"me","kind":"human"}"""));put("audience",JsonArray(listOf(JsonPrimitive("me"))));put("payload",payload.objectAt("payload")) });frames.emit(DownFrame.Feed("",Row("demo",++seq,env))) }
            buildJsonObject { put("timer_id",id) }
        }
        Up.CANCEL_TIMER -> { timers.remove(payload.text("timer_id"))?.cancel();json("{}") }
        Up.RESOURCE -> when (payload.text("op")) { "read" -> json("""{"value":"demo-old-value"}""");"stat" -> json("""{"exists":true}""");else -> json("{}") }
        else -> json("{}")
    }
    override suspend fun history(payload: JsonObject)=HistoryPage(emptyList(),Envelope.EMPTY)
}

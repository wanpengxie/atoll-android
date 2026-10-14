package dev.atoll.app.governance

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
import dev.atoll.app.core.*
import dev.atoll.app.tasks.*
import dev.atoll.app.ui.theme.AtollTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

/** All mutations and observations are in-memory fixtures. This Activity has no network client. */
class GovernanceDemoActivity:ComponentActivity() {
    private lateinit var model:GovernanceDemoModel
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent);model.mode=intent.getStringExtra("mode") ?: "channel" }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState);enableEdgeToEdge()
        model=ViewModelProvider(this,object:ViewModelProvider.Factory { @Suppress("UNCHECKED_CAST") override fun <T:ViewModel> create(modelClass: Class<T>)=GovernanceDemoModel() as T })[GovernanceDemoModel::class.java]
        model.mode=intent.getStringExtra("mode") ?: "channel"
        setContent { AtollTheme { Surface(Modifier.fillMaxSize()) { Column(Modifier.systemBarsPadding().imePadding()) {
            Text("c0.dev.web · 离线治理演示",Modifier.padding(12.dp),style=MaterialTheme.typography.titleSmall)
            Row { listOf("channel" to "频道","space" to "空间","create" to "创建","activity" to "活动").forEach { (mode,label) -> TextButton(onClick={ model.mode=mode }) { Text(label) } } }
            if (model.entered.isNotEmpty()) Text("进入 ${model.entered}",Modifier.padding(horizontal=12.dp))
            when (model.mode) {
                "space" -> SpaceAdministrationScreen(model.vm,Modifier.weight(1f))
                "activity" -> { val operations by model.vm.activityOperations.collectAsState();ActivityCenterScreen(selectActivity(listOf(ChannelActivityInput("demo","c0.dev.web",emptyList())),operations=operations),Modifier.weight(1f)) }
                else -> GovernanceScreen(model.vm,Modifier.weight(1f),onEnterChannel={ model.entered=it })
            }
            if (model.mode=="create") ChannelCreateScreen(model.vm,onEnterChannel={ model.entered=it;model.mode="channel" },onClose={ model.mode="channel" })
        } } } }
    }
}
private class GovernanceDemoModel:ViewModel() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val frames=MutableSharedFlow<DownFrame>(extraBufferCapacity=32)
    private val port=DemoGovernancePort()
    val vm=GovernanceViewModel(port,"root",frames)
    var mode by mutableStateOf("channel")
    var entered by mutableStateOf("")
    init {
        vm.context("demo","human:self",true,setOf("demo","copyable"))
        scope.launch { vm.creations.collect { operations -> vm.context("demo","human:self",true,setOf("demo","copyable")+operations.mapNotNull { it.targetId.takeIf(String::isNotEmpty) }) } }
    }
    override fun onCleared() { vm.close();scope.cancel() }
}
private fun demoJson(text: String)=Json.parseToJsonElement(text).jsonObject
private class DemoGovernancePort:GovernancePort {
    private var next=0
    private var members=listOf(
        demoJson("""{"declared":{"id":"human:self","name":"我","kind":"human","principal":"root"},"actual":{"measures":[{"name":"bound","value":true},{"name":"device_online","value":true}]}}"""),
        demoJson("""{"declared":{"id":"agent:codex","name":"Codex","kind":"agent","entry_id":"e1","body":"class codex"},"actual":{"measures":[{"name":"bound","value":true},{"name":"device_online","value":true}]}}"""),
        demoJson("""{"declared":{"id":"tool:files","name":"文件工具","kind":"tool","entry_id":"e2","body":"actor files@2"},"actual":{"measures":[{"name":"bound","value":false},{"name":"device_online","value":null,"unknown":true}]}}"""))
    private var channelRows=listOf(demoJson("""{"declared":{"id":"demo","name":"web","qualified_name":"c0.dev.web","owner_principal":"root","parent_id":"dev"},"actual":{"measures":[{"name":"open","value":true}]}}"""),demoJson("""{"declared":{"id":"copyable","name":"research","qualified_name":"c0.research","parent_id":"demo"},"actual":{"measures":[{"name":"open","value":true}]}}"""))
    private var devices=listOf(demoJson("""{"declared":{"id":"local-device","name":"local-device"},"actual":{"measures":[{"name":"online","value":true}]}}"""),demoJson("""{"declared":{"id":"mac-mini","name":"Mac mini"},"actual":{"measures":[{"name":"online","value":false}]}}"""))
    private var descriptions=listOf(demoJson("""{"id":"research","name":"研究员","version":2,"class":"codex","status":"present","owner":"root","configurable":true,"description":"竞品资料研究","params":{"model":"gpt-test"}}"""),demoJson("""{"id":"research","name":"研究员","version":1,"class":"codex","status":"retired","owner":"root"}"""))
    private val people=listOf(demoJson("""{"declared":{"id":"root","display_name":"我","kind":"human"}}"""),demoJson("""{"declared":{"id":"alice","display_name":"Alice","kind":"human","status":"present"}}"""),demoJson("""{"declared":{"id":"guest","kind":"human"}}"""))
    private var body=demoJson("""{"description":"Android 客户端开发与验证","serving":1,"devices":[],"members":[{"id":"e1","name":"Codex","body":{"class":"codex"}},{"id":"e2","name":"文件工具","body":{"actor":"files@2"}}]}""")
    private var revision=3
    private val keys=mutableMapOf("demo_key" to "dummy-value")
    override suspend fun directory(channel: String)=GovernanceDirectory(channelRows,members,devices,people,descriptions,channelRows.first(),devices.filter { (body["devices"] as? JsonArray)?.contains(JsonPrimitive(it.directoryId()))==true })
    override suspend fun channels(parent: String)=channelRows.filter { it.declared().text("parent_id")==parent }
    override suspend fun execute(channel: String,command: GovernanceCommand,id: String,accepted: ()->Unit):JsonObject {
        accepted();delay(150)
        val p=command.payload
        return when (command.word) {
            "system.channel.get" -> buildJsonObject { put("description",buildJsonObject { put("revision",revision);put("body",body) }) }
            "system.channel.description.get" -> buildJsonObject { put("body",body) }
            "system.member.get" -> demoJson("""{"id":"${p.text("member")}","class":"codex","config":{"revision":2,"values":{"model":"gpt-test"}}}""")
            "system.member.create" -> { val entry="entry-${++next}";members=members+buildJsonObject { put("declared",buildJsonObject { put("id","agent:new:$next");put("name",p.text("name").ifEmpty { "新成员" });put("kind","agent");put("entry_id",entry);put("body","class codex") });put("actual",demoJson("""{"measures":[{"name":"bound","value":true}]}""")) };buildJsonObject { put("entry",buildJsonObject { put("id",entry) }) } }
            "system.member.admit" -> { members=members+demoJson("""{"declared":{"id":"human:${p.text("principal")}","name":"${p.text("principal")}","kind":"human","principal":"${p.text("principal")}"}}""");Envelope.EMPTY }
            "system.member.delete" -> { members=members.filter { it.directoryId()!=p.text("member") };Envelope.EMPTY }
            "system.channel.set" -> { body=JsonObject(body+mapOf("description" to p["description"]!!,"serving" to p["serving"]!!));revision++;Envelope.EMPTY }
            "system.device.attach","system.device.detach" -> { val values=(body["devices"] as? JsonArray).orEmpty().toMutableList();val device=JsonPrimitive(p.text("device_id"));if (command.word.endsWith("attach")) values.add(device) else values.remove(device);body=JsonObject(body+("devices" to JsonArray(values.distinct())));revision++;Envelope.EMPTY }
            "system.device.create" -> { devices=devices+buildJsonObject { put("declared",buildJsonObject { put("id","device-${++next}");put("name",p.text("name")) }) };Envelope.EMPTY }
            "system.device.delete" -> { devices=devices.filter { it.directoryId()!=p.text("device_id") };Envelope.EMPTY }
            "system.actor.description.create" -> { val base=p.text("id").ifEmpty { "description-${++next}" };val version=(descriptions.filter { it.text("id")==base }.maxOfOrNull { it.integer("version") } ?: 0)+1;descriptions=descriptions+JsonObject(p+mapOf("id" to JsonPrimitive(base),"version" to JsonPrimitive(version),"status" to JsonPrimitive("present")));Envelope.EMPTY }
            "system.actor.description.retire" -> { descriptions=descriptions.map { if (it.text("id")==p.text("id") && it.integer("version")==p.integer("version")) JsonObject(it+("status" to JsonPrimitive("retired"))) else it };Envelope.EMPTY }
            "system.channel.create" -> { val target="child-${++next}";channelRows=channelRows+buildJsonObject { put("declared",buildJsonObject { put("id",target);put("name",p.text("name"));put("qualified_name","c0.dev.web.${p.text("name")}");put("parent_id",channel) });put("actual",demoJson("""{"measures":[{"name":"open","value":true}]}""")) };buildJsonObject { put("channel_id",target) } }
            else -> Envelope.EMPTY
        }
    }
    override suspend fun resource(channel: String,payload: JsonObject):JsonObject = when (payload.text("op")) {
        "list" -> buildJsonObject { put("items",JsonArray(keys.keys.sorted().map { buildJsonObject { put("id","global/$it") } })) }
        "stat" -> buildJsonObject { put("exists",payload.text("resource_id").removePrefix("global/") in keys) }
        "create","write" -> { keys[payload.text("resource_id").removePrefix("global/")]=(payload["args"] as JsonPrimitive).content;Envelope.EMPTY }
        "delete" -> { keys.remove(payload.text("resource_id").removePrefix("global/"));Envelope.EMPTY }
        else -> error("Demo never reads key values")
    }
}

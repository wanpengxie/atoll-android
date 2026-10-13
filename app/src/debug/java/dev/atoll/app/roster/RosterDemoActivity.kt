package dev.atoll.app.roster

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.atoll.app.core.*
import dev.atoll.app.net.actorRequest
import dev.atoll.app.ui.theme.AtollTheme
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

/** Local fake wire only. No server, credentials, account or production member is involved. */
class RosterDemoActivity : ComponentActivity() {
    private lateinit var model: RosterViewModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fixture = RosterFixture()
        model = RosterViewModel("roster-offline", fixture, fixture, "human:fixture")
        model.updateAccess(true, "human:fixture")
        setContent {
            AtollTheme {
                val state by model.state.collectAsState()
                Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                    Text("成员与配置 · 离线 Fake Wire", Modifier.padding(12.dp))
                    if (state.selected == null) RosterPanel(model, Modifier.weight(1f))
                    else ActorDetail(model, Modifier.weight(1f), onClose = model::clearSelection)
                }
            }
        }
    }
    override fun onDestroy() { model.close(); super.onDestroy() }
}
private class RosterFixture : WireClient, RosterPort {
    override val state = MutableStateFlow<WireState>(WireState.Attached(1, MemberConfig.empty))
    override val frames = MutableSharedFlow<DownFrame>()
    private var sequence = 0L
    private var revision = 1L
    private var info = MemberConfig.parse("""{"class":"agent","body":{"class":"agent"},"params":{"model":"demo","temperature":0.3},"requires":["agent.ask"],"own_config":{"revision":1,"desired_host":"","values":{"agent":"codex","config":{"endpoint":"https://example.invalid"}}},"effective":{"model":"demo","service":{"api_key":"${'$'}required:测试值"}},"sources":{"model":"member","service.api_key":"actor"},"missing":[{"key":"service.api_key","hint":"仅填模拟值，不填真实凭据"}],"note":"所有写入只修改本机 fixture","build":{"object":{"kind":"member"},"result":"ok","state":"ready","attempt":1,"description":{"channel_revision":1},"config":{"revision":1}}}""")
    override suspend fun roster() = MemberConfig.parse("""{"complete":true,"items":[{"declared":{"id":"human:fixture","name":"测试用户","kind":"human","body":"human"},"actual":{"measures":[{"name":"bound","value":true},{"name":"device_online","value":true}]}},{"declared":{"id":"agent:fixture","name":"codex（本机模拟）","kind":"agent","body":"class agent","description":"离线能力与配置演示"},"actual":{"measures":[{"name":"bound","value":true},{"name":"device_online","value":true}]}},{"declared":{"id":"tool:fixture","name":"离线工具","kind":"tool","body":"class echo"},"actual":{"measures":[{"name":"bound","value":false}]}},{"declared":{"id":"peer:fixture","name":"未知成员","kind":"peer"},"actual":{"measures":[{"name":"bound","unknown":true}]}},{"declared":{"id":"peer:hidden","body":"generated"}}]}""")
    override suspend fun actor(target: String, word: String, payload: JsonObject) = actorRequest("roster-offline", target, word, payload)
    override suspend fun descriptions() = listOf(MemberConfig.parse("""{"id":"fixture-writer","name":"writer","version":2,"status":"present"}"""))
    override suspend fun devices() = listOf(MemberConfig.parse("""{"id":"fixture-mini","name":"模拟 Mac mini"}"""))
    override suspend fun globalNames() = listOf("fixture_api_key", "fixture_model")
    override suspend fun history(payload: JsonObject) = HistoryPage(emptyList(), MemberConfig.empty)
    override suspend fun request(type: String, payload: JsonObject): JsonObject {
        check(type == Up.SUBMIT) { "fixture only submits actor words" }
        val word = payload.text("msg_type"); val body = payload.obj("payload")
        val value: JsonElement = when (word) {
            "actor.describe" -> MemberConfig.parse("""{"words":{"agent.demo":{"description":"本机模拟的任意能力调用；回显输入，不执行任务","input_schema":{"type":"object"},"error_codes":["invalid_args","busy"]},"agent.context":{"description":"模拟上下文信息","input_schema":{"type":"object"}}}}""")
            "system.member.get" -> info
            "system.class.list" -> Json.parseToJsonElement("""[{"class":"agent","kind":"agent"},{"class":"codex","kind":"agent"},{"class":"claude","kind":"agent"},{"class":"echo","kind":"tool"}]""")
            "system.member.set" -> {
                val next = info.toMutableMap()
                body["body"]?.let { next["body"] = it }
                body["params"]?.let { next["params"] = applyPatch(info.obj("params"), it) }
                body["requires"]?.let { next["requires"] = if (it == JsonNull) JsonArray(emptyList()) else it }
                info = JsonObject(next); buildJsonObject { put("description_revision", ++revision) }
            }
            "system.member.config.set" -> {
                val own = info.obj("own_config").toMutableMap()
                body["desired_host"]?.let { own["desired_host"] = it }
                body["values"]?.let { own["values"] = applyPatch(info.obj("own_config").obj("values"), it) }
                own["revision"] = JsonPrimitive(++revision)
                val next = info.toMutableMap(); next["own_config"] = JsonObject(own)
                next["desired_host"] = own["desired_host"] ?: JsonPrimitive("")
                body["values"]?.let { next["effective"] = applyPatch(info["effective"], it) }
                if (MemberConfig.valueAtPath(own["values"], "service.api_key") != null) next["missing"] = JsonArray(emptyList())
                info = JsonObject(next); buildJsonObject { put("revision", revision) }
            }
            else -> buildJsonObject { put("mock", true); put("word", word); put("echo", body) }
        }
        val envelope = Envelope(buildJsonObject {
            put("id", "fixture-answer-${++sequence}"); put("kind", "response"); put("type", word); put("parent_id", payload.text("id"))
            put("payload", buildJsonObject { put("body", buildJsonObject { put("status", "completed"); put("value", value) }) })
        })
        frames.emit(DownFrame.Feed("", Row("roster-offline", sequence, envelope)))
        return MemberConfig.empty
    }
    private fun applyPatch(before: JsonElement?, patch: JsonElement): JsonElement {
        if (patch !is JsonObject) return patch
        val next = (before as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        patch.forEach { (key, value) -> if (value == JsonNull) next.remove(key) else next[key] = applyPatch(next[key], value) }
        return JsonObject(next)
    }
}

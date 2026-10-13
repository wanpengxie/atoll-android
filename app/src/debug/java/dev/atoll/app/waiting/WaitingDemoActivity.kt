package dev.atoll.app.waiting

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.atoll.app.compose.*
import dev.atoll.app.core.*
import dev.atoll.app.model.*
import dev.atoll.app.net.actorRequest
import dev.atoll.app.ui.theme.AtollTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.util.UUID

/** Entirely offline fixture. No credentials, HTTP, auth/logout or production actors. */
class WaitingDemoActivity : ComponentActivity() {
    private lateinit var fixture: WaitingFixture
    private lateinit var composer: ComposerViewModel
    private lateinit var waiting: WaitingViewModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fixture = WaitingFixture()
        composer = ComposerViewModel("waiting-offline", fixture, MemoryOutbox(), AttachmentUploader { _, _ -> error("fixture has no files") })
        composer.setChannel("waiting-offline", "human:fixture", listOf(ComposerActor("agent:fixture", "codex（离线模拟）", "agent")), true)
        composer.updateText("普通草稿会在退出编辑后恢复")
        waiting = WaitingViewModel("waiting-offline", fixture, object : WaitingPort {
            override suspend fun actor(actorId: String, type: String, payload: JsonObject, messageId: String) = fixture.actorRequest("waiting-offline", actorId, type, payload, messageId = messageId)
            override suspend fun cancel(targetId: String) { fixture.request(Up.CANCEL, payload("req_id" to targetId)) }
            override suspend fun submit(frame: JsonObject, text: String, targetLabel: String) = composer.submitFrame(frame, text, targetLabel)
        }, fixture.projection(), "human:fixture")
        fixture.changed = { waiting.updateSnapshot(fixture.projection()) }
        waiting.updateAccess(true, "human:fixture", mapOf("agent:fixture" to "codex（离线模拟）"))
        fixture.reset()
        setContent {
            AtollTheme {
                val state by waiting.state.collectAsState()
                val connection by fixture.state.collectAsState()
                Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                    Text("等待区与编辑 · 离线模拟", Modifier.padding(12.dp))
                    Row {
                        TextButton(onClick = fixture::reset) { Text("重置队列") }
                        TextButton(onClick = { fixture.state.value = if (connection is WireState.Attached) WireState.Disconnected("offline fixture", 0) else WireState.Attached(2, Envelope.EMPTY) }) { Text(if (connection is WireState.Attached) "模拟断线" else "恢复连接") }
                    }
                    Text("这三个队列任务来自固定投影；所有控制只在本机模拟。", Modifier.weight(1f).padding(12.dp))
                    WaitingDock(waiting)
                    AtollComposer(composer, editing = waiting.editorBinding(state))
                }
            }
        }
    }
    override fun onDestroy() { waiting.close(); composer.close(); super.onDestroy() }
}
private class MemoryOutbox : OutboxRepository {
    private val rows = linkedMapOf<String, LocalSubmission>()
    override suspend fun restore() = rows.values.toList()
    override suspend fun putMany(rows: List<LocalSubmission>) { rows.forEach { this.rows[it.messageId] = it } }
    override suspend fun remove(messageId: String) { rows.remove(messageId) }
    override suspend fun restoreDrafts() = emptyMap<String, ComposerDraft>()
    override suspend fun putDraft(channelId: String, draft: ComposerDraft) {}
}
private class WaitingFixture : WireClient {
    override val state = MutableStateFlow<WireState>(WireState.Attached(1, buildJsonObject { put("boot", "waiting-fixture") }))
    override val frames = MutableSharedFlow<DownFrame>()
    private var replica = ChannelReplica("waiting-offline", "human:fixture")
    private var seq = 0L
    private var hold = ""
    private val original = linkedMapOf<String, String>()
    var changed: () -> Unit = {}
    fun projection() = replica.timeline(TimelineOptions(selfId = "human:fixture"))
    fun reset() {
        replica = ChannelReplica("waiting-offline", "human:fixture"); seq = 0; hold = ""; original.clear()
        for ((index, text) in listOf("请整理测试方案", "请检查待办清单", "请回答第三条消息").withIndex()) {
            val id = "fixture-${index + 1}"
            original[id] = text
            row(request(id, "agent.ask", payload("text" to text)))
            row(response(id, "agent.ask", controls("queued")))
        }
        changed()
    }
    private fun row(envelope: Envelope): Row {
        val row = Row("waiting-offline", ++seq, envelope)
        replica.apply(RowBatch("waiting-offline", listOf(row), Origin.LIVE))
        return row
    }
    private suspend fun emit(envelope: Envelope) {
        frames.emit(DownFrame.Feed("", row(envelope))); changed()
    }
    private fun request(id: String, type: String, body: JsonObject) = Envelope(buildJsonObject {
        put("id", id); put("kind", "request"); put("type", type); put("ts", System.currentTimeMillis()); put("channel_id", "waiting-offline")
        put("sender", payload("id" to "human:fixture", "kind" to "human")); put("audience", buildJsonArray { add("agent:fixture") })
        put("payload", buildJsonObject { put("body", body) })
    })
    private fun response(target: String, type: String, body: JsonObject) = Envelope(buildJsonObject {
        put("id", UUID.randomUUID().toString()); put("kind", "response"); put("type", type); put("parent_id", target); put("ts", System.currentTimeMillis())
        put("sender", payload("id" to "agent:fixture", "kind" to "agent")); put("audience", buildJsonArray { add("human:fixture") })
        put("payload", buildJsonObject { put("body", body) })
    })
    private fun controls(status: String) = buildJsonObject {
        put("status", status); put("controls", buildJsonArray { listOf("agent.steer", "agent.replace", "agent.dismiss").forEach { add(buildJsonObject { put("word", it) }) } })
    }
    override suspend fun request(type: String, payload: JsonObject): JsonObject {
        if (state.value !is WireState.Attached) throw WireException(type, "closed", "模拟断线")
        if (type == Up.CANCEL) {
            val target = payload.text("req_id"); original.remove(target)
            emit(response(target, "agent.ask", buildJsonObject { put("status", "failed"); put("cancelled", true) }))
            return Envelope.EMPTY
        }
        if (type != Up.SUBMIT) return Envelope.EMPTY
        val word = payload.text("msg_type"); val id = payload.text("id"); val body = payload["payload"] as? JsonObject ?: Envelope.EMPTY
        emit(request(id, word, body))
        val value: JsonObject = when (word) {
            "actor.describe" -> buildJsonObject { put("words", buildJsonObject {
                for (control in listOf("agent.replace", "agent.unhold", "agent.hold", "agent.context")) put(control, buildJsonObject {
                    put("input_schema", buildJsonObject { put("properties", buildJsonObject { put("expected_hold_id", buildJsonObject { put("type", "string") }) }) })
                })
            }) }
            "agent.context" -> if (hold.isEmpty()) Envelope.EMPTY else buildJsonObject { put("frozen", buildJsonObject { put("held_by", hold); put("until", System.currentTimeMillis() + 30 * 60_000) }) }
            "agent.hold" -> { hold = id; Envelope.EMPTY }
            "agent.unhold" -> {
                if (hold.isNotEmpty() && hold != body.text("expected_hold_id")) throw WireException(word, "cas_mismatch", "暂停已被接管")
                hold = ""; buildJsonObject { put("released", true) }
            }
            "agent.steer" -> {
                val targets = if (body.flag("all")) original.keys.toList() else listOf(body.text("target"))
                targets.forEach { target -> original.remove(target); emit(response(target, "agent.ask", buildJsonObject { put("status", "completed"); put("merged_into", "fixture-current") })) }
                Envelope.EMPTY
            }
            "agent.dismiss" -> { val target = body.text("target"); original.remove(target); emit(response(target, "agent.ask", buildJsonObject { put("status", "failed"); put("cancelled", true) })); Envelope.EMPTY }
            "agent.replace" -> {
                val target = body.text("target")
                if (hold != body.text("expected_hold_id") || original[target] != body.text("old_text")) {
                    emit(response(id, word, buildJsonObject { put("status", "failed"); put("detail", "编辑比较失败") })); return Envelope.EMPTY
                }
                original.remove(target); original[id] = body.text("new_text")
                emit(response(target, "agent.ask", buildJsonObject { put("status", "completed"); put("replaced_by", id) }))
                emit(response(id, word, controls("queued")))
                return Envelope.EMPTY
            }
            "agent.ask" -> { original[id] = body.text("text"); emit(response(id, word, controls("queued"))); return Envelope.EMPTY }
            else -> Envelope.EMPTY
        }
        emit(response(id, word, JsonObject(value + ("status" to JsonPrimitive("completed")))))
        return Envelope.EMPTY
    }
    override suspend fun history(payload: JsonObject) = HistoryPage(emptyList(), Envelope.EMPTY)
}

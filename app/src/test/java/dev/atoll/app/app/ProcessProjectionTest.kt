package dev.atoll.app.app

import dev.atoll.app.core.*
import dev.atoll.app.model.*
import dev.atoll.app.ui.screens.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProcessProjectionTest {
    private fun envelope(id: String, process: JsonObject? = null, kind: String = "response") = Envelope(buildJsonObject {
        put("id", id); put("kind", kind); put("type", "agent.ask")
        put("payload", buildJsonObject { put("body", buildJsonObject { if (kind == "response") put("status", "processing"); if (process != null) put("process", process) }) })
    })
    @Test fun toolStartAndEndMergeFieldsAndKeepAuditIdsWhileTextStagesStayInChat() {
        val request = envelope("opaque:request", kind = "request")
        val observations = listOf(
            Row("c", 2, envelope("start", buildJsonObject {
                put("kind", "tool"); put("tool_call_id", "call:opaque"); put("tool", "workspace.bash"); put("phase", "started")
                put("input", buildJsonObject { put("command", "ls") }); put("audit_id", "audit")
            })),
            Row("c", 3, envelope("text", buildJsonObject { put("kind", "stage"); put("stage", "text"); put("text", "narration") })),
            Row("c", 4, envelope("end", buildJsonObject {
                put("kind", "tool"); put("tool_call_id", "call:opaque"); put("tool", "workspace.bash"); put("phase", "ended")
                put("outcome", "failed"); put("output", "missing"); put("detail", "execution notes"); put("audit_id", "audit")
            })))
        val target = TimelineMessageTarget(request, Turn(request, 1, observations))
        val panel = processPanel(target)
        assertEquals("opaque:request", panel.requestId)
        assertEquals(1L, panel.requestSeq)
        val tool = panel.records.single()
        assertEquals(ProcessKind.Tool, tool.kind)
        assertEquals("tool: workspace.bash 失败", tool.line)
        assertEquals("ls", (tool.input as JsonObject)["command"].string())
        assertEquals("missing", tool.outputText)
        assertEquals("execution notes", tool.body)
        assertTrue(tool.identifiers.contains("审计编号" to "audit"))
        assertTrue(tool.identifiers.contains("过程编号" to "end"))
    }
    @Test fun toolRequestsInTheFoldedThreadAlsoReachTheDetailPanel() {
        val request = envelope("q", kind = "request")
        val turn = Turn(request, 1)
        val childRequest = Envelope(buildJsonObject {
            put("id", "tool:call"); put("kind", "request"); put("type", "workspace.bash")
            put("sender", buildJsonObject { put("id", "agent:a"); put("kind", "agent") })
            put("audience", buildJsonArray { add("tool:device") })
            put("payload", buildJsonObject { put("body", buildJsonObject { put("command", "ls") }) })
        })
        val child = dev.atoll.app.model.TimelineEntry("turn", 2, Turn(childRequest, 2))
        val entry = dev.atoll.app.model.TimelineEntry("turn", 1, turn, thread = listOf(child))
        val source = ReplicaSnapshot("c", 1, 1, 2, emptyList(), listOf(entry), emptyList(), emptyMap())
        val panel = processPanel(TimelineMessageTarget(request, turn), source)
        assertEquals("call:tool:call", panel.records.single().id)
        assertEquals("ls", (panel.records.single().input as JsonObject)["command"].string())
    }

    @Test fun historicalOpenTurnsDoNotClaimToStillBeRunning() {
        val request = envelope("q", kind = "request")
        val turn = Turn(request, 1, listOf(Row("c", 2, envelope("processing"))))
        assertEquals("processing", processPanel(TimelineMessageTarget(request, turn)).status)
        assertEquals("终态未加载", processPanel(TimelineMessageTarget(request, turn), newerUnloaded = true).status)
    }
    @Test fun panelMapsOnlyTheTargetThreadAndExecutionIdComesFromProcessing() {
        fun frame(id: String, kind: String, sender: String, audience: String = "", parent: String = "", status: String = "", text: String = "") = Envelope(buildJsonObject {
            put("id", id); put("kind", kind); put("type", "agent.ask"); put("parent_id", parent); put("ts", 1000)
            put("sender", buildJsonObject { put("id", sender); put("kind", sender.substringBefore(':')) })
            put("audience", buildJsonArray { if (audience.isNotEmpty()) add(audience) })
            put("payload", buildJsonObject { put("body", buildJsonObject { put("text", text); if (status.isNotEmpty()) put("status", status) }) })
        })
        val request = frame("root:request", "request", "human:root", "agent:a", text = "只看这个回合")
        val progress = frame("root:processing", "response", "agent:a", parent = request.id, status = "processing").let { base ->
            Envelope(JsonObject(base.json + ("payload" to buildJsonObject { put("body", JsonObject(base.body + ("turn_id" to JsonPrimitive("execution:opaque")))) })))
        }
        val turn = Turn(request, 1, listOf(Row("c", 2, progress)))
        val childRequest = frame("child", "request", "agent:a", "agent:b", parent = request.id, text = "协作任务")
        val childTurn = Turn(childRequest, 3, terminal = frame("child:answer", "response", "agent:b", parent = "child", status = "completed", text = "协作结果"), terminalSeq = 4)
        val root = dev.atoll.app.model.TimelineEntry("turn", 1, turn, thread = listOf(dev.atoll.app.model.TimelineEntry("turn", 3, childTurn)))
        val unrelated = dev.atoll.app.model.TimelineEntry("turn", 5, Turn(frame("other", "request", "human:other", "agent:b", text = "不相关请求"), 5))
        val rows = listOf(Row("c", 1, request), Row("c", 2, progress), Row("c", 3, childRequest), Row("c", 4, childTurn.terminal!!), Row("c", 5, unrelated.turn!!.request))
        val source = ReplicaSnapshot("c", 1, 1, 5, rows, listOf(root, unrelated), emptyList(), rows.associate { it.envelope.id to it.envelope })
        val roster = listOf(ActorRecord("human:root", "root", "human"), ActorRecord("agent:a", "A", "agent"), ActorRecord("agent:b", "B", "agent"))
        val panel = processPanel(TimelineMessageTarget(request, turn), source, selfId = "human:root", roster = roster)
        assertEquals("只看这个回合", panel.request!!.text)
        assertEquals("A", panel.agent!!.name)
        assertEquals("execution:opaque", panel.executionTurnId)
        assertEquals(listOf("child"), panel.relatedCalls.map { it.id })
        assertEquals("协作结果", panel.relatedCalls.single().text)
        assertFalse(panel.relatedCalls.any { it.id == "other" })
        val childPanel = processPanel(TimelineMessageTarget(childRequest, childTurn), source, selfId = "human:root", roster = roster)
        assertEquals("child", childPanel.requestId)
        assertEquals("root:request", childPanel.collaboration!!.root.id)
        assertEquals(listOf("child"), childPanel.collaboration!!.calls.map { it.id })
        assertEquals("", processPanel(TimelineMessageTarget(request, turn.copy(terminal = progress)), source).executionTurnId)
        assertEquals("", processPanel(TimelineMessageTarget(request, turn), source, newerUnloaded = true).executionTurnId)
    }

    @Test fun runningTasksPreserveTheOwnerAndOpaqueRequestAnchor() {
        val task = BackgroundTask("key", "call", "agent:a", kind = "shell", requestId = "q:opaque", startedAt = 42)
        val row = runningPanel(listOf(task), listOf(ActorRecord("agent:a", "Agent A", "agent"))).single()
        assertEquals("Agent A", row.owner)
        assertEquals("q:opaque", row.requestId)
        assertEquals(SubTaskKind.Shell, row.kind)
    }
}

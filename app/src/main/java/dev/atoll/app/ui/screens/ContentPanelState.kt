package dev.atoll.app.ui.screens

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.JsonElement

enum class ProcessKind { Stage, Tool }
@Immutable data class ProcessRecordViewState(val id: String, val line: String, val time: String,
    val kind: ProcessKind = ProcessKind.Stage, val stateOnly: Boolean = false, val input: JsonElement? = null,
    val output: JsonElement? = null, val outputText: String = "", val body: String = "", val identifiers: List<Pair<String, String>> = emptyList())
@Immutable data class ProcessPanelViewState(val title: String, val requestId: String, val type: String,
    val status: String, val records: List<ProcessRecordViewState>, val requestSeq: Long? = null,
    val terminalId: String? = null, val detail: String = "", val selectedRecordId: String? = null,
    val request: MessageViewState? = null, val agent: AgentViewState? = null,
    val relatedCalls: List<RelatedCallViewState> = emptyList(), val subTasks: List<SubTaskViewState> = emptyList(),
    val collaboration: CollaborationViewState? = null, val startedAtMillis: Long = 0,
    val startTime: String = "", val executionTurnId: String = "")
@Immutable data class ProgressTrailViewState(val title: String, val records: List<ProcessRecordViewState>,
    val running: Boolean, val startedAtMillis: Long = 0, val startTime: String = "")
enum class SubTaskKind(val label: String) { Agent("子 Agent"), Shell("后台命令"), Other("后台任务") }
enum class SubTaskPhase(val label: String) { Running("进行中"), Completed("已完成"), Failed("失败") }
@Immutable data class SubTaskViewState(val id: String, val title: String, val owner: String, val kind: SubTaskKind,
    val phase: SubTaskPhase, val startedAtMillis: Long, val requestId: String? = null, val latest: String = "",
    val request: String = "", val result: String = "", val messages: List<String> = emptyList(), val children: List<SubTaskViewState> = emptyList())
@Immutable data class StarredMessageViewState(val id: String, val sender: String, val time: String, val text: String,
    val senderId: String = sender, val agent: Boolean = true, val resultFields: Int? = null, val self: Boolean = false)
fun runningFor(startedAt: Long, now: Long): String {
    val minutes = ((now - startedAt).coerceAtLeast(0) / 60000)
    return when { minutes < 1 -> "刚开始"; minutes < 60 -> "已运行 $minutes 分钟"; else -> "已运行 ${minutes / 60} 小时 ${minutes % 60} 分钟" }
}

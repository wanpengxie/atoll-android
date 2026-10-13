package dev.atoll.app.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.atoll.app.ui.screens.*
import dev.atoll.app.ui.theme.AtollColors
import kotlinx.coroutines.delay

@Composable
fun ProgressTrail(state: ProgressTrailViewState, onOpenRecord: (String) -> Unit) {
    var expanded by remember(state.title) { mutableStateOf(false) }
    val now by produceState(System.currentTimeMillis(), state.running) {
        if (state.running) while (true) { value = System.currentTimeMillis(); delay(1000) }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 8.dp)) {
            Text(if (state.running) "处理中: ${state.title}" else "${state.records.size} 条过程记录", Modifier.weight(1f), fontSize = 12.sp, color = AtollColors.Muted)
            if (state.running) Text("运行 ${activityDuration(state.startedAtMillis, now)} ${state.startTime}", fontSize = 10.sp, color = AtollColors.Muted)
            Text(if (expanded) " ⌃" else " ⌄", color = AtollColors.Muted, fontSize = 12.sp)
        }
        val visible = if (expanded) state.records else if (state.running) state.records.takeLast(2) else emptyList()
        visible.forEach { record -> Row(Modifier.fillMaxWidth().clickable(enabled = !record.stateOnly) { onOpenRecord(record.id) }.padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(record.line, Modifier.weight(1f), fontSize = 11.sp, color = AtollColors.Muted)
            Text(record.time, fontSize = 10.sp, color = AtollColors.Muted)
        } }
    }
}
@Composable
fun SubTaskTree(tasks: List<SubTaskViewState>, depth: Int = 0, onOpen: (String) -> Unit = {}) {
    var groupExpanded by remember(tasks.map { it.id }) { mutableStateOf(tasks.size <= 3) }
    Column(Modifier.padding(start = if (depth > 0) 12.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        if (tasks.size > 3) TextButton({ groupExpanded = !groupExpanded }) {
            Text("子 Agent ${tasks.size} 个 · ${tasks.count { it.phase == SubTaskPhase.Running }} 进行中 · ${tasks.count { it.phase == SubTaskPhase.Failed }} 失败", fontSize = 11.sp, color = AtollColors.Muted)
        }
        if (groupExpanded) tasks.forEach { task -> SubTaskLeaf(task, depth, onOpen) }
    }
}
@Composable private fun SubTaskLeaf(task: SubTaskViewState, depth: Int, onOpen: (String) -> Unit) {
    var expanded by remember(task.id) { mutableStateOf(false) }
    val now by produceState(System.currentTimeMillis(), task.phase) {
        if (task.phase == SubTaskPhase.Running) while (true) { value = System.currentTimeMillis(); delay(1000) }
    }
    Surface(shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, AtollColors.Line), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
                Text("${task.kind.label} · ${task.phase.label} · ${task.owner}", fontSize = 10.sp, color = if (task.phase == SubTaskPhase.Failed) AtollColors.Accent else AtollColors.Muted)
                Text(task.title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                if (task.phase == SubTaskPhase.Running) Text(runningFor(task.startedAtMillis, now), fontSize = 10.sp, color = AtollColors.Muted)
                if (task.latest.isNotEmpty()) Text(task.latest, fontSize = 11.sp, color = AtollColors.Muted, maxLines = if (expanded) Int.MAX_VALUE else 2)
            }
            if (expanded) {
                if (task.request.isNotEmpty()) Markdown("${task.id}:request", "> ${task.request}")
                task.messages.forEachIndexed { index, text -> Markdown("${task.id}:message:$index", text) }
                if (task.result.isNotEmpty()) Markdown("${task.id}:result", task.result)
                if (task.children.isNotEmpty()) SubTaskTree(task.children, depth + 1, onOpen)
                task.requestId?.let { TextButton({ onOpen(it) }) { Text("查看过程", fontSize = 11.sp) } }
            }
        }
    }
}

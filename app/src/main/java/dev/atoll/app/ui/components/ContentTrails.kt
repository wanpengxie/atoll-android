package dev.atoll.app.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.atoll.app.ui.screens.*
import dev.atoll.app.ui.theme.AtollColors
import dev.atoll.app.ui.theme.AtollMono
import kotlinx.coroutines.delay

/** While running: the title, run time and the last two steps. Finished: a collapsed record count. */
@Composable
fun ProgressTrail(state: ProgressTrailViewState, onOpenRecord: (String) -> Unit) {
    var expanded by remember(state.title) { mutableStateOf(false) }
    val now by produceState(System.currentTimeMillis(), state.running) {
        if (state.running) while (true) { value = System.currentTimeMillis(); delay(1000) }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).clip(RoundedCornerShape(10.dp)).clickable { expanded = !expanded }
            .semantics { stateDescription = if (expanded) "已展开" else "已折叠" }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.running) Spinner()
            Text(if (state.running) "处理中: ${state.title}" else "${state.records.size} 条过程记录", Modifier.weight(1f),
                fontSize = 13.sp, fontWeight = if (state.running) FontWeight.Medium else FontWeight.Normal,
                color = if (state.running) AtollColors.RunningText else AtollColors.Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (state.running) Text("运行 ${activityDuration(state.startedAtMillis, now)} ${state.startTime}", fontSize = 12.sp,
                fontFamily = AtollMono, color = AtollColors.RunningText)
            Text(if (expanded) "收起" else "展开", fontSize = 12.sp, color = AtollColors.Muted)
        }
        val visible = if (expanded) state.records else if (state.running) state.records.takeLast(2) else emptyList()
        visible.forEachIndexed { index, record ->
            val last = index == visible.lastIndex
            Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).clip(RoundedCornerShape(8.dp))
                .clickable(enabled = !record.stateOnly) { onOpenRecord(record.id) }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RecordMark(record, active = state.running && last)
                Text(record.line, Modifier.weight(1f), fontSize = 12.sp, lineHeight = 17.sp,
                    fontFamily = if (record.kind == ProcessKind.Tool) AtollMono else null,
                    color = if (state.running && last) AtollColors.RunningText else AtollColors.Body, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(record.time, fontSize = 11.sp, color = AtollColors.Muted)
            }
        }
    }
}

@Composable
private fun RecordMark(record: ProcessRecordViewState, active: Boolean) {
    val failed = record.line.endsWith("失败")
    Box(Modifier.size(18.dp).clip(CircleShape).background(when { active -> AtollColors.RunningSurface; failed -> AtollColors.DangerSurface; else -> AtollColors.Sunken }),
        contentAlignment = Alignment.Center) {
        when {
            active -> Spinner(10.dp)
            failed -> Icon(Icons.Outlined.Close, null, Modifier.size(11.dp), tint = AtollColors.Danger)
            else -> Icon(Icons.Outlined.Check, null, Modifier.size(11.dp), tint = AtollColors.Muted)
        }
    }
}

/** More than three sub-tasks collapse into one summary row. */
@Composable
fun SubTaskTree(tasks: List<SubTaskViewState>, depth: Int = 0, onOpen: (String) -> Unit = {}) {
    var groupExpanded by remember(tasks.map { it.id }) { mutableStateOf(tasks.size <= 3) }
    Column(Modifier.padding(start = if (depth > 0) 12.dp else 0.dp, top = 2.dp, bottom = 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (tasks.size > 3) {
            val running = tasks.count { it.phase == SubTaskPhase.Running }
            TextChip("子 Agent ${tasks.size} 个 · $running 进行中 · ${tasks.count { it.phase == SubTaskPhase.Failed }} 失败",
                tone = if (running > 0) Tone.Running else null, onClick = { groupExpanded = !groupExpanded })
        }
        if (groupExpanded) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(AtollColors.Surface)
            .border(1.dp, AtollColors.Line, RoundedCornerShape(12.dp))) {
            tasks.forEachIndexed { index, task ->
                if (index > 0) HorizontalDivider(color = AtollColors.LineSoft)
                SubTaskLeaf(task, depth, onOpen)
            }
        }
    }
}
@Composable private fun SubTaskLeaf(task: SubTaskViewState, depth: Int, onOpen: (String) -> Unit) {
    var expanded by remember(task.id) { mutableStateOf(false) }
    val now by produceState(System.currentTimeMillis(), task.phase) {
        if (task.phase == SubTaskPhase.Running) while (true) { value = System.currentTimeMillis(); delay(1000) }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.semantics { stateDescription = if (expanded) "已展开" else "已折叠" },
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.padding(top = 4.dp)) {
                when (task.phase) {
                    SubTaskPhase.Running -> PulseDot(size = 8.dp)
                    SubTaskPhase.Failed -> Icon(Icons.Outlined.Close, null, Modifier.size(14.dp), tint = AtollColors.Danger)
                    SubTaskPhase.Completed -> Icon(Icons.Outlined.Check, null, Modifier.size(14.dp), tint = AtollColors.Muted)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${task.kind.label} · ${task.phase.label} · ${task.owner}", fontSize = 12.sp,
                    color = if (task.phase == SubTaskPhase.Failed) AtollColors.Danger else AtollColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(task.title, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
                if (task.latest.isNotEmpty()) Text(task.latest, fontSize = 12.sp, fontFamily = AtollMono, color = AtollColors.Muted,
                    maxLines = if (expanded) Int.MAX_VALUE else 1, overflow = TextOverflow.Ellipsis)
            }
            if (task.phase == SubTaskPhase.Running) Text(runningFor(task.startedAtMillis, now), fontSize = 12.sp, color = AtollColors.RunningText)
        }
        if (expanded) {
            if (task.request.isNotEmpty()) Markdown("${task.id}:request", "> ${task.request}")
            task.messages.forEachIndexed { index, text -> Markdown("${task.id}:message:$index", text) }
            if (task.result.isNotEmpty()) Markdown("${task.id}:result", task.result)
            if (task.children.isNotEmpty()) SubTaskTree(task.children, depth + 1, onOpen)
            task.requestId?.let { TextButton({ onOpen(it) }, Modifier.heightIn(min = 40.dp)) { Text("查看过程", fontSize = 13.sp, color = AtollColors.Lagoon) } }
        }
    }
}

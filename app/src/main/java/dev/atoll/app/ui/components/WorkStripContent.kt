package dev.atoll.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.atoll.app.ui.screens.*
import dev.atoll.app.ui.theme.AtollColors
import dev.atoll.app.ui.theme.AtollMono
import kotlinx.coroutines.delay

private const val COLLABORATION = 1
private const val TASKS = 2
private const val CALLS = 4
private const val PREVIEW_LIMIT = 3

/** L0 counts; L1 bounded plain-text previews; full details live in the process sheet. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WorkStrip(message: MessageViewState, onProcess: () -> Unit,
    onChildProcess: (String) -> Unit, inset: Boolean, onExpanded: ((Boolean) -> Unit)? = null) {
    if (!showTurnWorkStrip(message)) return
    var expanded by rememberSaveable(message.id) { mutableIntStateOf(0) }
    // Restore the parent tracker only for an already-open strip; closed rows launch no effects.
    if (expanded != 0 && onExpanded != null) LaunchedEffect(message.id) { onExpanded(true) }
    val groups = remember(message.relatedCalls) { message.relatedCalls.partition { it.conversation } }
    val records = message.trail?.records.orEmpty()
    val running = message.status == MessageStatus.Processing || message.trail?.running == true
    fun toggle(bit: Int) { expanded = expanded xor bit; onExpanded?.invoke(expanded != 0) }
    Column(Modifier.fillMaxWidth().then(if (inset) Modifier.background(AtollColors.Raised) else Modifier)
        .padding(horizontal = if (inset) 12.dp else 0.dp, vertical = if (inset) 10.dp else 0.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (inset) HorizontalDivider(color = AtollColors.LineSoft)
        val latest = message.progress ?: records.lastOrNull()?.line
        if (latest != null || running) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (running) Spinner(13.dp)
            Text(latest ?: "思考中…", Modifier.weight(1f), fontSize = 13.sp,
                color = if (running) AtollColors.RunningText else AtollColors.Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (running) WorkElapsed(message.trail?.startedAtMillis ?: 0)
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (records.isNotEmpty()) WorkCountChip("过程 ${records.size} ›", null, onClick = onProcess)
            if (groups.first.isNotEmpty()) WorkCountChip("协作 ${groups.first.size}", expanded and COLLABORATION != 0,
                onClick = { toggle(COLLABORATION) })
            if (message.subTasks.isNotEmpty()) WorkCountChip("子 Agent ${message.subTasks.size}", expanded and TASKS != 0,
                onClick = { toggle(TASKS) })
            if (groups.second.isNotEmpty()) WorkCountChip("调用 ${groups.second.size}", expanded and CALLS != 0,
                danger = groups.second.any { it.status == MessageStatus.Failed }, onClick = { toggle(CALLS) })
            if (expanded != 0) TextButton({ expanded = 0; onExpanded?.invoke(false) }, Modifier.heightIn(min = 44.dp)) {
                Text("收起", fontSize = 12.sp, color = AtollColors.Muted)
            }
        }
        if (expanded != 0 && records.isNotEmpty()) {
            WorkHeading("最近两步")
            records.takeLast(2).forEachIndexed { index, record -> key(record.id) {
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(onClick = onProcess),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WorkMark(if (record.line.endsWith("失败")) MessageStatus.Failed else
                        if (running && index == minOf(records.size, 2) - 1) MessageStatus.Processing else MessageStatus.Completed)
                    Text(record.line, Modifier.weight(1f), fontSize = 12.sp, fontFamily = AtollMono,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(record.time, fontSize = 12.sp, color = AtollColors.Muted)
                }
            } }
        }
        if (expanded and COLLABORATION != 0 && groups.first.isNotEmpty()) WorkPreview("协作", groups.first.size, onProcess) {
            groups.first.take(PREVIEW_LIMIT).forEach { call -> key(call.id) {
                CallSummary(call, { onChildProcess(call.id) })
            } }
        }
        if (expanded and TASKS != 0 && message.subTasks.isNotEmpty()) WorkPreview("子 Agent · ${message.subTasks.count { it.phase == SubTaskPhase.Running }} 进行中",
            message.subTasks.size, onProcess) {
            message.subTasks.take(PREVIEW_LIMIT).forEach { task -> key(task.id) {
                SubTaskSummary(task) { task.requestId?.let(onChildProcess) ?: onProcess() }
            } }
        }
        if (expanded and CALLS != 0 && groups.second.isNotEmpty()) WorkPreview("关联调用 · ${groups.second.count { it.status == MessageStatus.Failed }} 失败",
            groups.second.size, onProcess) {
            groups.second.take(PREVIEW_LIMIT).forEach { call -> key(call.id) {
                CallSummary(call, { onChildProcess(call.id) })
            } }
        }
        if (expanded != 0 || showTurnProcessLink(message) && records.isEmpty() && groups.first.isEmpty() && groups.second.isEmpty() && message.subTasks.isEmpty())
            TextButton(onProcess, Modifier.align(Alignment.CenterHorizontally).heightIn(min = 44.dp)) {
                Text(if (expanded != 0) "在面板中查看全部 ›" else "查看过程 ›", fontSize = 13.sp, color = AtollColors.Lagoon)
            }
    }
}

@Composable
private fun WorkCountChip(text: String, selected: Boolean?, danger: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 44.dp).widthIn(min = 44.dp).clickable(role = Role.Button, onClick = onClick)
        .semantics { if (selected != null) { this.selected = selected; stateDescription = if (selected) "已展开" else "已折叠" } },
        contentAlignment = Alignment.Center) {
        Box(Modifier.height(26.dp).clip(RoundedCornerShape(13.dp))
            .background(if (selected == true) AtollColors.Ink else AtollColors.Surface)
            .border(1.dp, if (selected == true) AtollColors.Ink else AtollColors.Line, RoundedCornerShape(13.dp))
            .padding(horizontal = 9.dp), contentAlignment = Alignment.Center) {
            Text(text, fontSize = 12.sp, color = if (selected == true) Color.White else if (danger) AtollColors.Danger else AtollColors.Ink)
        }
    }
}

@Composable private fun WorkPreview(title: String, count: Int, onAll: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    WorkHeading(title)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(AtollColors.Surface)
        .border(1.dp, AtollColors.Line, RoundedCornerShape(12.dp)), content = content)
    if (count > PREVIEW_LIMIT) TextButton(onAll, Modifier.heightIn(min = 44.dp)) {
        Text("查看全部 $count 项 ›", fontSize = 12.sp, color = AtollColors.Lagoon)
    }
}

@Composable internal fun WorkHeading(text: String) {
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = AtollColors.Muted)
}

/** Shared compact rows. They never parse an answer, compose JSON, or recurse into children. */
@Composable
internal fun CallSummary(call: RelatedCallViewState, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (call.conversation) ActorAvatar(call.sender, agent = true, size = 18.dp)
            Text(if (call.conversation) "${call.sender} › ${call.recipients.joinToString("、")}" else call.title,
                Modifier.weight(1f), fontSize = if (call.conversation) 13.sp else 12.sp,
                fontFamily = if (call.conversation) null else AtollMono,
                color = if (call.status == MessageStatus.Failed) AtollColors.Danger else AtollColors.Ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            WorkMark(call.status)
            Text(call.status.label, fontSize = 12.sp, color = if (call.status == MessageStatus.Failed) AtollColors.Danger else AtollColors.Muted)
        }
        if (call.conversation) {
            Text(call.title, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (call.text.isNotBlank()) Text("↳ ${call.text.take(180).replace('\n', ' ')}", fontSize = 13.sp,
                color = AtollColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    HorizontalDivider(color = AtollColors.LineSoft)
}

@Composable
internal fun SubTaskSummary(task: SubTaskViewState, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        WorkMark(when (task.phase) { SubTaskPhase.Running -> MessageStatus.Processing; SubTaskPhase.Completed -> MessageStatus.Completed; SubTaskPhase.Failed -> MessageStatus.Failed })
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("${task.owner} · ${task.title}", fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(task.latest.ifEmpty { "${task.kind.label} · ${task.phase.label}" }, fontSize = 12.sp,
                fontFamily = if (task.latest.isNotEmpty()) AtollMono else null, color = AtollColors.Muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (task.phase == SubTaskPhase.Running) WorkElapsed(task.startedAtMillis)
    }
    HorizontalDivider(color = AtollColors.LineSoft)
}

@Composable internal fun WorkMark(status: MessageStatus) {
    when (status) {
        MessageStatus.Processing -> Spinner(13.dp)
        MessageStatus.Queued -> PulseDot(AtollColors.Warning, pulsing = false)
        MessageStatus.Failed -> Icon(Icons.Outlined.Close, null, Modifier.size(14.dp), tint = AtollColors.Danger)
        MessageStatus.Completed -> Icon(Icons.Outlined.Check, null, Modifier.size(14.dp), tint = AtollColors.Muted)
    }
}

/** Only this tiny leaf reads the clock, so ticking does not recompose a work strip or turn body. */
@Composable internal fun WorkElapsed(startedAt: Long) {
    if (startedAt <= 0) return
    val now by produceState(System.currentTimeMillis(), startedAt) {
        while (true) { delay(1000); value = System.currentTimeMillis() }
    }
    Text(activityDuration(startedAt, now), fontSize = 12.sp, fontFamily = AtollMono, color = AtollColors.RunningText)
}

package dev.atoll.app.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.atoll.app.ui.components.*
import dev.atoll.app.ui.theme.AtollColors
import dev.atoll.app.ui.theme.AtollMono
import dev.atoll.app.ui.theme.AtollShapes
import kotlinx.coroutines.delay

/** Full-screen secondary page on the paper ground: title, 关闭, then content. */
@Composable
fun ContentPanel(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(AtollColors.Ground).safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().background(AtollColors.Surface).heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                TextButton(onClose, Modifier.heightIn(min = 44.dp)) { Text("关闭", fontSize = 15.sp, color = AtollColors.Muted) }
            }
            HorizontalDivider(color = AtollColors.Line)
            content()
        }
    }
}
@Composable
fun ProcessPanel(state: ProcessPanelViewState, onClose: () -> Unit, actions: (@Composable () -> Unit)? = null) {
    var selected by remember(state.requestId, state.selectedRecordId) { mutableStateOf(state.selectedRecordId) }
    var ledger by remember(state.requestId) { mutableStateOf(false) }
    ContentPanel("过程 · ${state.title}", onClose) {
        Row(Modifier.fillMaxWidth().background(AtollColors.Surface).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val tone = when (state.status) { "completed" -> Tone.Neutral; "failed" -> Tone.Danger; "processing" -> Tone.Running; else -> Tone.Waiting }
            StatusPill(when (state.status) { "completed" -> "已完成"; "failed" -> "失败"; "processing" -> "处理中"; "queued" -> "排队中"; "pending" -> "等待中"; else -> state.status },
                tone, live = state.status == "processing")
            Text("${state.records.size} 条过程记录", fontSize = 13.sp, color = AtollColors.Muted)
        }
        actions?.invoke()
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp)) {
            if (state.records.isEmpty()) item { Text("这一轮还没有过程记录。", Modifier.padding(vertical = 12.dp), color = AtollColors.Muted, fontSize = 14.sp) }
            itemsIndexed(state.records, key = { _, record -> record.id }) { index, record ->
                val last = index == state.records.lastIndex
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.width(20.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                        val failed = record.line.endsWith("失败")
                        Box(Modifier.size(20.dp).clip(CircleShape).background(if (failed) AtollColors.DangerSurface else AtollColors.Sunken), contentAlignment = Alignment.Center) {
                            Text(if (record.kind == ProcessKind.Tool) "⚙" else "·", fontSize = 11.sp, color = if (failed) AtollColors.Danger else AtollColors.Muted)
                        }
                        if (!last) Box(Modifier.width(1.5.dp).weight(1f).background(AtollColors.Line))
                    }
                    Column(Modifier.weight(1f).padding(bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 32.dp).clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = !record.stateOnly) { selected = if (selected == record.id) null else record.id },
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(record.line, Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Medium,
                                fontFamily = if (record.kind == ProcessKind.Tool) AtollMono else null, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(record.time, color = AtollColors.Muted, fontSize = 12.sp)
                        }
                        if (selected == record.id && !record.stateOnly) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(AtollColors.Surface)
                            .border(1.dp, AtollColors.Line, RoundedCornerShape(12.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (record.kind == ProcessKind.Tool) {
                                record.input?.let { PanelLabel("工具输入输出"); JsonTree(it) }
                                if (record.outputText.isNotEmpty()) { PanelLabel("输出"); Markdown("${record.id}:output", boundedToolOutput(record.outputText)) }
                                else record.output?.let { PanelLabel("输出"); JsonTree(it) }
                                if (record.body.isNotEmpty()) { PanelLabel("执行说明"); Markdown("${record.id}:body", record.body) }
                                if (record.input == null && record.output == null && record.outputText.isEmpty() && record.body.isEmpty()) Text("这次调用没有返回可展示的数据。", color = AtollColors.Muted, fontSize = 13.sp)
                            } else if (record.body.isNotEmpty()) Markdown("${record.id}:body", record.body)
                            else Text("这次过程没有留下可展示的正文。", color = AtollColors.Muted, fontSize = 13.sp)
                        }
                    }
                }
            }
            item("ledger") {
                Column(Modifier.padding(top = 8.dp)) {
                    TextButton({ ledger = !ledger }) { Text("账本信息 ${if (ledger) "⌃" else "⌄"}", color = AtollColors.Muted) }
                    if (ledger) GroupCard {
                        LedgerFact("请求编号", state.requestId); LedgerFact("类型", state.type); LedgerFact("状态", state.status)
                        state.requestSeq?.let { LedgerFact("账本序号", it.toString()) }
                        state.terminalId?.let { LedgerFact("终态编号", it) }
                        state.records.forEach { record -> record.identifiers.forEach { (label, value) -> LedgerFact(label, value) } }
                        if (state.detail.isNotEmpty()) Text(state.detail, Modifier.padding(12.dp), fontSize = 13.sp, color = AtollColors.Muted)
                    }
                }
            }
        }
    }
}
@Composable private fun PanelLabel(text: String) { Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = AtollColors.Muted) }
@Composable private fun LedgerFact(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.width(80.dp), fontSize = 12.sp, color = AtollColors.Muted)
        Text(value, Modifier.weight(1f), fontSize = 12.sp, fontFamily = AtollMono)
    }
}
@Composable
fun RunningDrawer(tasks: List<SubTaskViewState>, onJump: (String) -> Boolean, onClose: () -> Unit) {
    val running = tasks.filter { it.phase == SubTaskPhase.Running }
    var missing by remember { mutableStateOf<String?>(null) }
    val now by produceState(System.currentTimeMillis(), running.isNotEmpty()) {
        if (running.isNotEmpty()) while (true) { value = System.currentTimeMillis(); delay(1000) }
    }
    ContentPanel("运行中${if (running.isNotEmpty()) " · ${running.size}" else ""}", onClose) {
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (running.isEmpty()) item { PanelEmpty("没有正在运行的后台任务", "Agent 派出的子 Agent 和后台命令在运行时会出现在这里，点一下回到派出它的消息。") }
            items(running, key = { it.id }) { task ->
                Column(Modifier.fillMaxWidth().clip(AtollShapes.Card).background(AtollColors.Surface).border(1.dp, AtollColors.Line, AtollShapes.Card).clickable {
                    if (task.requestId != null && onJump(task.requestId)) { missing = null; onClose() } else missing = task.id
                }.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PulseDot(size = 8.dp)
                        Text("${task.kind.label} · ${task.owner}", Modifier.weight(1f), fontSize = 12.sp, color = AtollColors.Muted)
                        Text(runningFor(task.startedAtMillis, now), fontSize = 12.sp, color = AtollColors.RunningText)
                    }
                    Text(task.title.ifEmpty { "（未命名任务）" }, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    if (task.latest.isNotEmpty()) Text(task.latest, fontSize = 13.sp, fontFamily = AtollMono, color = AtollColors.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (missing == task.id) Text(if (task.requestId == null) "这个任务没有记下是哪条消息派出的。" else "派出它的消息还没加载到页面上，往上翻到它附近后再点。", color = AtollColors.Warning, fontSize = 13.sp)
                }
            }
        }
    }
}
@Composable
fun StarredDrawer(stars: List<StarredMessageViewState>, onJump: (String) -> Boolean, onRemove: (String) -> Unit, onClose: () -> Unit) {
    var missing by remember { mutableStateOf<String?>(null) }
    ContentPanel("星标", onClose) {
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (stars.isEmpty()) item { PanelEmpty("还没有星标消息", "长按一条消息选择“星标”，它会出现在这里，点一下就能回到那条消息。") }
            items(stars, key = { it.id }) { star -> Row(Modifier.fillMaxWidth().clip(AtollShapes.Card).background(AtollColors.Surface).border(1.dp, AtollColors.Line, AtollShapes.Card)) {
                Column(Modifier.weight(1f).clickable { if (onJump(star.id)) { missing = null; onClose() } else missing = star.id }.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActorAvatar(star.sender, star.sender, agent = true, size = 22.dp)
                        Text(star.sender, Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(star.time, fontSize = 12.sp, color = AtollColors.Muted)
                    }
                    Text(star.text.ifEmpty { "（无文字内容）" }, fontSize = 15.sp, lineHeight = 22.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    if (missing == star.id) Text("这条消息还没加载到页面上，往上翻到它附近后再点。", color = AtollColors.Warning, fontSize = 13.sp)
                }
                IconButton({ onRemove(star.id) }, Modifier.size(44.dp)) { Text("×", fontSize = 18.sp, color = AtollColors.Muted) }
            } }
        }
    }
}
@Composable private fun PanelEmpty(title: String, description: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(description, color = AtollColors.Muted, fontSize = 14.sp, lineHeight = 21.sp, textAlign = TextAlign.Center)
    }
}

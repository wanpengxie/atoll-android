package dev.atoll.app.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import kotlinx.coroutines.delay

@Composable
fun ContentPanel(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(AtollColors.Workspace).safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f).padding(start = 16.dp), style = MaterialTheme.typography.titleMedium, maxLines = 2)
                TextButton(onClose, Modifier.size(44.dp)) { Text("×", fontSize = 20.sp, color = AtollColors.Muted) }
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
        actions?.invoke()
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (state.records.isEmpty()) item { Text("这一轮还没有过程记录。", color = AtollColors.Muted, fontSize = 12.sp) }
            items(state.records, key = { it.id }) { record ->
                Surface(shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, AtollColors.Line)) {
                    Column(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().clickable(enabled = !record.stateOnly) { selected = if (selected == record.id) null else record.id }.padding(10.dp)) {
                            Text(record.line, Modifier.weight(1f), fontSize = 12.sp)
                            Text(record.time, color = AtollColors.Muted, fontSize = 10.sp)
                        }
                        if (selected == record.id && !record.stateOnly) Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (record.kind == ProcessKind.Tool) {
                                record.input?.let { Text("工具输入输出", fontSize = 11.sp, fontWeight = FontWeight.SemiBold); JsonTree(it) }
                                if (record.outputText.isNotEmpty()) { Text("输出", fontSize = 11.sp, fontWeight = FontWeight.SemiBold); Markdown("${record.id}:output", boundedToolOutput(record.outputText)) }
                                else record.output?.let { Text("输出", fontSize = 11.sp, fontWeight = FontWeight.SemiBold); JsonTree(it) }
                                if (record.body.isNotEmpty()) { Text("执行说明", fontSize = 11.sp, fontWeight = FontWeight.SemiBold); Markdown("${record.id}:body", record.body) }
                                if (record.input == null && record.output == null && record.outputText.isEmpty() && record.body.isEmpty()) Text("这次调用没有返回可展示的数据。", color = AtollColors.Muted, fontSize = 12.sp)
                            } else if (record.body.isNotEmpty()) Markdown("${record.id}:body", record.body)
                            else Text("这次过程没有留下可展示的正文。", color = AtollColors.Muted, fontSize = 12.sp)
                        }
                    }
                }
            }
            item("ledger") {
                TextButton({ ledger = !ledger }) { Text("账本信息 ${if (ledger) "⌃" else "⌄"}", color = AtollColors.Muted) }
                if (ledger) Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LedgerFact("请求编号", state.requestId); LedgerFact("类型", state.type); LedgerFact("状态", state.status)
                    state.requestSeq?.let { LedgerFact("账本序号", it.toString()) }
                    state.terminalId?.let { LedgerFact("终态编号", it) }
                    state.records.forEach { record -> record.identifiers.forEach { (label, value) -> LedgerFact(label, value) } }
                    if (state.detail.isNotEmpty()) Text(state.detail, fontSize = 12.sp, color = AtollColors.Muted)
                }
            }
        }
    }
}
@Composable private fun LedgerFact(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { Text(label, Modifier.width(70.dp), fontSize = 11.sp, color = AtollColors.Muted); Text(value, fontSize = 11.sp) }
}
@Composable
fun RunningDrawer(tasks: List<SubTaskViewState>, onJump: (String) -> Boolean, onClose: () -> Unit) {
    val running = tasks.filter { it.phase == SubTaskPhase.Running }
    var missing by remember { mutableStateOf<String?>(null) }
    val now by produceState(System.currentTimeMillis(), running.isNotEmpty()) {
        if (running.isNotEmpty()) while (true) { value = System.currentTimeMillis(); delay(1000) }
    }
    ContentPanel("运行中${if (running.isNotEmpty()) " · ${running.size}" else ""}", onClose) {
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (running.isEmpty()) item { PanelEmpty("没有正在运行的后台任务", "Agent 派出的子 Agent 和后台命令在运行时会出现在这里，点一下回到派出它的消息。") }
            items(running, key = { it.id }) { task ->
                Column(Modifier.fillMaxWidth().clickable {
                    if (task.requestId != null && onJump(task.requestId)) { missing = null; onClose() } else missing = task.id
                }.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("${task.kind.label} · ${task.owner} · ${runningFor(task.startedAtMillis, now)}", fontSize = 11.sp, color = AtollColors.Muted)
                    Text(task.title.ifEmpty { "（未命名任务）" }, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    if (task.latest.isNotEmpty()) Text(task.latest, fontSize = 12.sp, color = AtollColors.Muted)
                    if (missing == task.id) Text(if (task.requestId == null) "这个任务没有记下是哪条消息派出的。" else "派出它的消息还没加载到页面上，往上翻到它附近后再点。", color = AtollColors.Warning, fontSize = 12.sp)
                }
                HorizontalDivider(color = AtollColors.Line)
            }
        }
    }
}
@Composable
fun StarredDrawer(stars: List<StarredMessageViewState>, onJump: (String) -> Boolean, onRemove: (String) -> Unit, onClose: () -> Unit) {
    var missing by remember { mutableStateOf<String?>(null) }
    ContentPanel("星标", onClose) {
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (stars.isEmpty()) item { PanelEmpty("还没有星标消息", "在消息下方点“☆ 星标”，它会出现在这里，点一下就能回到那条消息。") }
            items(stars, key = { it.id }) { star -> Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f).clickable { if (onJump(star.id)) { missing = null; onClose() } else missing = star.id }.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth()) { Text(star.sender, Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold); Text(star.time, fontSize = 10.sp, color = AtollColors.Muted) }
                    Text(star.text.ifEmpty { "（无文字内容）" }, fontSize = 12.sp, color = AtollColors.Muted)
                    if (missing == star.id) Text("这条消息还没加载到页面上，往上翻到它附近后再点。", color = AtollColors.Warning, fontSize = 12.sp)
                }
                TextButton({ onRemove(star.id) }, Modifier.size(44.dp)) { Text("×", color = AtollColors.Muted) }
            }; HorizontalDivider(color = AtollColors.Line) }
        }
    }
}
@Composable private fun PanelEmpty(title: String, description: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(description, color = AtollColors.Muted, fontSize = 12.sp)
    }
}

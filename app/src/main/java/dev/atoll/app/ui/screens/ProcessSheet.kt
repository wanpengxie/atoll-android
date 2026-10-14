package dev.atoll.app.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.atoll.app.ui.components.*
import dev.atoll.app.ui.theme.AtollColors
import dev.atoll.app.ui.theme.AtollMono

enum class ProcessTab(val label: String) { Process("过程"), Collaboration("协作"), Tasks("子任务"), Calls("调用"), Ledger("账本") }

/** One turn, one selected page. Hidden tabs and closed records never compose their rich content. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProcessPanel(state: ProcessPanelViewState, onClose: () -> Unit, actions: (@Composable () -> Unit)? = null,
    onLink: ((String) -> Unit)? = null, onAttachment: MessageAttachmentHandler? = null,
    onProcess: (String) -> Unit = {}, onOpenRequest: (String) -> Unit = {},
    onStop: (() -> Unit)? = null, onCancel: (() -> Unit)? = null,
    onGuide: ((String) -> Unit)? = null, controlsBusy: Boolean = false, guideError: String = "",
    initialTab: ProcessTab = ProcessTab.Process,
    collaborationContent: (@Composable () -> Unit)? = null) {
    var tab by rememberSaveable(state.requestId) { mutableStateOf(initialTab) }
    var selected by rememberSaveable(state.requestId, state.selectedRecordId) { mutableStateOf(state.selectedRecordId) }
    val calls = remember(state.relatedCalls) { state.relatedCalls.filterNot { it.conversation } }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheet, containerColor = AtollColors.Surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), scrimColor = AtollColors.Ink.copy(alpha = .32f),
        dragHandle = { Box(Modifier.padding(top = 8.dp, bottom = 4.dp).size(36.dp, 4.dp).clip(CircleShape).background(AtollColors.ControlLine)) }) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.94f).imePadding().semantics { contentDescription = "过程面板" }) {
            ProcessHeader(state, onClose)
            state.request?.text?.takeIf(String::isNotBlank)?.let { request ->
                Text(request, Modifier.padding(horizontal = 20.dp, vertical = 6.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp)).background(AtollColors.Sunken).padding(horizontal = 12.dp, vertical = 10.dp),
                    fontSize = 13.sp, lineHeight = 19.sp, color = AtollColors.Body, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            if (onStop != null || onCancel != null) Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onStop != null) OutlinedButton(onStop, Modifier.heightIn(min = 44.dp), enabled = !controlsBusy) { Text("中断本轮", fontSize = 13.sp) }
                if (onCancel != null) OutlinedButton(onCancel, Modifier.heightIn(min = 44.dp), enabled = !controlsBusy,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AtollColors.Danger)) { Text("取消请求", fontSize = 13.sp) }
            }
            actions?.invoke()
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                ProcessTab.entries.forEach { choice ->
                    val active = tab == choice
                    Column(Modifier.width(IntrinsicSize.Max).heightIn(min = 44.dp).clickable(role = Role.Tab) { tab = choice }
                        .semantics { this.selected = active }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.height(42.dp), contentAlignment = Alignment.Center) {
                            Text(choice.label, fontSize = 14.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (active) AtollColors.Ink else AtollColors.Muted)
                        }
                        Box(Modifier.fillMaxWidth().height(2.dp).background(if (active) AtollColors.Ink else Color.Transparent))
                    }
                }
            }
            HorizontalDivider(color = AtollColors.Line)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    ProcessTab.Process -> ProcessRecords(state, selected, { selected = if (selected == it) null else it }, onLink)
                    ProcessTab.Collaboration -> when {
                        collaborationContent != null -> collaborationContent()
                        state.collaboration != null -> CollaborationContent(state.collaboration, onOpenRequest = onOpenRequest,
                            onLink = onLink, onAttachment = onAttachment, onProcess = onProcess)
                        else -> ProcessEmpty("这一轮没有协作消息。")
                    }
                    ProcessTab.Tasks -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp)) {
                        if (state.subTasks.isEmpty()) item("empty") { ProcessEmpty("这一轮没有子任务。") }
                        items(state.subTasks, key = { it.id }, contentType = { "subtask" }) { task ->
                            SubTaskLeaf(task, 0, onProcess)
                            HorizontalDivider(color = AtollColors.LineSoft)
                        }
                    }
                    ProcessTab.Calls -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp)) {
                        if (calls.isEmpty()) item("empty") { ProcessEmpty("这一轮没有关联调用。") }
                        items(calls, key = { it.id }, contentType = { "call" }) { call ->
                            CallLeaf(state.requestId, call, onLink, onAttachment, onProcess)
                            HorizontalDivider(color = AtollColors.LineSoft)
                        }
                    }
                    ProcessTab.Ledger -> ProcessLedger(state)
                }
            }
            ProcessGuide(state, onGuide, controlsBusy, guideError)
        }
    }
}

@Composable private fun ProcessHeader(state: ProcessPanelViewState, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val agent = state.agent
        ActorAvatar(agent?.name ?: state.title, agent?.id ?: state.requestId, agent = agent?.id?.startsWith("human:") != true, size = 40.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("${agent?.name ?: state.title} · 过程", fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val running = state.status == "processing"
                StatusPill(when (state.status) { "completed" -> "已完成"; "failed" -> "失败"; "processing" -> "处理中"; "queued" -> "排队中"; "pending" -> "等待中"; else -> state.status },
                    when (state.status) { "processing" -> Tone.Running; "failed" -> Tone.Danger; "queued", "pending" -> Tone.Waiting; else -> Tone.Neutral }, live = running)
                if (running) WorkElapsed(state.startedAtMillis)
                if (state.startTime.isNotEmpty()) Text("${state.startTime} 开始", fontSize = 13.sp, color = AtollColors.Muted)
            }
        }
        IconButton(onClose, Modifier.size(44.dp)) { Icon(Icons.Outlined.Close, "关闭过程面板", Modifier.size(20.dp), tint = AtollColors.Muted) }
    }
}

@Composable private fun ProcessRecords(state: ProcessPanelViewState, selected: String?, onSelect: (String) -> Unit, onLink: ((String) -> Unit)?) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 24.dp)) {
        if (state.records.isEmpty()) item("empty") { ProcessEmpty("这一轮还没有过程记录。") }
        itemsIndexed(state.records, key = { _, record -> record.id }, contentType = { _, record -> record.kind }) { index, record ->
            val last = index == state.records.lastIndex
            // Connector draws after measurement; no IntrinsicSize pass over expanded Markdown/JSON.
            Row(Modifier.fillMaxWidth().drawBehind {
                if (!last) drawLine(AtollColors.Line, Offset(10.dp.toPx(), 20.dp.toPx()), Offset(10.dp.toPx(), size.height), strokeWidth = 1.5.dp.toPx())
            }, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.padding(top = 12.dp).size(20.dp).clip(CircleShape).background(AtollColors.Sunken), contentAlignment = Alignment.Center) {
                    WorkMark(if (record.line.endsWith("失败")) MessageStatus.Failed else if (last && state.status == "processing") MessageStatus.Processing else MessageStatus.Completed)
                }
                Column(Modifier.weight(1f).padding(bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = !record.stateOnly) { onSelect(record.id) }
                        .semantics { this.selected = selected == record.id }, verticalAlignment = Alignment.CenterVertically) {
                        Text(record.line, Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Medium,
                            fontFamily = if (record.kind == ProcessKind.Tool) AtollMono else null, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(record.time, Modifier.padding(start = 8.dp), color = AtollColors.Muted, fontSize = 12.sp)
                    }
                    if (selected == record.id && !record.stateOnly) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(AtollColors.Ground).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (record.kind == ProcessKind.Tool) {
                            record.input?.let { ProcessLabel("工具输入输出"); JsonTree(it) }
                            if (record.outputText.isNotEmpty()) { ProcessLabel("输出"); Markdown("${record.id}:output", boundedToolOutput(record.outputText), onLink = onLink) }
                            else record.output?.let { ProcessLabel("输出"); JsonTree(it) }
                        }
                        if (record.body.isNotEmpty()) { ProcessLabel("执行说明"); Markdown("${record.id}:body", record.body, onLink = onLink) }
                        if (record.input == null && record.output == null && record.outputText.isEmpty() && record.body.isEmpty())
                            Text("这次过程没有留下可展示的正文。", color = AtollColors.Muted, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable private fun ProcessLedger(state: ProcessPanelViewState) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp)) {
        item("request") {
            LedgerFact("请求编号", state.requestId); LedgerFact("类型", state.type); LedgerFact("状态", state.status)
            state.requestSeq?.let { LedgerFact("账本序号", it.toString()) }
            state.terminalId?.let { LedgerFact("终态编号", it) }
        }
        items(state.records, key = { it.id }, contentType = { "ledger" }) { record ->
            record.identifiers.forEach { (label, value) -> LedgerFact(label, value) }
        }
        if (state.detail.isNotEmpty()) item("detail") { Text(state.detail, Modifier.padding(vertical = 12.dp), fontSize = 13.sp, color = AtollColors.Muted) }
    }
}

@Composable private fun ProcessGuide(state: ProcessPanelViewState, onGuide: ((String) -> Unit)?, busy: Boolean, error: String) {
    var text by rememberSaveable(state.requestId) { mutableStateOf("") }
    val enabled = onGuide != null && state.status == "processing" && state.executionTurnId.isNotEmpty() && !busy
    fun send() { if (enabled && text.isNotBlank()) onGuide?.invoke(text.trim()) }
    HorizontalDivider(color = AtollColors.Line)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicTextField(text, { text = it }, Modifier.weight(1f).heightIn(min = 44.dp)
                .clip(RoundedCornerShape(22.dp)).border(1.dp, AtollColors.Line, RoundedCornerShape(22.dp))
                .semantics { contentDescription = "插话引导" }.padding(horizontal = 14.dp, vertical = 12.dp),
                enabled = enabled, maxLines = 3, textStyle = TextStyle(fontSize = 13.sp, lineHeight = 19.sp,
                    color = if (enabled) AtollColors.Ink else AtollColors.Faint),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }),
                decorationBox = { inner -> Box {
                    if (text.isEmpty()) Text("插话引导：补充指示，不打断本轮", fontSize = 13.sp,
                        color = AtollColors.Faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                } })
            IconButton(::send, Modifier.size(44.dp).clip(CircleShape).background(if (enabled && text.isNotBlank()) AtollColors.Ink else AtollColors.Sunken),
                enabled = enabled && text.isNotBlank()) { Icon(Icons.Outlined.ArrowUpward, "发送引导", Modifier.size(20.dp), tint = if (enabled && text.isNotBlank()) Color.White else AtollColors.Faint) }
        }
        if (error.isNotEmpty()) Text(error, Modifier.padding(top = 6.dp), color = AtollColors.Danger, fontSize = 12.sp)
    }
}

@Composable private fun ProcessEmpty(text: String) { Text(text, Modifier.padding(vertical = 24.dp, horizontal = 8.dp), color = AtollColors.Muted, fontSize = 14.sp) }
@Composable private fun ProcessLabel(text: String) { Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = AtollColors.Muted) }
@Composable private fun LedgerFact(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.width(80.dp), fontSize = 12.sp, color = AtollColors.Muted)
        Text(value, Modifier.weight(1f), fontSize = 12.sp, fontFamily = AtollMono)
    }
}

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
import dev.atoll.app.ui.theme.ChatColors
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
    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheet, containerColor = ChatColors.Block,
        shape = ChatSheetShape, scrimColor = ChatColors.Scrim, dragHandle = { ChatGrabber() }) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.94f).imePadding().semantics { contentDescription = "过程面板" }) {
            ProcessHeader(state, onClose)
            state.request?.text?.takeIf(String::isNotBlank)?.let { request ->
                Text(request, Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp).clip(RoundedCornerShape(4.dp)).background(ChatColors.Quote)
                    .padding(horizontal = 10.dp, vertical = 6.dp), fontSize = 13.sp, lineHeight = 19.sp, color = ChatColors.Gray, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (onStop != null || onCancel != null) Row(Modifier.padding(start = 4.dp, top = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (onStop != null) ProcessTextAction("中断本轮", ChatColors.Link, !controlsBusy, onStop)
                if (onCancel != null) ProcessTextAction("取消请求", ChatColors.Red, !controlsBusy, onCancel)
            }
            actions?.invoke()
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                ProcessTab.entries.forEach { choice ->
                    val active = tab == choice
                    val count = when (choice) {
                        ProcessTab.Process -> state.records.size
                        ProcessTab.Collaboration -> state.relatedCalls.count { it.conversation }
                        ProcessTab.Tasks -> state.subTasks.size
                        ProcessTab.Calls -> calls.size
                        ProcessTab.Ledger -> 0
                    }
                    Box(Modifier.heightIn(min = 44.dp).clickable(role = Role.Tab) { tab = choice }.semantics { this.selected = active },
                        contentAlignment = Alignment.Center) {
                        Text(if (count > 0) "${choice.label} $count" else choice.label, fontSize = 15.sp,
                            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal, color = if (active) ChatColors.Ink else ChatColors.Gray)
                    }
                }
            }
            HorizontalDivider(color = ChatColors.LineInWhite)
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
                            HorizontalDivider(color = ChatColors.LineInWhite)
                        }
                    }
                    ProcessTab.Calls -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp)) {
                        if (calls.isEmpty()) item("empty") { ProcessEmpty("这一轮没有关联调用。") }
                        items(calls, key = { it.id }, contentType = { "call" }) { call ->
                            CallLeaf(state.requestId, call, onLink, onAttachment, onProcess)
                            HorizontalDivider(color = ChatColors.LineInWhite)
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
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val agent = state.agent
        ActorAvatar(agent?.name ?: state.title, agent?.id ?: state.requestId, agent = agent?.id?.startsWith("human:") != true, size = 36.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(state.title.ifEmpty { "${agent?.name.orEmpty()} · 过程" }, fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium,
                color = ChatColors.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val running = state.status == "processing"
                if (running) ChatSpinner()
                Text(when (state.status) { "completed" -> "已完成"; "failed" -> "失败"; "processing" -> "处理中"; "queued" -> "排队中"; "pending" -> "等待中"; else -> state.status },
                    fontSize = 13.sp, color = if (state.status == "failed") ChatColors.Red else ChatColors.Gray)
                if (running) WorkElapsed(state.startedAtMillis)
                val tail = listOfNotNull(agent?.name, state.startTime.takeIf(String::isNotEmpty)?.let { "$it 开始" }).joinToString(" · ")
                if (tail.isNotEmpty()) Text("· $tail", fontSize = 13.sp, color = ChatColors.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text("收起", Modifier.heightIn(min = 44.dp).clickable(onClickLabel = "关闭过程面板", onClick = onClose).padding(horizontal = 12.dp).wrapContentHeight(),
            fontSize = 15.sp, color = ChatColors.Gray)
    }
}

@Composable private fun ProcessTextAction(label: String, color: Color, enabled: Boolean, onClick: () -> Unit) {
    Text(label, Modifier.heightIn(min = 44.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 12.dp).wrapContentHeight(),
        fontSize = 15.sp, color = if (enabled) color else ChatColors.Hint)
}

@Composable private fun ProcessRecords(state: ProcessPanelViewState, selected: String?, onSelect: (String) -> Unit, onLink: ((String) -> Unit)?) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp)) {
        if (state.records.isEmpty()) item("empty") { ProcessEmpty("这一轮还没有过程记录。") }
        itemsIndexed(state.records, key = { _, record -> record.id }, contentType = { _, record -> record.kind }) { index, record ->
            val last = index == state.records.lastIndex
            // Connector draws after measurement; no IntrinsicSize pass over expanded Markdown/JSON.
            Row(Modifier.fillMaxWidth().drawBehind {
                if (!last) drawLine(ChatColors.LineInWhite, Offset(8.dp.toPx(), 34.dp.toPx()), Offset(8.dp.toPx(), size.height), strokeWidth = 1.dp.toPx())
            }, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.padding(top = 14.dp).size(16.dp), contentAlignment = Alignment.Center) {
                    WorkMark(if (record.line.endsWith("失败")) MessageStatus.Failed else if (last && state.status == "processing") MessageStatus.Processing else MessageStatus.Completed)
                }
                Column(Modifier.weight(1f).padding(bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = !record.stateOnly) { onSelect(record.id) }
                        .semantics { this.selected = selected == record.id }, verticalAlignment = Alignment.CenterVertically) {
                        Text(record.line, Modifier.weight(1f), fontSize = 15.sp, lineHeight = 22.sp,
                            color = if (record.line.endsWith("失败")) ChatColors.Red else ChatColors.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(record.time, Modifier.padding(start = 8.dp), color = ChatColors.Gray, fontSize = 13.sp)
                    }
                    if (selected == record.id && !record.stateOnly) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(ChatColors.Bar).padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (record.kind == ProcessKind.Tool) {
                            record.input?.let { ProcessLabel("工具输入输出"); JsonTree(it) }
                            if (record.outputText.isNotEmpty()) { ProcessLabel("输出"); Markdown("${record.id}:output", boundedToolOutput(record.outputText), onLink = onLink) }
                            else record.output?.let { ProcessLabel("输出"); JsonTree(it) }
                        }
                        if (record.body.isNotEmpty()) { ProcessLabel("执行说明"); Markdown("${record.id}:body", record.body, onLink = onLink) }
                        if (record.input == null && record.output == null && record.outputText.isEmpty() && record.body.isEmpty())
                            Text("这次过程没有留下可展示的正文。", color = ChatColors.Gray, fontSize = 13.sp)
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
        if (state.detail.isNotEmpty()) item("detail") { Text(state.detail, Modifier.padding(vertical = 12.dp), fontSize = 13.sp, color = ChatColors.Gray) }
    }
}

@Composable private fun ProcessGuide(state: ProcessPanelViewState, onGuide: ((String) -> Unit)?, busy: Boolean, error: String) {
    var text by rememberSaveable(state.requestId) { mutableStateOf("") }
    val enabled = onGuide != null && state.status == "processing" && state.executionTurnId.isNotEmpty() && !busy
    fun send() { if (enabled && text.isNotBlank()) onGuide?.invoke(text.trim()) }
    Column(Modifier.fillMaxWidth().background(ChatColors.Bar)) {
        HorizontalDivider(color = ChatColors.Line)
        Row(Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicTextField(text, { text = it }, Modifier.weight(1f).heightIn(min = 40.dp).clip(RoundedCornerShape(4.dp)).background(ChatColors.Block)
                .semantics { contentDescription = "插话引导" }.padding(horizontal = 12.dp, vertical = 8.dp),
                enabled = enabled, maxLines = 3, textStyle = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, color = if (enabled) ChatColors.Ink else ChatColors.Hint),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(ChatColors.Green),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }),
                decorationBox = { inner -> Box {
                    if (text.isEmpty()) Text("插话引导：补充指示，不打断本轮", fontSize = 16.sp, lineHeight = 24.sp, color = ChatColors.Hint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                } })
            val ready = enabled && text.isNotBlank()
            Box(Modifier.height(40.dp).clip(RoundedCornerShape(4.dp)).background(if (ready) ChatColors.Green else ChatColors.Secondary)
                .clickable(enabled = ready, onClickLabel = "发送引导", onClick = ::send).semantics { contentDescription = "发送引导" }.padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center) { Text("发送", fontSize = 15.sp, color = if (ready) Color.White else ChatColors.Hint) }
        }
        if (error.isNotEmpty()) Text(error, Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp), color = ChatColors.Red, fontSize = 12.sp)
    }
}

@Composable private fun ProcessEmpty(text: String) { Text(text, Modifier.padding(vertical = 24.dp, horizontal = 8.dp), color = ChatColors.Gray, fontSize = 14.sp) }
@Composable private fun ProcessLabel(text: String) { Text(text, fontSize = 12.sp, color = ChatColors.Gray) }
@Composable private fun LedgerFact(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.width(80.dp), fontSize = 12.sp, color = ChatColors.Gray)
        Text(value, Modifier.weight(1f), fontSize = 12.sp, fontFamily = AtollMono)
    }
}

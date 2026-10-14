@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package dev.atoll.app.compose

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import dev.atoll.app.ui.components.*
import dev.atoll.app.ui.screens.MessageViewState
import dev.atoll.app.ui.theme.AtollColors
import dev.atoll.app.ui.theme.AtollMono
import kotlinx.coroutines.*

/** Host owns the channel-files picker and preview; selected resources enter addAttachments(). */
@Composable
fun AtollComposer(viewModel: ComposerViewModel, modifier: Modifier = Modifier, onPickChannelFiles: (() -> Unit)? = null, onPreviewAttachment: ((ComposerAttachment) -> Unit)? = null, showLocalEchoes: Boolean = true, editing: ComposerEditBinding? = null) {
    if (editing != null) { EditingComposer(editing, modifier.background(AtollColors.Ground)); return }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var focused by remember { mutableStateOf(false) }
    var editor by remember(viewModel, state.channelId) { mutableStateOf(TextFieldValue(state.draft.text)) }
    var dismissed by remember(state.channelId) { mutableStateOf(false) }
    var active by remember(state.channelId) { mutableIntStateOf(0) }
    var showSelector by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(state.editorCommand, state.channelId) {
        val command = state.editorCommand
        editor = TextFieldValue(command.text, TextRange(command.selectionStart.coerceIn(0, command.text.length), command.selectionEnd.coerceIn(0, command.text.length)))
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        scope.launch { viewModel.upload(uploadFiles(context, uris)) }
    }
    val resting = ComposerEditor.rests(state.draft, focused)
    val mention = if (!dismissed) ComposerDelivery.mention(editor.text, editor.selection.end, state.roster, state.selfId) else null
    val before = editor.text.take(editor.selection.end)
    val slash = if (!dismissed) Regex("(?:^|\n)/([^\\s/]*)$").find(before)?.groupValues?.get(1) else null
    val commandRows = if (slash != null) state.definitions.filter { it.name.contains(slash, true) }.take(8) else emptyList()
    val count = mention?.candidates?.size?.takeIf { it > 0 } ?: commandRows.size
    fun update(value: TextFieldValue, external: Boolean = false) {
        editor = value; dismissed = false; active = 0
        if (external) viewModel.replaceEditorText(value.text, value.selection.start, value.selection.end) else viewModel.onEditorText(value.text)
    }
    fun pick(index: Int) {
        val actor = mention?.candidates?.getOrNull(index)
        if (actor != null) {
            viewModel.addMention(actor, mention)
            editor = TextFieldValue(viewModel.state.value.draft.text, TextRange((mention!!.start).coerceAtMost(viewModel.state.value.draft.text.length)))
        } else commandRows.getOrNull(index)?.let { command ->
            val start = before.lastIndexOf('/')
            val next = editor.text.replaceRange(start, editor.selection.end, "/${command.name} ")
            update(TextFieldValue(next, TextRange(start + command.name.length + 2)), external = true)
        }
        dismissed = false; active = 0
    }
    fun newline() {
        val next = editor.text.replaceRange(editor.selection.min, editor.selection.max, "\n")
        update(TextFieldValue(next, TextRange(editor.selection.min + 1)), external = true)
    }
    val currentChoice = state.parameters?.current
    val agentLabel = when {
        state.delivery.recipients.size > 1 -> "${state.delivery.recipients.size} 个目标"
        state.delivery.recipients.singleOrNull()?.kind == "human" -> "@${state.delivery.recipients.single().name}"
        state.targetAgent == null -> "选择 Agent"
        state.parameters?.switching == true -> "切换中…"
        currentChoice != null -> "${state.targetAgent!!.name} · ${currentChoice.effortLabel.ifEmpty { currentChoice.modelLabel }}"
        else -> state.targetAgent!!.name
    }
    Column(modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Timeline/dock own normal handoffs once mounted; rejected sends keep their recovery entry.
        ComposerLocalEchoes(if (showLocalEchoes) state.submissions else state.submissions.filter { it.state == SendState.REJECTED }, viewModel::retry, viewModel::cancel)
        if (!state.online) NoticeBanner("离线编辑；发送会先保存到本机，连接可用后自动发送。", Tone.Waiting)
        if (!state.writable) NoticeBanner("当前频道不可写；草稿仍保留在当前设备", Tone.Neutral)
        if (state.error.isNotEmpty()) NoticeBanner(state.error, Tone.Danger, action = "关闭", onAction = viewModel::clearError)
        if (count > 0) Surface(color = AtollColors.Surface, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, AtollColors.Line), shadowElevation = 8.dp) {
            Column(Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState()).padding(6.dp)) {
                Text(if (mention != null && mention.candidates.isNotEmpty()) "提及成员" else "命令", Modifier.padding(start = 10.dp, top = 6.dp, bottom = 4.dp),
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = AtollColors.Muted)
                repeat(count) { index ->
                    val actor = mention?.candidates?.getOrNull(index)
                    val command = commandRows.getOrNull(index)
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (index == active % count) AtollColors.Subtle else AtollColors.Surface)
                        .clickable { pick(index) }.heightIn(min = 48.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (actor != null) {
                            ActorAvatar(actor.name, actor.id, agent = actor.kind != "human", size = 32.dp)
                            Column(Modifier.weight(1f)) {
                                Text(actor.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                Text(listOf(if (actor.kind == "agent") "Agent" else actor.kind, actor.body).filter { it.isNotBlank() }.joinToString(" · "),
                                    fontSize = 12.sp, color = AtollColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        } else if (command != null) {
                            Text("/${command.name}", Modifier.widthIn(min = 96.dp), fontSize = 14.sp, fontWeight = FontWeight.Medium, fontFamily = AtollMono)
                            Text(command.label, Modifier.weight(1f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (mention == null || mention.candidates.isEmpty()) Text("正文要以 / 开头时，写成 //。", Modifier.padding(start = 10.dp, top = 4.dp, bottom = 6.dp),
                    fontSize = 12.sp, color = AtollColors.Muted)
            }
        }
        val outline = when { dragging -> AtollColors.Lagoon; focused -> AtollColors.Ink; else -> AtollColors.ControlLine }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(if (dragging) AtollColors.LagoonSurface else AtollColors.Surface)
            .border(if (focused || dragging) 1.5.dp else 1.dp, outline, RoundedCornerShape(22.dp))) {
            if (dragging) Text("松开以上传到当前频道", Modifier.padding(start = 16.dp, top = 10.dp), color = AtollColors.LagoonText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            state.draft.reply?.let { reply ->
                Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 8.dp).clip(RoundedCornerShape(12.dp)).background(AtollColors.Subtle)
                    .height(IntrinsicSize.Min).padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(3.dp).fillMaxHeight().padding(vertical = 8.dp).background(AtollColors.Lagoon, RoundedCornerShape(2.dp)))
                    Column(Modifier.weight(1f).padding(start = 8.dp, top = 6.dp, bottom = 6.dp)) {
                        Text("回复 @${reply.senderName}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(reply.excerpt.take(120), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, color = AtollColors.Muted)
                    }
                    IconButton(onClick = { viewModel.setReply(null) }, Modifier.size(40.dp)) { Icon(Icons.Outlined.Close, "取消回复", Modifier.size(18.dp), tint = AtollColors.Muted) }
                }
            }
            if (!resting && (state.draft.recipients.isNotEmpty() || !state.delivery.valid)) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(state.delivery.label, fontSize = 12.sp, color = if (state.delivery.valid) AtollColors.Muted else AtollColors.Warning)
                state.draft.recipients.forEach { recipient ->
                    Row(Modifier.heightIn(min = 32.dp).clip(RoundedCornerShape(50)).background(AtollColors.LagoonSurface)
                        .clickable { viewModel.removeMention(recipient.id) }.padding(start = 10.dp, end = 6.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("@${recipient.name}", fontSize = 13.sp, color = AtollColors.LagoonText, fontWeight = FontWeight.Medium)
                        Icon(Icons.Outlined.Close, "移除 ${recipient.name}", Modifier.size(14.dp), tint = AtollColors.LagoonText)
                    }
                }
            }
            if (state.draft.attachments.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                state.draft.attachments.forEach { attachment ->
                    Row(Modifier.heightIn(min = 36.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, AtollColors.Line, RoundedCornerShape(10.dp))
                        .clickable { onPreviewAttachment?.invoke(attachment) }.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Description, null, Modifier.size(16.dp), tint = AtollColors.Muted)
                        Text("${attachment.name} · ${formatSize(attachment.size)}", Modifier.padding(start = 6.dp).widthIn(max = 220.dp), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { viewModel.removeAttachment(attachment.resourceId) }, Modifier.size(36.dp)) {
                            Icon(Icons.Outlined.Close, "移除附件 ${attachment.name}", Modifier.size(14.dp), tint = AtollColors.Muted)
                        }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 4.dp)) {
                key(viewModel) { NativeComposerEditor(state.editorCommand, Modifier.fillMaxWidth(), state.writable, if (resting) 1 else 6,
                    onValue = { update(it) }, onFocus = { focused = it }, onSend = { if (state.canSend) viewModel.send(editor.text) },
                    onFiles = { uris -> scope.launch { viewModel.upload(uploadFiles(context, uris)) } }, onDrag = { dragging = it },
                    onKey = { key, shift, composing, repeated ->
                        if (key == "Enter" && repeated) true else when (ComposerEditor.key(key, shift, composing, editor.selection.collapsed && editor.selection.start == 0, state.draft.recipients.isNotEmpty(), state.draft.reply != null, count)) {
                            EditorAction.SEND -> { if (state.canSend) viewModel.send(editor.text); true }
                            EditorAction.NEWLINE -> { newline(); true }
                            EditorAction.REMOVE_MENTION -> { viewModel.removeMention(state.draft.recipients.last().id); true }
                            EditorAction.CLEAR_REPLY -> { viewModel.setReply(null); true }
                            EditorAction.DISMISS_MENU -> { dismissed = true; true }
                            EditorAction.NEXT -> { active = (active + 1) % count; true }
                            EditorAction.PREVIOUS -> { active = (active + count - 1) % count; true }
                            EditorAction.PICK -> { pick(active % count); true }
                            else -> false
                        }
                    }) }
            }
            Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.heightIn(min = 36.dp).clip(RoundedCornerShape(50)).background(AtollColors.Subtle).clickable { showSelector = true }
                    .padding(start = 4.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.targetAgent?.let { ActorAvatar(it.name, it.id, agent = true, size = 26.dp) } ?: Spacer(Modifier.width(4.dp))
                    Text(agentLabel, Modifier.widthIn(max = 160.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                    Icon(Icons.Outlined.KeyboardArrowDown, null, Modifier.size(16.dp), tint = AtollColors.Muted)
                }
                Spacer(Modifier.weight(1f))
                if (state.uploading) Spinner(16.dp, AtollColors.Muted, AtollColors.Line)
                if (!resting) IconButton(onClick = ::newline, enabled = state.writable, modifier = Modifier.size(40.dp)) { Icon(Icons.Outlined.KeyboardReturn, "换行", tint = AtollColors.Muted) }
                IconButton(onClick = { onPickChannelFiles?.invoke() }, enabled = state.online && state.writable && onPickChannelFiles != null, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.FolderOpen, "从频道文件选择", tint = AtollColors.Muted)
                }
                IconButton(onClick = { launcher.launch(arrayOf("*/*")) }, enabled = state.online && state.writable && !state.uploading, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.AttachFile, "上传本机文件", tint = AtollColors.Muted)
                }
                IconButton(onClick = { viewModel.send(editor.text) }, enabled = state.canSend,
                    modifier = Modifier.padding(start = 2.dp).size(40.dp).clip(CircleShape).background(if (state.canSend) AtollColors.Ink else AtollColors.Sunken)) {
                    if (state.sending) Spinner(18.dp, Color.White, Color.White.copy(alpha = .3f))
                    else Icon(Icons.Outlined.ArrowUpward, "发送", tint = if (state.canSend) Color.White else AtollColors.Faint)
                }
            }
        }
    }
    if (showSelector) AgentSelector(state, viewModel, onClose = { showSelector = false })
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentSelector(state: ComposerUiState, viewModel: ComposerViewModel, onClose: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose, containerColor = AtollColors.Surface, dragHandle = null) {
        SheetHeader("Agent 与模型", onClose, closeLabel = "完成")
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.roster.filter { it.kind == "agent" }.forEach { actor ->
                val chosen = state.targetAgent?.id == actor.id
                Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(14.dp))
                    .border(if (chosen) 1.5.dp else 1.dp, if (chosen) AtollColors.Ink else AtollColors.Line, RoundedCornerShape(14.dp))
                    .clickable { viewModel.selectAgent(actor.id) }.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ActorAvatar(actor.name, actor.id, agent = true, size = 36.dp)
                    Column(Modifier.weight(1f)) {
                        Text(actor.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        if (actor.body.isNotBlank()) Text(actor.body, fontSize = 12.sp, color = AtollColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (chosen) Icon(Icons.Outlined.Check, "已选择", tint = AtollColors.Ink)
                }
            }
            state.targetAgent?.let { actor ->
                HorizontalDivider(Modifier.padding(vertical = 6.dp), color = AtollColors.Line)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${actor.name} 的参数", Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AtollColors.Muted)
                    TextButton(onClick = { viewModel.refreshParameters(actor.id) }, enabled = state.online) { Text("刷新", color = AtollColors.Lagoon) }
                }
                val parameters = state.parameters
                if (parameters?.loading == true) LinearProgressIndicator(Modifier.fillMaxWidth(), color = AtollColors.Lagoon, trackColor = AtollColors.Sunken)
                if (parameters?.error?.isNotEmpty() == true) NoticeBanner(parameters.error, Tone.Danger)
                val models = parameters?.choices?.distinctBy { it.model }.orEmpty()
                if (models.isNotEmpty()) {
                    Text("模型", fontSize = 14.sp)
                    ChipRow {
                        models.forEach { choice ->
                            TextChip(choice.modelLabel, selected = parameters?.current?.model == choice.model,
                                onClick = if (state.online && parameters?.switching != true) ({ viewModel.selectModel(choice) }) else null)
                        }
                    }
                }
                val efforts = parameters?.choices?.filter { it.model == parameters.current?.model }.orEmpty()
                if (efforts.isNotEmpty()) {
                    Text("推理强度", fontSize = 14.sp)
                    ChipRow {
                        efforts.forEach { choice ->
                            TextChip(choice.effortLabel.ifEmpty { "默认" }, selected = parameters?.current?.effort == choice.effort,
                                onClick = if (state.online && parameters?.switching != true) ({ viewModel.selectModel(choice) }) else null)
                        }
                    }
                }
                if (parameters != null && parameters.contextWindow > 0) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(AtollColors.Ground).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row { Text("上下文", Modifier.weight(1f), fontSize = 13.sp); Text("${parameters.contextTokens} / ${parameters.contextWindow}", fontSize = 13.sp, fontFamily = AtollMono, color = AtollColors.Muted) }
                    LinearProgressIndicator(progress = { parameters.percent / 100f }, modifier = Modifier.fillMaxWidth(),
                        color = if (parameters.percent >= 75) AtollColors.Warning else AtollColors.Lagoon, trackColor = AtollColors.Sunken)
                    Text("上下文 ${parameters.contextTokens} / ${parameters.contextWindow} · 已使用 ${parameters.percent}%", fontSize = 12.sp, color = AtollColors.Muted)
                }
                parameters?.client?.let { client ->
                    val version = client.text("current").ifEmpty { client.text("version") }
                    val status = when (client.text("update_status")) { "available" -> "可升级${client.text("latest").takeIf { it.isNotEmpty() }?.let { "至 $it" }.orEmpty()}"; "current" -> "已是最新"; else -> "升级状态未知" }
                    if (version.isNotEmpty() || client.text("update_status").isNotEmpty()) Text("客户端 ${version.ifEmpty { "版本未知" }} · $status", fontSize = 12.sp, color = AtollColors.Muted)
                }
            }
        }
    }
}
@Composable
fun ComposerLocalEchoes(rows: List<LocalSubmission>, onRetry: (String) -> Unit, onCancel: (String) -> Unit) {
    rows.takeLast(4).forEach { row ->
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(AtollColors.Surface).border(1.dp, AtollColors.Line, RoundedCornerShape(14.dp))
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 4.dp)) {
            Text(row.text.ifEmpty { row.frame.text("msg_type") }.take(160), fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            SubmissionRow(row, onRetry, onCancel)
        }
    }
}

/** Put below a local timeline bubble. The durable journal owns retry/cancellation. */
@Composable
fun TimelineSubmissionStatus(row: LocalSubmission, onRetry: (String) -> Unit, onCancel: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 50.dp, end = 6.dp)) { SubmissionRow(row, onRetry, onCancel) }
}

@Composable
private fun SubmissionRow(row: LocalSubmission, onRetry: (String) -> Unit, onCancel: (String) -> Unit) {
    val tone = when {
        row.cancelRequested -> Tone.Neutral
        row.state == SendState.REJECTED -> Tone.Danger
        row.state in setOf(SendState.UNCERTAIN, SendState.DELAYED) -> Tone.Waiting
        else -> Tone.Neutral
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        StatusPill(submissionLabel(row), tone, Modifier.weight(1f, fill = false), live = row.state == SendState.TRANSMITTING)
        Spacer(Modifier.weight(1f))
        if (row.state in setOf(SendState.REJECTED, SendState.UNCERTAIN) && !row.cancelRequested)
            TextButton({ onRetry(row.messageId) }, Modifier.heightIn(min = 44.dp)) { Text("使用原编号重试", fontSize = 13.sp, color = AtollColors.Lagoon) }
        TextButton({ onCancel(row.messageId) }, Modifier.heightIn(min = 44.dp)) { Text("取消", fontSize = 13.sp, color = AtollColors.Muted) }
    }
    if (row.error.isNotEmpty()) Text(row.error, fontSize = 12.sp, color = AtollColors.DangerText)
}

fun submissionLabel(row: LocalSubmission): String = if (row.cancelRequested) "取消待确认" else when (row.state) {
    SendState.QUEUED -> "已保存到本机，待发送"
    SendState.TRANSMITTING -> "发送中"
    SendState.ACCEPTED -> "已接受，等待账本"
    SendState.DELAYED -> "账本确认延迟"
    SendState.UNCERTAIN -> "发送结果待确认，正在通过重连账本核对。"
    SendState.REJECTED -> "发送失败"
}
internal suspend fun uploadFiles(context: Context, uris: List<Uri>): List<UploadFile> = withContext(Dispatchers.IO) {
    uris.map { uri ->
        var name = uri.lastPathSegment ?: "file"
        var size = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor -> if (cursor.moveToFirst()) {
            cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = cursor.getString(it) ?: name }
            cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }?.let { size = cursor.getLong(it) }
        } }
        UploadFile(name, size, context.contentResolver.getType(uri) ?: "application/octet-stream") { context.contentResolver.openInputStream(uri) ?: error("无法读取文件") }
    }
}
private fun formatSize(size: Long): String = if (size < 0) "大小未知" else if (size >= 1024 * 1024) "%.1f MB".format(size / 1048576.0) else if (size >= 1024) "%.1f KB".format(size / 1024.0) else "$size B"

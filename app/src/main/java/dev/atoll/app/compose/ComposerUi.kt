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
import dev.atoll.app.ui.components.MessageFrame
import dev.atoll.app.ui.screens.MessageViewState
import dev.atoll.app.ui.theme.AtollColors
import kotlinx.coroutines.*

/** Host owns the channel-files picker and preview; selected resources enter addAttachments(). */
@Composable
fun AtollComposer(viewModel: ComposerViewModel, modifier: Modifier = Modifier, onPickChannelFiles: (() -> Unit)? = null, onPreviewAttachment: ((ComposerAttachment) -> Unit)? = null, showLocalEchoes: Boolean = true) {
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
    Column(modifier.background(if (dragging) AtollColors.Selected else AtollColors.Input).padding(horizontal = 10.dp, vertical = 5.dp)) {
        if (dragging) Text("松开以上传到当前频道", color = AtollColors.Agent, fontSize = 12.sp)
        if (showLocalEchoes) ComposerLocalEchoes(state.submissions, viewModel::retry, viewModel::cancel)
        if (!state.online) Text("离线编辑；发送会先保存到本机，连接可用后自动发送。", color = AtollColors.Warning, fontSize = 12.sp)
        if (!state.writable) Text("当前频道不可写；草稿仍保留在当前设备", color = AtollColors.Muted, fontSize = 12.sp)
        if (state.error.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(state.error, Modifier.weight(1f), color = AtollColors.Accent, fontSize = 12.sp)
            IconButton(onClick = viewModel::clearError, modifier = Modifier.size(44.dp)) { Icon(Icons.Outlined.Close, "关闭错误") }
        }
        state.draft.reply?.let { reply ->
            Surface(color = AtollColors.MutedSurface, shape = RoundedCornerShape(6.dp)) {
                Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("回复 @${reply.senderName}", fontSize = 12.sp); Text(reply.excerpt.take(120), maxLines = 2, fontSize = 11.sp, color = AtollColors.Muted) }
                    IconButton(onClick = { viewModel.setReply(null) }) { Icon(Icons.Outlined.Close, "取消回复") }
                }
            }
        }
        if (!resting) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            Text(state.delivery.label, fontSize = 12.sp, color = if (state.delivery.valid) AtollColors.Online else AtollColors.Warning)
            state.draft.recipients.forEach { recipient -> InputChip(selected = true, onClick = { viewModel.removeMention(recipient.id) }, label = { Text("@${recipient.name}") }, trailingIcon = { Icon(Icons.Outlined.Close, "移除 ${recipient.name}", Modifier.size(14.dp)) }, modifier = Modifier.padding(start = 6.dp)) }
        }
        state.draft.attachments.forEach { attachment ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onPreviewAttachment?.invoke(attachment) }, modifier = Modifier.weight(1f)) { Text("◇ ${attachment.name} · ${formatSize(attachment.size)}", maxLines = 1) }
                IconButton(onClick = { viewModel.removeAttachment(attachment.resourceId) }) { Icon(Icons.Outlined.Close, "移除附件 ${attachment.name}") }
            }
        }
        if (count > 0) Surface(color = AtollColors.Rail, border = BorderStroke(1.dp, AtollColors.ControlLine), shape = RoundedCornerShape(8.dp)) {
            Column(Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                repeat(count) { index ->
                    val actor = mention?.candidates?.getOrNull(index)
                    val command = commandRows.getOrNull(index)
                    Row(Modifier.fillMaxWidth().background(if (index == active % count) AtollColors.Selected else AtollColors.Rail).clickable { pick(index) }.heightIn(min = 44.dp).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column { Text(if (actor != null) "@${actor.name}" else "/${command!!.name}", fontSize = 14.sp); Text(if (actor != null) "${actor.kind} · ${actor.body}" else command!!.label, fontSize = 11.sp, color = AtollColors.Muted, maxLines = 1) }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            key(viewModel) { NativeComposerEditor(state.editorCommand, Modifier.weight(1f), state.writable, if (resting) 1 else 6,
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
            IconButton(onClick = { viewModel.send(editor.text) }, enabled = state.canSend, modifier = Modifier.size(44.dp)) { if (state.sending) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Outlined.ArrowUpward, "发送") }
        }
        if (!resting) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { launcher.launch(arrayOf("*/*")) }, enabled = state.online && state.writable && !state.uploading) { Icon(Icons.Outlined.AttachFile, "上传本机文件") }
            IconButton(onClick = { onPickChannelFiles?.invoke() }, enabled = state.online && state.writable && onPickChannelFiles != null) { Icon(Icons.Outlined.FolderOpen, "从频道文件选择") }
            IconButton(onClick = ::newline, enabled = state.writable) { Icon(Icons.Outlined.KeyboardReturn, "换行") }
            if (state.uploading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { showSelector = true }, modifier = Modifier.heightIn(min = 44.dp)) {
                val parameters = state.parameters
                Text(when {
                    state.delivery.recipients.size > 1 -> "${state.delivery.recipients.size} 个目标"
                    state.delivery.recipients.singleOrNull()?.kind == "human" -> "@${state.delivery.recipients.single().name}"
                    state.targetAgent == null -> "选择 Agent"
                    parameters?.switching == true -> "切换中…"
                    parameters?.current != null -> "${parameters.current.model} · ${parameters.current.effort} ${if (parameters.contextWindow > 0) "${parameters.percent}%" else ""}"
                    else -> state.targetAgent!!.name
                }, maxLines = 1, fontSize = 12.sp)
            }
        }
    }
    if (showSelector) AgentSelector(state, viewModel, onClose = { showSelector = false })
}
@Composable
private fun AgentSelector(state: ComposerUiState, viewModel: ComposerViewModel, onClose: () -> Unit) {
    AlertDialog(onDismissRequest = onClose, title = { Text("Agent 与模型") }, confirmButton = { TextButton(onClick = onClose) { Text("完成") } }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            state.roster.filter { it.kind == "agent" }.forEach { actor ->
                TextButton(onClick = { viewModel.selectAgent(actor.id) }) { Text("${if (state.targetAgent?.id == actor.id) "✓ " else ""}${actor.name}") }
            }
            state.targetAgent?.let { actor ->
                Row(verticalAlignment = Alignment.CenterVertically) { Text(actor.name, Modifier.weight(1f)); TextButton(onClick = { viewModel.refreshParameters(actor.id) }, enabled = state.online) { Text("刷新") } }
                val parameters = state.parameters
                if (parameters?.loading == true) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (parameters?.error?.isNotEmpty() == true) Text(parameters.error, color = AtollColors.Accent, fontSize = 12.sp)
                Text("模型", fontSize = 12.sp, color = AtollColors.Muted)
                parameters?.choices?.distinctBy { it.model }?.forEach { choice ->
                    TextButton(onClick = { viewModel.selectModel(choice) }, enabled = state.online && parameters.switching.not()) { Text("${if (parameters.current?.model == choice.model) "✓ " else ""}${choice.modelLabel}") }
                }
                Text("推理强度", fontSize = 12.sp, color = AtollColors.Muted)
                parameters?.choices?.filter { it.model == parameters.current?.model }?.forEach { choice ->
                    TextButton(onClick = { viewModel.selectModel(choice) }, enabled = state.online && parameters.switching.not()) { Text("${if (parameters.current?.effort == choice.effort) "✓ " else ""}${choice.effortLabel.ifEmpty { "默认" }}") }
                }
                if (parameters != null && parameters.contextWindow > 0) {
                    Text("上下文 ${parameters.contextTokens} / ${parameters.contextWindow} · 已使用 ${parameters.percent}%", fontSize = 12.sp)
                    LinearProgressIndicator(progress = { parameters.percent / 100f }, modifier = Modifier.fillMaxWidth())
                }
                parameters?.client?.let { client ->
                    val version = client.text("current").ifEmpty { client.text("version") }
                    val status = when (client.text("update_status")) { "available" -> "可升级${client.text("latest").takeIf { it.isNotEmpty() }?.let { "至 $it" }.orEmpty()}"; "current" -> "已是最新"; else -> "升级状态未知" }
                    if (version.isNotEmpty() || client.text("update_status").isNotEmpty()) Text("客户端 ${version.ifEmpty { "版本未知" }} · $status", fontSize = 11.sp, color = AtollColors.Muted)
                }
            }
        }
    })
}
@Composable
fun ComposerLocalEchoes(rows: List<LocalSubmission>, onRetry: (String) -> Unit, onCancel: (String) -> Unit) {
    rows.takeLast(4).forEach { row ->
        Surface(color = AtollColors.MutedSurface, shape = RoundedCornerShape(6.dp), modifier = Modifier.padding(vertical = 3.dp)) {
            Column(Modifier.padding(8.dp)) {
                Text(row.text.ifEmpty { row.frame.text("msg_type") }.take(160), fontSize = 14.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (row.cancelRequested) "取消待确认" else when (row.state) { SendState.QUEUED -> "已保存到本机，待发送"; SendState.TRANSMITTING -> "发送中"; SendState.ACCEPTED -> "已接受，等待账本"; SendState.DELAYED -> "账本确认延迟"; SendState.UNCERTAIN -> "发送结果待确认，正在通过重连账本核对。"; SendState.REJECTED -> "发送失败" }, Modifier.weight(1f), fontSize = 11.sp, color = AtollColors.Muted)
                    if (row.state in setOf(SendState.REJECTED, SendState.UNCERTAIN) && !row.cancelRequested) TextButton(onClick = { onRetry(row.messageId) }) { Text("使用原编号重试", fontSize = 11.sp) }
                    TextButton(onClick = { onCancel(row.messageId) }) { Text("取消", fontSize = 11.sp) }
                }
                if (row.error.isNotEmpty()) Text(row.error, fontSize = 11.sp, color = AtollColors.Accent)
            }
        }
    }
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

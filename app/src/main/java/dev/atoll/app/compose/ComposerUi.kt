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
import dev.atoll.app.ui.theme.AtollMono
import dev.atoll.app.ui.theme.ChatColors
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.*

/** Host owns the channel-files picker and preview; selected resources enter addAttachments(). */
@Composable
fun AtollComposer(viewModel: ComposerViewModel, modifier: Modifier = Modifier, onPickChannelFiles: (() -> Unit)? = null, onPreviewAttachment: ((ComposerAttachment) -> Unit)? = null, showLocalEchoes: Boolean = true, editing: ComposerEditBinding? = null,
    busyAgents: Set<String> = emptySet()) {
    if (editing != null) { EditingComposer(editing, modifier.background(ChatColors.Bar)); return }
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
    val target = state.targetAgent
    val placeholder = when {
        !state.writable -> "当前频道不可写"
        state.delivery.recipients.size > 1 -> "发给 ${state.delivery.recipients.joinToString("、") { it.name }}"
        state.delivery.recipients.singleOrNull() != null -> "发给 ${state.delivery.recipients.single().name}"
        else -> "发消息，@ 指定成员，/ 使用命令"
    }
    var plusOpen by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        // Timeline/dock own normal handoffs once mounted; rejected sends keep their recovery entry.
        ComposerLocalEchoes(if (showLocalEchoes) state.submissions else state.submissions.filter { it.state == SendState.REJECTED }, viewModel::retry, viewModel::cancel)
        if (count > 0) Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).shadow(8.dp, ChatSheetShape).clip(ChatSheetShape).background(ChatColors.Block)
            .verticalScroll(rememberScrollState())) {
            val candidates = mention?.candidates.orEmpty()
            if (candidates.isNotEmpty()) {
                MentionHeading(if (mention?.query.isNullOrEmpty()) "可以提及的成员" else "匹配 “${mention?.query}”")
                candidates.forEachIndexed { index, actor ->
                    ChatListRow(actor.name, Modifier.background(if (index == active % count) ChatColors.Bar else ChatColors.Block),
                        subtitle = listOf(if (actor.kind == "agent") "Agent" else if (actor.kind == "human") "成员" else actor.kind, actor.body).filter { it.isNotBlank() }.joinToString(" · "),
                        divider = index < candidates.lastIndex, onClick = { pick(index) },
                        leading = { ActorAvatar(actor.name, actor.id, agent = actor.kind != "human", size = 36.dp) })
                }
            } else {
                Row(Modifier.fillMaxWidth().background(ChatColors.Bar).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (target != null) {
                        Text("发给", fontSize = 13.sp, color = ChatColors.Gray)
                        ActorAvatar(target.name, target.id, agent = true, size = 16.dp)
                        Text("${target.name} 的命令", fontSize = 13.sp, color = ChatColors.Ink)
                    } else Text("命令", fontSize = 13.sp, color = ChatColors.Gray)
                }
                commandRows.forEachIndexed { index, command ->
                    val danger = command.name == "restart"
                    ChatListRow("/${command.name}", Modifier.background(if (index == active % count) ChatColors.Bar else ChatColors.Block),
                        subtitle = command.label, minHeight = 52.dp, divider = index < commandRows.lastIndex,
                        titleColor = if (danger) ChatColors.Red else ChatColors.Ink, onClick = { pick(index) },
                        trailing = when {
                            danger -> ({ Text("需确认", fontSize = 13.sp, color = ChatColors.Gray) })
                            command.name == "compact" && (state.parameters?.contextWindow ?: 0) > 0 -> ({ Text("上下文 ${state.parameters!!.percent}%", fontSize = 13.sp, color = ChatColors.Gray) })
                            command.usage != "/${command.name}" -> ({ Text(command.usage.removePrefix("/${command.name}").trim(), fontSize = 13.sp, fontFamily = AtollMono, color = ChatColors.Gray) })
                            else -> null
                        })
                }
                Text("正文要以 / 开头时，写成 //。命令不能带附件，回复模式下不可用。", Modifier.fillMaxWidth().background(ChatColors.Bar)
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp), fontSize = 13.sp, lineHeight = 18.sp, color = ChatColors.Gray)
            }
        }
        Column(Modifier.fillMaxWidth().background(ChatColors.Bar)) {
            HorizontalDivider(color = ChatColors.Line)
            if (!state.online) ChatNotice("离线编辑；发送会先保存到本机，连接可用后自动发送。")
            if (state.error.isNotEmpty()) ChatNotice(state.error, danger = true, action = "关闭", onAction = viewModel::clearError)
            state.draft.reply?.let { reply ->
                Row(Modifier.fillMaxWidth().padding(start = 56.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${reply.senderName}：${reply.excerpt.take(120)}", Modifier.weight(1f).clip(RoundedCornerShape(4.dp)).background(ChatColors.Quote)
                        .padding(horizontal = 10.dp, vertical = 6.dp), maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, lineHeight = 19.sp, color = ChatColors.Gray)
                    IconButton(onClick = { viewModel.setReply(null) }, Modifier.size(40.dp)) { Icon(Icons.Outlined.Close, "取消回复", Modifier.size(18.dp), tint = ChatColors.Gray) }
                }
            }
            if (!resting && (state.draft.recipients.isNotEmpty() || !state.delivery.valid)) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(start = 56.dp, end = 12.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!state.delivery.valid) Text(state.delivery.label, fontSize = 12.sp, color = ChatColors.Red)
                state.draft.recipients.forEach { recipient ->
                    Row(Modifier.heightIn(min = 32.dp).clip(RoundedCornerShape(50)).background(ChatColors.Secondary)
                        .clickable { viewModel.removeMention(recipient.id) }.padding(start = 10.dp, end = 6.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("@${recipient.name}", fontSize = 13.sp, color = ChatColors.Ink)
                        Icon(Icons.Outlined.Close, "移除 ${recipient.name}", Modifier.size(14.dp), tint = ChatColors.Gray)
                    }
                }
            }
            if (state.draft.attachments.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 56.dp, end = 12.dp, top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                state.draft.attachments.forEach { attachment ->
                    Row(Modifier.heightIn(min = 36.dp).clip(RoundedCornerShape(4.dp)).background(ChatColors.Block)
                        .clickable { onPreviewAttachment?.invoke(attachment) }.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Description, null, Modifier.size(16.dp), tint = ChatColors.Gray)
                        Text("${attachment.name} · ${formatSize(attachment.size)}", Modifier.padding(start = 6.dp).widthIn(max = 220.dp), fontSize = 13.sp, color = ChatColors.Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { viewModel.removeAttachment(attachment.resourceId) }, Modifier.size(36.dp)) {
                            Icon(Icons.Outlined.Close, "移除附件 ${attachment.name}", Modifier.size(14.dp), tint = ChatColors.Gray)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).clickable(onClickLabel = "选择 Agent") { showSelector = true }
                    .semantics { contentDescription = if (target != null) "发给 ${target.name}，更换" else "选择 Agent" }, contentAlignment = Alignment.Center) {
                    if (target != null) ActorAvatar(target.name, target.id, agent = true, size = 28.dp)
                    else Icon(Icons.Outlined.PersonAdd, null, Modifier.size(24.dp), tint = ChatColors.Gray)
                }
                Box(Modifier.weight(1f).heightIn(min = 40.dp).clip(RoundedCornerShape(4.dp))
                    .background(if (dragging) ChatColors.Secondary else ChatColors.Block).padding(horizontal = 4.dp)) {
                    key(viewModel) { NativeComposerEditor(state.editorCommand, Modifier.fillMaxWidth(), state.writable, if (resting) 1 else 6,
                        onValue = { update(it) }, onFocus = { focused = it }, onSend = { if (state.canSend) viewModel.send(editor.text) },
                        onFiles = { uris -> scope.launch { viewModel.upload(uploadFiles(context, uris)) } }, onDrag = { dragging = it },
                        hint = if (dragging) "松开以上传到当前频道" else placeholder,
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
                if (state.canSend || state.sending) Box(Modifier.padding(bottom = 4.dp).height(32.dp).clip(RoundedCornerShape(4.dp))
                    .background(ChatColors.Green).clickable(enabled = state.canSend, onClickLabel = "发送") { viewModel.send(editor.text) }
                    .semantics { contentDescription = "发送" }.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                    if (state.sending) Spinner(14.dp, Color.White, Color.White.copy(alpha = .3f)) else Text("发送", fontSize = 14.sp, color = Color.White)
                } else Box {
                    IconButton({ plusOpen = true }, Modifier.size(40.dp)) {
                        if (state.uploading) ChatSpinner(18.dp)
                        else Icon(Icons.Outlined.AddCircleOutline, "更多：附件、命令、频道文件", Modifier.size(28.dp), tint = ChatColors.Ink)
                    }
                    DropdownMenu(plusOpen, { plusOpen = false }, containerColor = ChatColors.Block) {
                        DropdownMenuItem({ Text("上传本机文件", fontSize = 15.sp) }, enabled = state.online && state.writable && !state.uploading,
                            leadingIcon = { Icon(Icons.Outlined.AttachFile, null) }, onClick = { plusOpen = false; launcher.launch(arrayOf("*/*")) })
                        DropdownMenuItem({ Text("从频道文件选择", fontSize = 15.sp) }, enabled = state.online && state.writable && onPickChannelFiles != null,
                            leadingIcon = { Icon(Icons.Outlined.FolderOpen, null) }, onClick = { plusOpen = false; onPickChannelFiles?.invoke() })
                        DropdownMenuItem({ Text("命令", fontSize = 15.sp) }, enabled = state.writable,
                            leadingIcon = { Icon(Icons.Outlined.Terminal, null) }, onClick = { plusOpen = false; update(TextFieldValue("/", TextRange(1)), external = true) })
                        DropdownMenuItem({ Text("换行", fontSize = 15.sp) }, enabled = state.writable,
                            leadingIcon = { Icon(Icons.Outlined.KeyboardReturn, null) }, onClick = { plusOpen = false; newline() })
                    }
                }
            }
        }
    }
    if (showSelector) AgentSelector(state, viewModel, busyAgents, onClose = { showSelector = false },
        onCompact = { update(TextFieldValue("/compact", TextRange(8)), external = true) })
}

@Composable
private fun MentionHeading(text: String) = Text(text, Modifier.fillMaxWidth().background(ChatColors.Bar).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 6.dp),
    fontSize = 13.sp, lineHeight = 18.sp, color = ChatColors.Gray)

/** AgentPicker board: whom the next message goes to, then that agent's model, effort and context. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentSelector(state: ComposerUiState, viewModel: ComposerViewModel, busyAgents: Set<String>, onClose: () -> Unit, onCompact: () -> Unit) {
    var choosing by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onClose, containerColor = ChatColors.Bar, shape = ChatSheetShape, dragHandle = null, scrimColor = ChatColors.Scrim) {
        ChatSheetHeader("发送给", "完成", onClose, actionIsLink = true)
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 8.dp, bottom = 20.dp)) {
            val agents = state.roster.filter { it.kind == "agent" }
            agents.forEachIndexed { index, actor ->
                val chosen = state.targetAgent?.id == actor.id
                val busy = actor.id in busyAgents
                ChatListRow(actor.name, minHeight = 64.dp, divider = index < agents.lastIndex, onClick = { viewModel.selectAgent(actor.id) },
                    leading = { ActorAvatar(actor.name, actor.id, agent = true, size = 40.dp) },
                    subtitleContent = {
                        if (busy) ChatSpinner()
                        Text(if (busy) "运行中 · 新消息会排队" else "空闲 · 立即处理", fontSize = 13.sp, color = ChatColors.Gray)
                    },
                    trailing = if (chosen) ({ Icon(Icons.Outlined.Check, "已选择", Modifier.size(20.dp), tint = ChatColors.Green) }) else null)
            }
            state.targetAgent?.let { actor ->
                val parameters = state.parameters
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${actor.name} 的参数", Modifier.weight(1f), fontSize = 13.sp, color = ChatColors.Gray)
                    if (parameters?.loading == true) ChatSpinner()
                    Text("刷新", Modifier.heightIn(min = 40.dp).clickable(enabled = state.online) { viewModel.refreshParameters(actor.id) }.padding(horizontal = 12.dp).wrapContentHeight(),
                        fontSize = 13.sp, color = if (state.online) ChatColors.Link else ChatColors.Hint)
                }
                if (parameters?.error?.isNotEmpty() == true) ChatNotice(parameters.error, danger = true)
                val editable = state.online && parameters?.switching != true
                val models = parameters?.choices?.distinctBy { it.model }.orEmpty()
                if (models.isNotEmpty()) {
                    ChatListRow("模型", minHeight = 52.dp, onClick = { choosing = if (choosing == "model") "" else "model" }, trailing = {
                        Text(parameters?.current?.modelLabel.orEmpty(), Modifier.widthIn(max = 180.dp), fontSize = 14.sp, color = ChatColors.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        ChatChevron()
                    })
                    if (choosing == "model") models.forEach { choice -> ChoiceRow(choice.modelLabel, parameters?.current?.model == choice.model, editable) { viewModel.selectModel(choice); choosing = "" } }
                }
                val efforts = parameters?.choices?.filter { it.model == parameters.current?.model && it.effort.isNotEmpty() }.orEmpty()
                if (efforts.isNotEmpty()) {
                    ChatListRow("推理强度", minHeight = 52.dp, onClick = { choosing = if (choosing == "effort") "" else "effort" }, trailing = {
                        Text(parameters?.current?.effortLabel.orEmpty().ifEmpty { "默认" }, fontSize = 14.sp, color = ChatColors.Gray)
                        ChatChevron()
                    })
                    if (choosing == "effort") efforts.forEach { choice -> ChoiceRow(choice.effortLabel.ifEmpty { "默认" }, parameters?.current?.effort == choice.effort, editable) { viewModel.selectModel(choice); choosing = "" } }
                }
                if (parameters != null && parameters.contextWindow > 0) ChatListRow("上下文", minHeight = 64.dp,
                    subtitle = "已使用 ${parameters.percent}%" + if (parameters.percent >= 75) "，接近上限" else "", trailing = {
                        Text("${compactTokens(parameters.contextTokens)} / ${compactTokens(parameters.contextWindow)}", fontSize = 13.sp, color = ChatColors.Gray)
                        Box(Modifier.size(width = 72.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(ChatColors.Line)) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(parameters.percent / 100f).background(ChatColors.Gray))
                        }
                    })
                ChatListRow("/compact 压缩上下文", minHeight = 52.dp, divider = false, onClick = { onCompact(); onClose() }, trailing = { ChatChevron() })
                parameters?.client?.let { client ->
                    val version = client.text("current").ifEmpty { client.text("version") }
                    val status = when (client.text("update_status")) { "available" -> "可升级${client.text("latest").takeIf { it.isNotEmpty() }?.let { "至 $it" }.orEmpty()}"; "current" -> "已是最新"; else -> "升级状态未知" }
                    if (version.isNotEmpty() || client.text("update_status").isNotEmpty()) Text("客户端 ${version.ifEmpty { "版本未知" }} · $status",
                        Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp), fontSize = 13.sp, color = ChatColors.Gray)
                }
                Text("参数会随下一条消息一起发送，并记住为本频道里 ${actor.name} 的默认值。", Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                    fontSize = 13.sp, lineHeight = 19.sp, color = ChatColors.Gray)
            }
        }
    }
}

@Composable
private fun ChoiceRow(label: String, chosen: Boolean, enabled: Boolean, onClick: () -> Unit) {
    ChatListRow(label, Modifier.padding(start = 16.dp), minHeight = 48.dp, onClick = if (enabled) onClick else null,
        trailing = if (chosen) ({ Icon(Icons.Outlined.Check, "已选择", Modifier.size(18.dp), tint = ChatColors.Green) }) else null)
}

private fun compactTokens(value: Long): String = if (value >= 1000) "${value / 1000}k" else value.toString()
@Composable
fun ComposerLocalEchoes(rows: List<LocalSubmission>, onRetry: (String) -> Unit, onCancel: (String) -> Unit) {
    rows.takeLast(4).forEach { row ->
        Column(Modifier.fillMaxWidth().background(ChatColors.Ground).padding(start = 52.dp, end = 12.dp, top = 6.dp), horizontalAlignment = Alignment.End) {
            Text(row.text.ifEmpty { row.frame.text("msg_type") }.take(160), Modifier.clip(RoundedCornerShape(12.dp, 4.dp, 12.dp, 12.dp))
                .background(ChatColors.Mine).padding(horizontal = 14.dp, vertical = 10.dp), fontSize = 16.sp, lineHeight = 25.sp,
                color = ChatColors.Body, maxLines = 2, overflow = TextOverflow.Ellipsis)
            SubmissionRow(row, onRetry, onCancel)
        }
    }
}

/** ChatStates ⑪: put below a local timeline bubble. The durable journal owns retry/cancellation. */
@Composable
fun TimelineSubmissionStatus(row: LocalSubmission, onRetry: (String) -> Unit, onCancel: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 40.dp), horizontalAlignment = Alignment.End) { SubmissionRow(row, onRetry, onCancel) }
}

@Composable
private fun SubmissionRow(row: LocalSubmission, onRetry: (String) -> Unit, onCancel: (String) -> Unit) {
    val failed = row.state == SendState.REJECTED && !row.cancelRequested
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        when {
            failed -> Box(Modifier.size(18.dp).clip(CircleShape).background(ChatColors.Red), contentAlignment = Alignment.Center) {
                Text("!", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            }
            row.state == SendState.TRANSMITTING || row.state == SendState.QUEUED -> ChatSpinner()
            else -> Unit
        }
        Text(submissionLabel(row), Modifier.weight(1f, fill = false), fontSize = 12.sp, color = if (failed) ChatColors.Red else ChatColors.Gray,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (row.state in setOf(SendState.REJECTED, SendState.UNCERTAIN) && !row.cancelRequested)
            Text("使用原编号重试", Modifier.heightIn(min = 44.dp).clickable { onRetry(row.messageId) }.padding(horizontal = 4.dp).wrapContentHeight(),
                fontSize = 12.sp, color = ChatColors.Link)
        Text("取消", Modifier.heightIn(min = 44.dp).clickable { onCancel(row.messageId) }.padding(horizontal = 4.dp).wrapContentHeight(),
            fontSize = 12.sp, color = ChatColors.Gray)
    }
    if (row.error.isNotEmpty()) Text(row.error, fontSize = 12.sp, color = ChatColors.Red)
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

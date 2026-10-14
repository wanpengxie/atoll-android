package dev.atoll.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.atoll.app.ui.screens.*
import dev.atoll.app.ui.theme.ChatColors

/** Running and queued are grey; only failure is red; completed shows nothing. */
@Composable
fun StatusChip(status: MessageStatus, onClick: (() -> Unit)? = null) {
    Row(Modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        when (status) {
            MessageStatus.Processing -> ChatSpinner()
            MessageStatus.Completed -> Icon(Icons.Outlined.Check, null, Modifier.size(14.dp), tint = ChatColors.Gray)
            else -> Unit
        }
        Text(status.label, fontSize = 12.sp, color = if (status == MessageStatus.Failed) ChatColors.Red else ChatColors.Gray)
    }
}

/** A 10dp grey ring, the only running indicator in the conversation. */
@Composable
fun ChatSpinner(size: androidx.compose.ui.unit.Dp = 10.dp) = Spinner(size, ChatColors.Gray, ChatColors.Line)

@Composable
fun DaySeparator(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(vertical = 10.dp), fontSize = 12.sp, lineHeight = 17.sp, color = ChatColors.Gray, textAlign = TextAlign.Center)
}
@Composable
fun WeakNotice(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(horizontal = 36.dp, vertical = 6.dp), color = ChatColors.Gray, fontSize = 12.sp,
        lineHeight = 17.sp, textAlign = TextAlign.Center)
}

/** Lets the message-actions host put 重试 on the failure line inside the block it wraps. */
@Immutable
class TurnResend(val onResend: () -> Unit, val enabled: Boolean)
val LocalTurnResend = compositionLocalOf<TurnResend?> { null }

internal val OthersShape = RoundedCornerShape(topStart = 4.dp, topEnd = 12.dp, bottomEnd = 12.dp, bottomStart = 12.dp)
internal val MineShape = RoundedCornerShape(topStart = 12.dp, topEnd = 4.dp, bottomEnd = 12.dp, bottomStart = 12.dp)
private val chatMarkdown = MarkdownStyle(bodySize = 16, bodyLineHeight = 25f, link = ChatColors.Link, code = ChatColors.Bar, codeMaxLines = 6)

/**
 * One timeline row, WeChat style. Mine: a right-aligned green bubble without a name.
 * Others: a name line when the speaker changes (or after 5 minutes), then a white block whose last line
 * carries the turn's status. A request and its answer are ordinary rows; the part only says they are adjacent.
 */
@Composable
fun MessageFrame(message: MessageViewState, onLink: ((String) -> Unit)? = null,
    onAttachment: MessageAttachmentHandler? = null, onRelatedCall: ((String) -> Unit)? = null, onWorkExpanded: ((Boolean) -> Unit)? = null,
    onQuote: ((String) -> Unit)? = null, onMore: () -> Unit = {}) {
    CompositionLocalProvider(LocalMarkdownStyle provides chatMarkdown, LocalContentColor provides ChatColors.Body) {
        if (message.self) MineRow(message, onLink, onAttachment, onQuote = onQuote)
        else OthersRow(message, onLink, onAttachment, { onRelatedCall?.invoke(it) ?: onMore() }, onMore, onQuote)
    }
}

/** ChatStates ④: a grey two-line quote of the original; tapping scrolls to it. */
@Composable
private fun Quote(text: String, key: String, onQuote: ((String) -> Unit)?) {
    Text(text, Modifier.clip(RoundedCornerShape(4.dp)).background(ChatColors.Quote)
        .then(if (onQuote != null) Modifier.clickable(onClickLabel = "查看原消息") { onQuote(key) } else Modifier)
        .padding(horizontal = 10.dp, vertical = 6.dp), fontSize = 13.sp, lineHeight = 19.sp, color = ChatColors.Gray, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun MineRow(message: MessageViewState, onLink: ((String) -> Unit)?, onAttachment: MessageAttachmentHandler?, faded: Boolean = false,
    onQuote: ((String) -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(start = 40.dp, top = if (message.continuation) 4.dp else 10.dp),
        horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(Modifier.then(if (faded) Modifier.alpha(.5f) else Modifier).clip(MineShape).background(ChatColors.Mine)
            .padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (message.text.isNotEmpty()) {
                if (remember(message.text) { plainText(message.text) }) Text(message.text, fontSize = 16.sp, lineHeight = 25.sp, color = ChatColors.Body)
                else FoldableMarkdown(message.id, message.text, message.latest, onLink)
            }
            message.result?.let { StructuredResult(it) }
            if (message.attachments.isNotEmpty()) MessageAttachments(message.attachments, onLink, onAttachment)
        }
        message.quote?.let { Quote(it, message.quoteKey, onQuote) }
        if (message.turnState?.edited == true) Text("已编辑", fontSize = 12.sp, color = ChatColors.Gray)
    }
}

@Composable
private fun OthersRow(message: MessageViewState, onLink: ((String) -> Unit)?, onAttachment: MessageAttachmentHandler?,
    onChildProcess: (String) -> Unit, onProcess: () -> Unit, onQuote: ((String) -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(top = if (message.continuation) 4.dp else 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!message.continuation) NameLine(message.sender, message.senderId, message.agent, message.time, message.recipient)
        Column(Modifier.fillMaxWidth().clip(OthersShape).background(ChatColors.Block)
            .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = if (hasStatusLine(message)) 0.dp else 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (message.text.isNotEmpty()) FoldableMarkdown(message.id, message.text, message.latest, onLink)
            message.result?.takeUnless { failed(message) && it.errorCode != null }?.let { StructuredResult(it) }
            if (message.attachments.isNotEmpty()) MessageAttachments(message.attachments, onLink, onAttachment)
            val collaboration = message.relatedCalls.filter { it.conversation }
            if (collaboration.isNotEmpty()) CollaborationCard(collaboration, onProcess)
            if (message.subTasks.isNotEmpty()) SubTaskList(message.subTasks, onChildProcess, onProcess)
            message.quote?.let { Quote(it, message.quoteKey, onQuote) }
            StatusLine(message, onProcess)
        }
    }
}

@Composable
internal fun NameLine(name: String, id: String, agent: Boolean, time: String, recipient: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActorAvatar(name, id.ifEmpty { name }, agent = agent, size = 20.dp)
        Text(name, Modifier.weight(1f, fill = false), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = ChatColors.Ink,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        recipient?.let { Text("→ $it", Modifier.weight(1f, fill = false), fontSize = 13.sp, color = ChatColors.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        if (time.isNotEmpty()) Text(time, fontSize = 13.sp, color = ChatColors.Gray)
    }
}

private fun failed(message: MessageViewState) = message.part != MessagePart.Request &&
    (message.turnState?.kind == TurnKind.Failed || message.status == MessageStatus.Failed)
private fun running(message: MessageViewState) = message.status == MessageStatus.Processing || message.status == MessageStatus.Queued ||
    message.trail?.running == true
private fun toolCount(message: MessageViewState) = (message.trail?.records?.size ?: 0) + message.relatedCalls.count { !it.conversation }
private fun stopped(message: MessageViewState) = message.part == MessagePart.Answer && message.turnState?.kind == TurnKind.Stopped && message.status == null
internal fun hasStatusLine(message: MessageViewState) = running(message) || failed(message) || stopped(message) || toolCount(message) > 0
internal fun hasWork(message: MessageViewState) = hasStatusLine(message)

/** Keeps a 44dp touch target but takes 10dp less room, so the line sits close under the text as drawn. */
internal fun Modifier.tuckedUp() = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val tuck = 10.dp.roundToPx()
    layout(placeable.width, (placeable.height - tuck).coerceAtLeast(0)) { placeable.place(0, -tuck) }
}

/** At most one 13sp line under the text: running, the tools used, failure or interruption. */
@Composable
private fun StatusLine(message: MessageViewState, onProcess: () -> Unit) {
    if (message.text.isNotEmpty() || message.result != null) Box(Modifier.tuckedUp()) { StatusLineContent(message, onProcess) }
    else StatusLineContent(message, onProcess)
}

@Composable
private fun StatusLineContent(message: MessageViewState, onProcess: () -> Unit) {
    when {
        failed(message) -> Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val result = message.result
            val reason = result?.errorCode?.let { code -> failureTitle(code) + result.detail.takeIf(String::isNotBlank)?.let { "（$it）" }.orEmpty() }
            Text("失败：${reason ?: "请求失败"}", Modifier.weight(1f, fill = false), fontSize = 13.sp, lineHeight = 18.sp, color = ChatColors.Red,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
            LocalTurnResend.current?.let { resend ->
                Text("重试", Modifier.clickable(enabled = resend.enabled, role = Role.Button, onClick = resend.onResend).padding(vertical = 12.dp),
                    fontSize = 13.sp, color = if (resend.enabled) ChatColors.Link else ChatColors.Hint)
            }
            Text("过程 ›", Modifier.clickable(role = Role.Button, onClick = onProcess).padding(vertical = 12.dp), fontSize = 13.sp, color = ChatColors.Gray)
        }
        running(message) -> Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(role = Role.Button, onClick = onProcess),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ChatSpinner()
            Text(if (message.status == MessageStatus.Queued) "排队中" else "处理中", fontSize = 13.sp, color = ChatColors.Gray)
            WorkElapsed(message.trail?.startedAtMillis ?: 0)
            val step = message.progress ?: message.trail?.records?.lastOrNull()?.line
            Text(step?.let { "· $it ›" } ?: "›", Modifier.weight(1f, fill = false), fontSize = 13.sp, color = ChatColors.Gray,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        stopped(message) -> Text("已中断 · 你停止了这一轮", Modifier.heightIn(min = 44.dp).wrapContentHeight(), fontSize = 13.sp, color = ChatColors.Gray)
        toolCount(message) > 0 -> Text("调用了 ${toolCount(message)} 个工具 ›", Modifier.fillMaxWidth().heightIn(min = 44.dp)
            .clickable(role = Role.Button, onClick = onProcess).wrapContentHeight(), fontSize = 13.sp, color = ChatColors.Gray)
    }
}

/** ChatStates ⑥: agent-to-agent talk is a 聊天记录 card; the full thread opens in the process sheet. */
@Composable
private fun CollaborationCard(calls: List<RelatedCallViewState>, onOpen: () -> Unit) {
    val first = calls.first()
    val people = (listOf(first.sender) + first.recipients).distinct().take(2)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(ChatColors.Bar).clickable(role = Role.Button, onClick = onOpen)
        .padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("${people.joinToString(" 与 ")} 的协作", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = ChatColors.Ink,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        calls.takeLast(2).forEach { call -> key(call.id) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${call.sender}：${call.text.ifBlank { call.title }.replace('\n', ' ')}", Modifier.weight(1f, fill = false), fontSize = 13.sp,
                    lineHeight = 19.sp, color = ChatColors.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (call.status == MessageStatus.Processing) ChatSpinner()
            }
        } }
        HorizontalDivider(Modifier.padding(top = 4.dp), color = ChatColors.LineInWhite)
        Text("协作记录 · ${calls.size} 条 ›", Modifier.heightIn(min = 28.dp).wrapContentHeight(), fontSize = 12.sp, color = ChatColors.Gray)
    }
}

/** ChatStates ⑦: sub-tasks listed under a hairline inside the block; state is shape, not colour (except failure). */
@Composable
private fun SubTaskList(tasks: List<SubTaskViewState>, onChild: (String) -> Unit, onProcess: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(color = ChatColors.LineInWhite)
        Text("子任务 ${tasks.count { it.phase == SubTaskPhase.Completed }}/${tasks.size}", Modifier.padding(top = 8.dp, bottom = 2.dp),
            fontSize = 12.sp, color = ChatColors.Gray)
        tasks.take(6).forEach { task -> key(task.id) {
            Row(Modifier.fillMaxWidth().heightIn(min = 32.dp).clickable { task.requestId?.let(onChild) ?: onProcess() },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) { when (task.phase) {
                    SubTaskPhase.Completed -> Icon(Icons.Outlined.Check, "已完成", Modifier.size(14.dp), tint = ChatColors.Gray)
                    SubTaskPhase.Running -> ChatSpinner()
                    SubTaskPhase.Failed -> Icon(Icons.Outlined.Close, "失败", Modifier.size(14.dp), tint = ChatColors.Red)
                } }
                Text(task.title, Modifier.weight(1f), fontSize = 14.sp, color = if (task.phase == SubTaskPhase.Failed) ChatColors.Red else ChatColors.Body,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                ActorAvatar(task.owner, task.owner, agent = true, size = 16.dp)
                if (task.phase == SubTaskPhase.Running) WorkElapsed(task.startedAtMillis)
            }
        } }
        if (tasks.size > 6) Text("全部 ${tasks.size} 个 ›", Modifier.heightIn(min = 44.dp).clickable(onClick = onProcess).wrapContentHeight(),
            fontSize = 13.sp, color = ChatColors.Gray)
    }
}

/** ChatStates ⑩ and ⑨: a superseded or lost request keeps my bubble; the outcome is a grey caption or the receiver's block. */
@Composable
fun CompactTurnRow(message: MessageViewState, onProcess: (String) -> Unit, onResend: (() -> Unit)?, resendEnabled: Boolean) {
    val state = message.turnState ?: return
    CompositionLocalProvider(LocalMarkdownStyle provides chatMarkdown, LocalContentColor provides ChatColors.Body) {
        if (state.kind == TurnKind.Lost) {
            if (message.self) MineRow(message, null, null) else OthersRow(message, null, null, {}, { onProcess(state.requestId) })
            val receiver = message.recipient ?: message.sender
            Column(Modifier.fillMaxWidth().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                NameLine(receiver, state.receiverId.ifEmpty { receiver }, agent = true, time = "")
                Column(Modifier.clip(OthersShape).background(ChatColors.Block).padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text("没有收到结果 · ${if (state.lostReason == "restart") "Agent 已重启" else "已过期"}", fontSize = 13.sp, color = ChatColors.Gray)
                    onResend?.let { resend -> Text("重新发送", Modifier.heightIn(min = 44.dp).clickable(enabled = resendEnabled, role = Role.Button, onClick = resend)
                        .wrapContentHeight(), fontSize = 13.sp, color = if (resendEnabled) ChatColors.Link else ChatColors.Hint) }
                }
            }
            return@CompositionLocalProvider
        }
        val caption = when (state.kind) {
            TurnKind.Merged -> "已合并到上一条一起处理"
            TurnKind.Preempted -> "已被下一条替换"
            else -> ""
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            MineRow(message, null, null, faded = true)
            if (caption.isNotEmpty()) Text("$caption ›", Modifier.heightIn(min = 32.dp).clickable { onProcess(state.targetId) }.wrapContentHeight(),
                fontSize = 12.sp, color = ChatColors.Gray)
        }
    }
}

/** Plain text keeps a content-sized bubble; anything that looks like Markdown gets the full renderer. */
private val markdownMarks = Regex("""(^|\n)\s*([#>|*-]|\d+\.)\s|```|\*\*|`|\[[^\]]*]\(|\$\$|!\[""")
internal fun plainText(text: String) = text.length < 2000 && !markdownMarks.containsMatchIn(text)

@Composable
internal fun ChatRunningDot(modifier: Modifier = Modifier) = Box(modifier.size(6.dp).clip(CircleShape).background(ChatColors.Green))

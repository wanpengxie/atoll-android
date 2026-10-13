package dev.atoll.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.atoll.app.ui.screens.*
import dev.atoll.app.ui.theme.AtollColors

@Composable
fun RelatedCalls(messageId: String, calls: List<RelatedCallViewState>, onLink: ((String) -> Unit)? = null,
    onAttachment: MessageAttachmentHandler? = null, onProcess: (String) -> Unit = {}) {
    val (conversations, ordinary) = remember(calls) { calls.partition { it.conversation } }
    if (conversations.isNotEmpty()) CallTree(messageId, conversations, true, onLink, onAttachment, onProcess)
    if (ordinary.isNotEmpty()) {
        var expanded by rememberSaveable(messageId) { mutableStateOf(false) }
        val running = ordinary.count { it.status == MessageStatus.Processing || it.status == MessageStatus.Queued }
        val failed = ordinary.count { it.status == MessageStatus.Failed }
        TextButton({ expanded = !expanded }, contentPadding = PaddingValues(horizontal = 7.dp),
            modifier = Modifier.semantics { stateDescription = if (expanded) "已展开" else "已折叠" }) {
            Text("${ordinary.size} 次关联调用", fontSize = 10.sp, color = AtollColors.Muted)
            Text(when { running > 0 -> "$running 处理中"; failed > 0 -> "$failed 失败"; else -> "已完成" },
                Modifier.padding(start = 7.dp), fontSize = 9.sp, color = if (failed > 0 && running == 0) AtollColors.Warning else AtollColors.Muted)
            Text(if (expanded) " ⌃" else " ⌄", fontSize = 10.sp, color = AtollColors.Muted)
        }
        // Closed calls do not compose or measure any result, Markdown or progress widgets.
        if (expanded) CallTree(messageId, ordinary, false, onLink, onAttachment, onProcess)
    }
}

@Composable
private fun CallTree(messageId: String, calls: List<RelatedCallViewState>, conversation: Boolean,
    onLink: ((String) -> Unit)?, onAttachment: MessageAttachmentHandler?, onProcess: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp,
        start = if (conversation) 16.dp else 0.dp).semantics {
        contentDescription = if (conversation) "Agent 协作消息" else "关联调用"
    }, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        calls.forEach { call -> key(call.id) {
            val step = if (conversation) 23 else 14
            Box(Modifier.fillMaxWidth().padding(start = ((call.depth.coerceAtLeast(1) - 1) * step).dp)
                .drawBehind {
                    val y = 20.dp.toPx().coerceAtMost(size.height)
                    drawLine(AtollColors.ControlLine, Offset.Zero, Offset(0f, size.height), 1.dp.toPx())
                    drawLine(AtollColors.ControlLine, Offset(0f, y), Offset(9.dp.toPx(), y), 1.dp.toPx())
                }.padding(start = 9.dp)) {
                CallLeaf(messageId, call, onLink, onAttachment, onProcess)
            }
        } }
    }
}

@Composable
private fun CallLeaf(messageId: String, call: RelatedCallViewState, onLink: ((String) -> Unit)?,
    onAttachment: MessageAttachmentHandler?, onProcess: (String) -> Unit) {
    var expanded by rememberSaveable(messageId, call.id) { mutableStateOf(false) }
    val recipients = call.recipients.joinToString("、").ifBlank { "—" }
    val toggleDescription = "${if (expanded) "收起" else "展开"} ${if (call.conversation) "$recipients 的协作消息" else "关联调用 ${call.title}"}"
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { expanded = !expanded }
            .semantics { contentDescription = toggleDescription; stateDescription = if (expanded) "已展开" else "已折叠" }
            .padding(horizontal = 7.dp, vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (!call.conversation) Text(call.title, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                color = if (call.status == MessageStatus.Failed) AtollColors.Warning else AtollColors.Text,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                if (call.conversation) Box(Modifier.size(20.dp).background(AtollColors.Selected, RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center) { Text(recipients.take(1).uppercase(), fontSize = 9.sp, color = AtollColors.Muted) }
                Text(if (call.conversation) recipients else "${call.sender} → $recipients", Modifier.weight(1f),
                    fontSize = if (call.conversation) 12.sp else 9.sp, color = AtollColors.Muted,
                    fontWeight = if (call.conversation) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(call.status.label, fontSize = 9.sp,
                    color = when (call.status) { MessageStatus.Completed -> AtollColors.Online;
                        MessageStatus.Failed -> AtollColors.Warning; else -> AtollColors.Muted })
                Text(call.time, fontSize = 9.sp, color = AtollColors.Muted)
                Text(if (expanded) "⌃" else "⌄", fontSize = 10.sp, color = AtollColors.Muted)
            }
            if (call.conversation && !expanded) Text(call.title, Modifier.padding(start = 25.dp),
                fontSize = 11.sp, color = AtollColors.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (expanded) Column(Modifier.fillMaxWidth().padding(horizontal = 7.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (call.conversation && call.request.isNotEmpty()) Surface(color = AtollColors.MutedSurface, shape = RoundedCornerShape(4.dp)) {
                Column(Modifier.fillMaxWidth().drawBehind {
                    drawLine(AtollColors.ControlLine, Offset.Zero, Offset(0f, size.height), 2.dp.toPx())
                }.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("回复 ${call.sender} · ${call.time}", fontSize = 9.sp, color = AtollColors.Muted)
                    Markdown("$messageId:${call.id}:request", call.request, onLink = onLink)
                }
            }
            if (call.text.isNotEmpty()) FoldableMarkdown("$messageId:${call.id}:response", call.text, onLink = onLink)
            call.result?.let { StructuredResult(it) }
            if (!call.conversation && call.text.isEmpty() && call.result == null && call.status != MessageStatus.Completed)
                Text("还没有终态。", fontSize = 10.sp, color = AtollColors.Muted)
            if (call.attachments.isNotEmpty()) MessageAttachments(call.attachments, onLink, onAttachment)
            call.trail?.let { ProgressTrail(it) { onProcess(call.id) } }
            if (call.conversation && call.subTasks.isNotEmpty()) SubTaskTree(call.subTasks, onOpen = onProcess)
        }
    }
}

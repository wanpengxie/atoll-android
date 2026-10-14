package dev.atoll.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.atoll.app.ui.screens.*
import dev.atoll.app.ui.theme.AtollColors
import dev.atoll.app.ui.theme.AtollMono

/**
 * Collaboration (agent to agent or person) is shown open; plain tool and system calls sit behind
 * one "N 次关联调用" chip. Depth is told by the sender › recipient path, and the indent stops
 * after one level so deep threads stay readable on a phone.
 */
@Composable
fun RelatedCalls(messageId: String, calls: List<RelatedCallViewState>, onLink: ((String) -> Unit)? = null,
    onAttachment: MessageAttachmentHandler? = null, onProcess: (String) -> Unit = {}) {
    val (conversations, ordinary) = remember(calls) { calls.partition { it.conversation } }
    if (conversations.isNotEmpty()) CallList(messageId, conversations, true, onLink, onAttachment, onProcess)
    if (ordinary.isNotEmpty()) {
        var expanded by rememberSaveable(messageId) { mutableStateOf(false) }
        val running = ordinary.count { it.status == MessageStatus.Processing || it.status == MessageStatus.Queued }
        val failed = ordinary.count { it.status == MessageStatus.Failed }
        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).clip(RoundedCornerShape(10.dp)).clickable { expanded = !expanded }
            .semantics { stateDescription = if (expanded) "已展开" else "已折叠" }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${ordinary.size} 次关联调用", fontSize = 13.sp, color = AtollColors.Body)
            when {
                running > 0 -> StatusPill("$running 处理中", Tone.Running, live = true)
                failed > 0 -> StatusPill("$failed 失败", Tone.Danger)
                else -> Text("已完成", fontSize = 12.sp, color = AtollColors.Muted)
            }
            Spacer(Modifier.weight(1f))
            Text(if (expanded) "收起" else "展开", fontSize = 12.sp, color = AtollColors.Muted)
        }
        // Closed calls do not compose or measure any result, Markdown or progress widgets.
        if (expanded) CallList(messageId, ordinary, false, onLink, onAttachment, onProcess)
    }
}

@Composable
private fun CallList(messageId: String, calls: List<RelatedCallViewState>, conversation: Boolean,
    onLink: ((String) -> Unit)?, onAttachment: MessageAttachmentHandler?, onProcess: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(AtollColors.Surface)
        .border(1.dp, AtollColors.Line, RoundedCornerShape(12.dp)).semantics {
            contentDescription = if (conversation) "Agent 协作消息" else "关联调用"
        }) {
        calls.forEachIndexed { index, call -> key(call.id) {
            if (index > 0) HorizontalDivider(color = AtollColors.LineSoft)
            Box(Modifier.fillMaxWidth().padding(start = if (call.depth > 1) 12.dp else 0.dp)) {
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
            .padding(horizontal = 12.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (call.conversation) ActorAvatar(recipients, recipients, agent = true, size = 18.dp)
                Text(if (call.conversation) "${call.sender} › $recipients" else call.title, Modifier.weight(1f),
                    fontSize = if (call.conversation) 13.sp else 12.sp, fontWeight = if (call.conversation) FontWeight.SemiBold else FontWeight.Normal,
                    fontFamily = if (call.conversation) null else AtollMono,
                    color = if (call.status == MessageStatus.Failed) AtollColors.Danger else AtollColors.Ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                CallStatus(call.status)
            }
            if (!call.conversation) Text("${call.sender} → $recipients · ${call.time}", fontSize = 11.sp, color = AtollColors.Muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (call.conversation && !expanded) Text(call.title, fontSize = 13.sp, lineHeight = 19.sp, color = AtollColors.Body,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (expanded) Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (call.conversation && call.request.isNotEmpty()) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(AtollColors.Ground)
                .padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.width(2.dp).heightIn(min = 18.dp).background(AtollColors.ControlLine))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("回复 ${call.sender} · ${call.time}", fontSize = 11.sp, color = AtollColors.Muted)
                    Markdown("$messageId:${call.id}:request", call.request, onLink = onLink)
                }
            }
            if (call.text.isNotEmpty()) FoldableMarkdown("$messageId:${call.id}:response", call.text, onLink = onLink)
            call.result?.let { StructuredResult(it) }
            if (!call.conversation && call.text.isEmpty() && call.result == null && call.status != MessageStatus.Completed)
                Text("还没有终态。", fontSize = 12.sp, color = AtollColors.Muted)
            if (call.attachments.isNotEmpty()) MessageAttachments(call.attachments, onLink, onAttachment)
            call.trail?.let { ProgressTrail(it) { onProcess(call.id) } }
            if (call.conversation && call.subTasks.isNotEmpty()) SubTaskTree(call.subTasks, onOpen = onProcess)
        }
    }
}

@Composable
private fun CallStatus(status: MessageStatus) {
    when (status) {
        MessageStatus.Processing -> PulseDot()
        MessageStatus.Queued -> Text(status.label, fontSize = 11.sp, color = AtollColors.Warning)
        MessageStatus.Failed -> Text(status.label, fontSize = 11.sp, color = AtollColors.Danger)
        MessageStatus.Completed -> Text(status.label, fontSize = 11.sp, color = AtollColors.Muted)
    }
}

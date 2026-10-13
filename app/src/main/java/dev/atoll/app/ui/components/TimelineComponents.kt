package dev.atoll.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.atoll.app.ui.screens.*
import dev.atoll.app.ui.theme.AtollColors

@Composable
fun StatusChip(status: MessageStatus) {
    val color = when (status) { MessageStatus.Processing -> AtollColors.Agent; MessageStatus.Queued -> AtollColors.Warning;
        MessageStatus.Failed -> AtollColors.Accent; MessageStatus.Completed -> AtollColors.Online }
    Text(status.label, Modifier.background(color.copy(alpha = .08f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 3.dp), color = color, fontSize = 11.sp)
}
@Composable
fun DaySeparator(text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f), color = AtollColors.Line)
        Text(text, Modifier.padding(horizontal = 12.dp), fontSize = 11.sp, color = AtollColors.Muted)
        HorizontalDivider(Modifier.weight(1f), color = AtollColors.Line)
    }
}
@Composable
fun WeakNotice(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), color = AtollColors.Muted, fontSize = 11.sp)
}
@Composable
fun MessageFrame(message: MessageViewState, onLink: ((String) -> Unit)? = null,
    onAttachment: MessageAttachmentHandler? = null, onRelatedCall: ((String) -> Unit)? = null, onMore: () -> Unit = {}) {
    if (message.self) {
        Column(Modifier.fillMaxWidth().padding(start = 7.dp, end = 7.dp,
            top = if (message.continuation) 4.dp else 10.dp, bottom = if (message.continuation) 8.dp else 10.dp),
            horizontalAlignment = Alignment.End) {
            if (!message.continuation) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(message.sender, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(message.time, fontSize = 10.sp, color = AtollColors.Muted)
                message.recipient?.let { Text("发送给 $it", fontSize = 10.sp, color = AtollColors.Muted) }
            }
            Row(Modifier.padding(top = if (message.continuation) 0.dp else 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Spacer(Modifier.weight(.1f))
                Surface(Modifier.weight(.9f), shape = RoundedCornerShape(12.dp), color = AtollColors.MutedSurface,
                    border = BorderStroke(1.dp, AtollColors.ControlLine)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        MessageContent(message, onLink, onAttachment, onRelatedCall, onMore)
                    }
                }
                if (message.continuation) ContinuationTime(message) else Box(Modifier.size(30.dp).background(AtollColors.Selected, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                    Text(message.sender.take(1).uppercase(), fontSize = 12.sp, color = AtollColors.Muted)
                }
            }
        }
        return
    }
    Row(Modifier.fillMaxWidth().padding(start = 7.dp, end = 7.dp,
        top = if (message.continuation) 4.dp else 10.dp, bottom = if (message.continuation) 8.dp else 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (message.continuation) ContinuationTime(message) else {
            Box(Modifier.size(30.dp).background((if (message.agent) AtollColors.Agent else AtollColors.Accent).copy(alpha = .09f), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                Text(if (message.agent) "✦" else message.sender.take(1).uppercase(), color = if (message.agent) AtollColors.Agent else AtollColors.Accent, fontSize = 15.sp)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!message.continuation) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(message.sender, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                if (message.agent) Text("AI", Modifier.padding(start = 6.dp), fontSize = 10.sp, color = AtollColors.Agent)
                message.recipient?.let { Text(" → $it", fontSize = 11.sp, color = AtollColors.Online) }
                Spacer(Modifier.weight(1f))
                Text(message.time, fontSize = 10.sp, color = AtollColors.Muted)
            }
            MessageContent(message, onLink, onAttachment, onRelatedCall, onMore)
        }
    }
}

/** Like the web avatar-slot timestamp; touch screens can read it without a hover gesture. */
@Composable
private fun ContinuationTime(message: MessageViewState) {
    Text(message.time, Modifier.width(30.dp).padding(top = 2.dp).semantics {
        contentDescription = "${message.sender}，${message.time}"
    }, fontSize = 8.sp, lineHeight = 12.sp, maxLines = 1, color = AtollColors.Muted, textAlign = TextAlign.End)
}

/** Both directions render the same content; only the surrounding bubble changes. */
@Composable
private fun MessageContent(message: MessageViewState, onLink: ((String) -> Unit)?,
    onAttachment: MessageAttachmentHandler?, onRelatedCall: ((String) -> Unit)?, onMore: () -> Unit) {
    if (message.text.isNotEmpty()) FoldableMarkdown(message.id, message.text, message.latest, onLink)
    message.result?.let { StructuredResult(it) }
    message.trail?.let { ProgressTrail(it) { onMore() } }
    if (message.subTasks.isNotEmpty()) SubTaskTree(message.subTasks, onOpen = { onMore() })
    if (message.attachments.isNotEmpty()) MessageAttachments(message.attachments, onLink, onAttachment)
    if (message.relatedCalls.isNotEmpty()) RelatedCalls(message.id, message.relatedCalls, onLink, onAttachment,
        onProcess = { id -> onRelatedCall?.invoke(id) ?: onMore() })
    message.status?.let { status -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusChip(status)
        message.progress?.let { Text(it, fontSize = 11.sp, color = AtollColors.Muted) }
    } }
    if (message.status != null) TextButton(onClick = onMore, contentPadding = PaddingValues(0.dp)) { Text("查看过程 ›", fontSize = 11.sp, color = AtollColors.Muted) }
}

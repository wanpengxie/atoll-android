package dev.atoll.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.atoll.app.ui.screens.*
import dev.atoll.app.ui.theme.AtollColors

/** Running is blue and live, queued is amber, failed is red; completed stays uncoloured. */
@Composable
fun StatusChip(status: MessageStatus, onClick: (() -> Unit)? = null) {
    when (status) {
        MessageStatus.Processing -> StatusPill(status.label, Tone.Running, live = true, onClick = onClick)
        MessageStatus.Queued -> StatusPill(status.label, Tone.Waiting, onClick = onClick)
        MessageStatus.Failed -> StatusPill(status.label, Tone.Danger, onClick = onClick)
        MessageStatus.Completed -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Icon(Icons.Outlined.Check, null, Modifier.size(14.dp), tint = AtollColors.Muted)
            Text(status.label, fontSize = 12.sp, color = AtollColors.Muted)
        }
    }
}
@Composable
fun DaySeparator(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(text, Modifier.clip(RoundedCornerShape(50)).background(AtollColors.Surface).padding(horizontal = 12.dp, vertical = 4.dp),
            fontSize = 12.sp, fontWeight = FontWeight.Medium, color = AtollColors.Body)
    }
}
@Composable
fun WeakNotice(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), color = AtollColors.Muted, fontSize = 12.sp,
        lineHeight = 17.sp, textAlign = TextAlign.Center)
}

private val cardTop = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)
private val cardBottom = RoundedCornerShape(bottomStart = 18.dp, bottomEnd = 18.dp)

/**
 * One timeline message. A request and its answer arrive as two rows and draw as one card:
 * the request is the card's top half, the answer (with its status and work strip) the bottom half.
 * Other messages are plain rows with an avatar, or only the time when they continue the same author.
 */
@Composable
fun MessageFrame(message: MessageViewState, onLink: ((String) -> Unit)? = null,
    onAttachment: MessageAttachmentHandler? = null, onRelatedCall: ((String) -> Unit)? = null, onWorkExpanded: ((Boolean) -> Unit)? = null, onMore: () -> Unit = {}) {
    when (message.part) {
        MessagePart.Request -> Column(Modifier.fillMaxWidth().padding(top = 7.dp).clip(cardTop).background(AtollColors.Surface)
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            CardHeader(message, null, onMore)
            MessageBody(message, onLink, onAttachment)
            WorkStrip(message, onMore, { onRelatedCall?.invoke(it) ?: onMore() }, inset = false, onExpanded = onWorkExpanded)
        }
        MessagePart.Answer -> Column(Modifier.fillMaxWidth().padding(bottom = turnBottomPadding(message)).clip(turnBottomShape(message, cardBottom)).background(AtollColors.Surface)) {
            HorizontalDivider(color = AtollColors.LineSoft)
            Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = if (hasWork(message)) 0.dp else 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CardHeader(message, message.status, onMore)
                MessageBody(message, onLink, onAttachment)
            }
            WorkStrip(message, onMore, { onRelatedCall?.invoke(it) ?: onMore() }, inset = true, onExpanded = onWorkExpanded)
        }
        MessagePart.Single -> Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp,
            top = if (message.continuation) 2.dp else 9.dp, bottom = if (message.continuation) 5.dp else 9.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (message.continuation) ContinuationTime(message)
            else ActorAvatar(message.sender, message.sender, agent = message.agent, self = message.self)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!message.continuation) Row(Modifier.fillMaxWidth().heightIn(min = 20.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (message.self) "我" else message.sender, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (message.self && message.turnState?.edited == true) EditedTag()
                    if (message.agent) AgentTag()
                    message.recipient?.let { Text("→ $it", Modifier.weight(1f, fill = false), fontSize = 13.sp, color = AtollColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    Text(message.time, fontSize = 12.sp, color = AtollColors.Muted)
                    Spacer(Modifier.weight(1f))
                    message.status?.let { StatusChip(it, onMore) }
                }
                MessageBody(message, onLink, onAttachment)
                if (hasWork(message)) Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(AtollColors.Surface)) {
                    WorkStrip(message, onMore, { onRelatedCall?.invoke(it) ?: onMore() }, inset = true, onExpanded = onWorkExpanded)
                }
            }
        }
    }
}

@Composable
private fun AgentTag() {
    Text("Agent", Modifier.clip(RoundedCornerShape(4.dp)).background(AtollColors.Sunken).padding(horizontal = 5.dp, vertical = 1.dp),
        fontSize = 11.sp, fontWeight = FontWeight.Medium, color = AtollColors.Muted)
}

@Composable
private fun CardHeader(message: MessageViewState, status: MessageStatus?, onMore: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActorAvatar(message.sender, message.sender, agent = message.agent, size = 24.dp, self = message.self)
        Text(if (message.self) "我" else message.sender, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (message.self && message.turnState?.edited == true) EditedTag()
        message.recipient?.let { Text("→ $it", Modifier.weight(1f, fill = false), fontSize = 13.sp, color = AtollColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        Spacer(Modifier.weight(1f))
        if (status != null) StatusChip(status, onMore) else Text(message.time, fontSize = 12.sp, color = AtollColors.Muted)
    }
}

/** Like the web avatar-slot timestamp; touch screens can read it without a hover gesture. */
@Composable
private fun ContinuationTime(message: MessageViewState) {
    Text(message.time, Modifier.width(32.dp).padding(top = 3.dp).semantics {
        contentDescription = "${message.sender}，${message.time}"
    }, fontSize = 10.sp, lineHeight = 14.sp, maxLines = 1, color = AtollColors.Faint, textAlign = TextAlign.End)
}

/** The prose part of a message: text, structured result and attachments. */
@Composable
private fun MessageBody(message: MessageViewState, onLink: ((String) -> Unit)?, onAttachment: MessageAttachmentHandler?) {
    if (message.text.isNotEmpty()) FoldableMarkdown(message.id, message.text, message.latest, onLink)
    message.result?.let { if (message.turnState?.kind == TurnKind.Failed && it.errorCode != null) TurnFailureSummary(it) else StructuredResult(it) }
    if (message.attachments.isNotEmpty()) MessageAttachments(message.attachments, onLink, onAttachment)
}

internal fun hasWork(message: MessageViewState) = showTurnWorkStrip(message)

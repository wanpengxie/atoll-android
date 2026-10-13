package dev.atoll.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.atoll.app.ui.screens.*
import dev.atoll.app.ui.theme.AtollColors

@Composable
fun StatusChip(status: MessageStatus) {
    val color = when (status) { MessageStatus.Processing -> AtollColors.Agent; MessageStatus.Queued -> AtollColors.Warning; MessageStatus.Failed -> AtollColors.Accent }
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
fun MessageFrame(message: MessageViewState, onLink: ((String) -> Unit)? = null, onMore: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = if (message.continuation) 2.dp else 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (message.continuation) Spacer(Modifier.width(30.dp)) else {
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
            Markdown(message.id, message.text, onLink = onLink)
            message.status?.let { status -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip(status)
                message.progress?.let { Text(it, fontSize = 11.sp, color = AtollColors.Muted) }
            } }
            if (message.status != null) TextButton(onClick = onMore, contentPadding = PaddingValues(0.dp)) { Text("查看过程 ›", fontSize = 11.sp, color = AtollColors.Muted) }
        }
    }
}

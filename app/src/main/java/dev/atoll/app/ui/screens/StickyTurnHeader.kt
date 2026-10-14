package dev.atoll.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.atoll.app.ui.components.*
import dev.atoll.app.ui.theme.AtollColors

@Immutable
internal data class StickyTurnHeaderState(val message: MessageViewState, val request: MessageViewState?)

/** Pin only an expanded tall row after its original title has left the viewport. */
internal fun stickyTurnHeader(entry: TimelineEntry?, previous: TimelineEntry?, expanded: Set<String>,
    offset: Int, size: Int, viewportStart: Int, viewportEnd: Int, headerSize: Int): StickyTurnHeaderState? {
    val message = (entry as? TimelineEntry.Message)?.value ?: return null
    val viewport = viewportEnd - viewportStart
    if (message.id !in expanded || viewport <= 0 || size <= viewport || offset + headerSize > viewportStart) return null
    val request = (previous as? TimelineEntry.Message)?.value?.takeIf {
        message.part == MessagePart.Answer && it.part == MessagePart.Request &&
            it.id.removeSuffix(":request") == message.id.removeSuffix(":answer")
    }
    return StickyTurnHeaderState(message, request)
}

@Composable
internal fun StickyTurnHeader(state: StickyTurnHeaderState, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val message = state.message
    val title = remember(state) {
        (state.request?.text ?: message.text).lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(100)
    }
    Column(modifier.fillMaxWidth().background(AtollColors.Surface).semantics { contentDescription = "粘顶回合标题" }) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(role = Role.Button, onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActorAvatar(message.sender, message.sender, agent = message.agent, self = message.self, size = 20.dp)
            Text(buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(if (message.self) "我" else message.sender) }
                if (title.isNotEmpty()) append(" · $title")
            }, Modifier.weight(1f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (message.status == MessageStatus.Processing || message.trail?.running == true) {
                PulseDot(size = 6.dp)
                WorkElapsed(message.trail?.startedAtMillis ?: 0)
            } else message.status?.let { WorkMark(it) }
        }
        HorizontalDivider(color = AtollColors.Line)
    }
}

package dev.atoll.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.atoll.app.ui.theme.ChatColors

/** Shared WeChat-style pieces for the conversation's sheets and lists (STYLE.md). */
val ChatSheetShape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)

@Composable
fun ChatGrabber() {
    Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(36.dp, 4.dp).clip(CircleShape).background(ChatColors.Line))
    }
}

/** Grabber, a 17sp title (optional grey subtitle) and one text action on the right. */
@Composable
fun ChatSheetHeader(title: String, action: String, onAction: () -> Unit, subtitle: String = "", actionIsLink: Boolean = false) {
    ChatGrabber()
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = ChatColors.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotEmpty()) Text(subtitle, fontSize = 13.sp, color = ChatColors.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(action, Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onAction)
            .padding(horizontal = 12.dp).wrapContentHeight(), fontSize = if (actionIsLink) 16.sp else 15.sp,
            fontWeight = if (actionIsLink) FontWeight.Medium else FontWeight.Normal, color = if (actionIsLink) ChatColors.Link else ChatColors.Gray)
    }
}

/** 13sp grey label on the grey ground, above a white group. */
@Composable
fun ChatSectionLabel(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp), fontSize = 13.sp, color = ChatColors.Gray)

/** A white row: optional leading, 16sp title and 13sp subtitle, trailing content, hairline inset 16dp below. */
@Composable
fun ChatListRow(title: String, modifier: Modifier = Modifier, subtitle: String? = null, minHeight: androidx.compose.ui.unit.Dp = 56.dp,
    divider: Boolean = true, titleColor: Color = ChatColors.Ink, onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null, subtitleContent: (@Composable RowScope.() -> Unit)? = null, trailing: (@Composable RowScope.() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().background(ChatColors.Block)) {
        Row(Modifier.fillMaxWidth().heightIn(min = minHeight).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            leading?.invoke()
            Column(Modifier.weight(1f).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontSize = 16.sp, lineHeight = 22.sp, color = titleColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (subtitleContent != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), content = subtitleContent)
                else subtitle?.let { Text(it, fontSize = 13.sp, lineHeight = 18.sp, color = ChatColors.Gray, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
            if (trailing != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), content = trailing)
        }
        if (divider) HorizontalDivider(Modifier.padding(start = 16.dp), color = ChatColors.LineInWhite)
    }
}

@Composable
fun ChatChevron() = Text("›", fontSize = 18.sp, color = ChatColors.Hint)

/** The one filled primary button per screen. */
@Composable
fun ChatPrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(8.dp)).background(if (enabled) ChatColors.Green else ChatColors.Green.copy(alpha = .4f))
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color.White)
    }
}

@Composable
fun ChatSecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = ChatColors.Ink) {
    Box(modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(8.dp)).background(ChatColors.Secondary)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 16.sp, color = if (enabled) color else ChatColors.Hint)
    }
}

/** A grey one-line notice on the bar ground (offline, read-only, errors in red). */
@Composable
fun ChatNotice(text: String, danger: Boolean = false, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f).padding(vertical = 4.dp), fontSize = 13.sp, lineHeight = 18.sp, color = if (danger) ChatColors.Red else ChatColors.Gray)
        action?.let { Text(it, Modifier.heightIn(min = 36.dp).clickable(onClick = onAction).padding(horizontal = 8.dp).wrapContentHeight(), fontSize = 13.sp, color = ChatColors.Link) }
    }
}

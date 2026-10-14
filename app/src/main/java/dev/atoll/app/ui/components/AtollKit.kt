package dev.atoll.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.atoll.app.ui.theme.AtollAvatarTints
import dev.atoll.app.ui.theme.AtollColors
import dev.atoll.app.ui.theme.AtollShapes

/** Shape tells agents (rounded square) from people (circle); colour alone never does. */
@Composable
fun ActorAvatar(name: String, id: String = name, agent: Boolean, size: Dp = 32.dp, self: Boolean = false, modifier: Modifier = Modifier) {
    val tint = if (self) AtollAvatarTints.Self else AtollAvatarTints.of(id.ifEmpty { name })
    val label = if (self) "我" else name.trim().removePrefix("agent:").removePrefix("human:").take(1).uppercase().ifEmpty { "?" }
    Box(modifier.size(size).clip(if (agent) RoundedCornerShape(size * 0.28f) else CircleShape).background(tint.background),
        contentAlignment = Alignment.Center) {
        Text(label, color = tint.content, fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

enum class Tone { Running, Waiting, Danger, Neutral, Lagoon }

private data class ToneColors(val surface: Color, val content: Color, val dot: Color)
private fun toneColors(tone: Tone) = when (tone) {
    Tone.Running -> ToneColors(AtollColors.RunningSurface, AtollColors.RunningText, AtollColors.Running)
    Tone.Waiting -> ToneColors(AtollColors.WarningSurface, AtollColors.Warning, AtollColors.Warning)
    Tone.Danger -> ToneColors(AtollColors.DangerSurface, AtollColors.Danger, AtollColors.Danger)
    Tone.Neutral -> ToneColors(AtollColors.Sunken, AtollColors.Body, AtollColors.Faint)
    Tone.Lagoon -> ToneColors(AtollColors.LagoonSurface, AtollColors.LagoonText, AtollColors.Lagoon)
}

/** A live dot; the halo pulses only while the state is ongoing. */
@Composable
fun PulseDot(color: Color = AtollColors.Running, size: Dp = 6.dp, pulsing: Boolean = true) {
    val alpha = if (pulsing) {
        val transition = rememberInfiniteTransition(label = "pulse")
        transition.animateFloat(1f, .35f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "pulse-alpha").value
    } else 1f
    Box(Modifier.size(size).alpha(alpha).background(color, CircleShape))
}

@Composable
fun Spinner(size: Dp = 13.dp, color: Color = AtollColors.Running, track: Color = AtollColors.RunningTrack) {
    CircularProgressIndicator(Modifier.size(size), color = color, trackColor = track, strokeWidth = 2.dp)
}

@Composable
fun StatusPill(text: String, tone: Tone, modifier: Modifier = Modifier, live: Boolean = false, onClick: (() -> Unit)? = null) {
    val colors = toneColors(tone)
    Row(modifier.heightIn(min = 24.dp).clip(AtollShapes.Pill).background(colors.surface)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(horizontal = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (live) PulseDot(colors.dot)
        Text(text, color = colors.content, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
fun CountBadge(text: String, tone: Tone = Tone.Neutral, strong: Boolean = false) {
    val colors = toneColors(tone)
    Box(Modifier.heightIn(min = 18.dp).widthIn(min = 18.dp).clip(AtollShapes.Pill)
        .background(if (strong) AtollColors.Ink else colors.surface).padding(horizontal = 5.dp), contentAlignment = Alignment.Center) {
        Text(text, color = if (strong) Color.White else colors.content, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Two to four mutually exclusive options. */
@Composable
fun SegmentedControl(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, fill: Boolean = false) {
    Row(modifier.clip(AtollShapes.Pill).background(AtollColors.Sunken).padding(3.dp)) {
        options.forEachIndexed { index, label ->
            val active = index == selected
            Box((if (fill) Modifier.weight(1f) else Modifier).heightIn(min = 30.dp).clip(AtollShapes.Pill)
                .background(if (active) AtollColors.Surface else Color.Transparent)
                .semantics { this.selected = active }
                .clickable(role = Role.Tab) { onSelect(index) }.padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center) {
                Text(label, fontSize = 13.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) AtollColors.Ink else AtollColors.Muted, maxLines = 1)
            }
        }
    }
}

/** Actor filter chip with its avatar; the dot shows a running turn. */
@Composable
fun ActorChip(name: String, id: String, selected: Boolean, running: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.heightIn(min = 34.dp).clip(AtollShapes.Pill)
        .background(if (selected) AtollColors.LagoonSurface else AtollColors.Surface)
        .border(BorderStroke(1.dp, if (selected) AtollColors.Lagoon.copy(alpha = .45f) else AtollColors.ControlLine), AtollShapes.Pill)
        .semantics { this.selected = selected }
        .clickable(onClick = onClick).padding(start = 4.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ActorAvatar(name, id, agent = true, size = 24.dp)
        Text(name, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) AtollColors.LagoonText else AtollColors.Body, maxLines = 1)
        if (running) PulseDot()
    }
}

/** Plain text chip used for counts and choices. */
@Composable
fun TextChip(text: String, modifier: Modifier = Modifier, selected: Boolean = false, tone: Tone? = null, onClick: (() -> Unit)? = null) {
    val colors = tone?.let(::toneColors)
    Row(modifier.heightIn(min = 28.dp).clip(AtollShapes.Pill)
        .background(when { selected -> AtollColors.Ink; colors != null -> colors.surface; else -> AtollColors.Surface })
        .then(if (!selected && colors == null) Modifier.border(1.dp, AtollColors.Line, AtollShapes.Pill) else Modifier)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        if (tone == Tone.Running && !selected) PulseDot(size = 5.dp)
        Text(text, fontSize = 12.sp, color = when { selected -> Color.White; colors != null -> colors.content; else -> AtollColors.Ink }, maxLines = 1)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: String = "") {
    Row(modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AtollColors.Muted)
        if (trailing.isNotEmpty()) Text(trailing, fontSize = 13.sp, color = AtollColors.Muted)
    }
}

/** Primary actions are ink; secondary ones are outlined; destructive ones are red text on white. */
enum class ButtonKind { Primary, Secondary, Danger, Text }

@Composable
fun AtollButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, kind: ButtonKind = ButtonKind.Primary,
    enabled: Boolean = true, busy: Boolean = false, icon: ImageVector? = null, height: Dp = 44.dp) {
    val container = when (kind) { ButtonKind.Primary -> AtollColors.Ink; ButtonKind.Secondary, ButtonKind.Danger -> AtollColors.Surface; ButtonKind.Text -> Color.Transparent }
    val content = when (kind) { ButtonKind.Primary -> Color.White; ButtonKind.Danger -> AtollColors.Danger; ButtonKind.Text -> AtollColors.Lagoon; ButtonKind.Secondary -> AtollColors.Ink }
    val border = when (kind) { ButtonKind.Secondary -> BorderStroke(1.dp, AtollColors.ControlLine); ButtonKind.Danger -> BorderStroke(1.dp, AtollColors.DangerLine); else -> null }
    Button(onClick, modifier.heightIn(min = height), enabled = enabled && !busy, shape = AtollShapes.Button, border = border,
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content,
            disabledContainerColor = if (kind == ButtonKind.Primary) AtollColors.Sunken else container, disabledContentColor = AtollColors.Faint),
        contentPadding = PaddingValues(horizontal = if (kind == ButtonKind.Text) 10.dp else 16.dp)) {
        if (busy) { Spinner(14.dp, if (kind == ButtonKind.Primary) Color.White else AtollColors.Ink, AtollColors.Faint); Spacer(Modifier.width(8.dp)) }
        else if (icon != null) { Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, fontSize = 15.sp, fontWeight = if (kind == ButtonKind.Primary) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
    }
}

/** Grouped white rows on the paper ground, the list idiom of every secondary page. */
@Composable
fun GroupCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(AtollShapes.Card).background(AtollColors.Surface)
        .border(1.dp, AtollColors.Line, AtollShapes.Card), content = content)
}

@Composable
fun GroupRow(title: String, modifier: Modifier = Modifier, value: String = "", subtitle: String = "", valueColor: Color = AtollColors.Muted,
    divider: Boolean = true, onClick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    Column {
        Row(modifier.fillMaxWidth().heightIn(min = 52.dp).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(title, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotEmpty()) Text(subtitle, fontSize = 12.sp, color = AtollColors.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (value.isNotEmpty()) Text(value, fontSize = 14.sp, color = valueColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            trailing?.invoke()
            if (onClick != null && trailing == null) Text("›", fontSize = 18.sp, color = AtollColors.Faint)
        }
        if (divider) HorizontalDivider(Modifier.padding(start = 14.dp), color = AtollColors.LineSoft)
    }
}

/** Banner for connection, history window and failures: what happened and what can be done. */
@Composable
fun NoticeBanner(text: String, tone: Tone, modifier: Modifier = Modifier, action: String? = null, onAction: () -> Unit = {},
    secondary: String? = null, onSecondary: () -> Unit = {}) {
    val colors = toneColors(tone)
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.surface).padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f).padding(vertical = 6.dp), fontSize = 13.sp, lineHeight = 19.sp,
            color = if (tone == Tone.Waiting) AtollColors.WarningInk else colors.content)
        if (action != null) TextButton(onAction, Modifier.heightIn(min = 40.dp)) { Text(action, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.content) }
        if (secondary != null) TextButton(onSecondary, Modifier.heightIn(min = 40.dp)) { Text(secondary, fontSize = 13.sp, color = AtollColors.Muted) }
    }
}

/** Sheet heading: grabber, title, optional subtitle and trailing action. */
@Composable
fun SheetHeader(title: String, onClose: () -> Unit, subtitle: String = "", closeLabel: String = "关闭", trailing: (@Composable RowScope.() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(36.dp, 4.dp).background(AtollColors.ControlLine, CircleShape))
        }
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotEmpty()) Text(subtitle, fontSize = 13.sp, color = AtollColors.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            trailing?.invoke(this)
            TextButton(onClose, Modifier.heightIn(min = 44.dp)) { Text(closeLabel, fontSize = 15.sp, color = AtollColors.Muted) }
        }
    }
}

/** A row of scrollable chips that never wraps into the content below. */
@Composable
fun ChipRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically, content = content)
}

/** Large page title used by top-level destinations. */
@Composable
fun PageTitle(text: String, modifier: Modifier = Modifier, trailing: (@Composable RowScope.() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
        trailing?.invoke(this)
    }
}

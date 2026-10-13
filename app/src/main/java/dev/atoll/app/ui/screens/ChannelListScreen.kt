package dev.atoll.app.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.atoll.app.ui.theme.AtollColors
import kotlinx.coroutines.delay

/** Compatibility entry point; live integration should pass the complete navigation state. */
@Composable
fun ChannelListScreen(channels: List<ChannelViewState>, connection: String, onOpen: (String) -> Unit,
    onCreate: () -> Unit = {}, onActivity: () -> Unit = {}, onSettings: () -> Unit = {}, activeChannelId: String? = null) {
    ChannelListScreen(ChannelNavigationViewState(channels,
        ConnectionPhase.entries.firstOrNull { it.name == connection.uppercase() } ?: ConnectionPhase.OPEN,
        AccountViewState("", "已登录用户"), activeChannelId = activeChannelId), onOpen, onCreate, onActivity, onSettings = onSettings)
}
@Composable
fun ChannelListScreen(state: ChannelNavigationViewState, onOpen: (String) -> Unit,
    onCreate: () -> Unit = {}, onActivity: () -> Unit = {}, onSpaceAdmin: () -> Unit = {},
    onLogout: () -> Unit = {}, onUpgrade: () -> Unit = {}, onClose: (() -> Unit)? = null, onSettings: () -> Unit = {}) {
    var collapsed by remember { mutableStateOf(emptySet<String>()) }
    val branches = state.channels.mapNotNull { it.parentId }.toSet()
    val visible = visibleChannels(state.channels, collapsed)
    val toggle: (String) -> Unit = { id -> collapsed = if (id in collapsed) collapsed - id else collapsed + id }
    var upgradeConfirmation by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(AtollColors.ChannelRail).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 58.dp).padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("ATOLL", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            val color = if (state.connection == ConnectionPhase.OPEN) AtollColors.Online else AtollColors.Warning
            Box(Modifier.size(6.dp).background(color, CircleShape))
            Text(state.connection.name, Modifier.padding(horizontal = 7.dp), color = AtollColors.Muted, fontSize = 9.sp)
            if (onClose != null) TextButton(onClose, Modifier.size(44.dp)) { Text("×", color = AtollColors.Text, fontSize = 20.sp) }
        }
        HorizontalDivider(color = AtollColors.Line)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)) {
            item("activity") { OutlinedButton(onActivity, Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small) { Text("◷ 活动", fontSize = 12.sp) } }
            val members = state.channels.filter { it.access.member }
            val others = state.channels.filterNot { it.access.member }
            item("members-label") { RailCaption("我的频道", members.size) }
            item("create") { TextButton(onCreate, Modifier.fillMaxWidth().height(44.dp), contentPadding = PaddingValues(horizontal = 4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("＋ 新建频道", fontSize = 12.sp) }
            } }
            if (members.isEmpty()) item("members-empty") { RailEmpty("还没有加入频道") }
            items(visible.filter { it.access.member }, key = { it.id }) { ChannelRow(it, state.activeChannelId == it.id, it.id in branches, it.id in collapsed, toggle, onOpen) }
            item("space-label") { RailCaption("空间", others.size) }
            if (others.isEmpty()) item("space-empty") { RailEmpty("没有可发现频道") }
            items(visible.filterNot { it.access.member }, key = { it.id }) { ChannelRow(it, state.activeChannelId == it.id, it.id in branches, it.id in collapsed, toggle, onOpen) }
        }
        if (state.account.root) state.upgrade?.let { update ->
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                OutlinedButton({ upgradeConfirmation = true }, Modifier.fillMaxWidth(), enabled = update.canStart, shape = MaterialTheme.shapes.small) {
                    Text(update.label(state.connection), fontSize = 12.sp)
                }
                Text("当前版本：${update.current}（只读）", fontSize = 10.sp, color = AtollColors.Muted)
            }
        }
        HorizontalDivider(color = AtollColors.Line)
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.dp).background(AtollColors.Selected, MaterialTheme.shapes.medium), contentAlignment = Alignment.Center) {
                Text(state.account.name.ifEmpty { state.account.id }.take(1).uppercase(), fontSize = 12.sp)
            }
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(state.account.name, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                Text(state.account.id, color = AtollColors.Muted, fontSize = 10.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onSettings, Modifier.height(44.dp), contentPadding = PaddingValues(horizontal = 4.dp)) { Text("设置", fontSize = 11.sp, color = AtollColors.Muted) }
                TextButton(onSpaceAdmin, Modifier.height(44.dp), contentPadding = PaddingValues(horizontal = 4.dp)) { Text("空间管理", fontSize = 11.sp, color = AtollColors.Muted) }
                TextButton(onLogout, Modifier.height(44.dp), contentPadding = PaddingValues(horizontal = 4.dp)) { Text("退出", fontSize = 11.sp, color = AtollColors.Muted) }
            }
        }
    }
    if (upgradeConfirmation) state.upgrade?.let { update -> AlertDialog(onDismissRequest = { upgradeConfirmation = false },
        title = { Text("升级到 ${update.latest}？") }, text = { Text("升级会重启 Atoll，并暂时中断当前连接和正在进行的工作。频道记录、任务和工作区数据会保留。") },
        confirmButton = { TextButton({ upgradeConfirmation = false; onUpgrade() }) { Text("确认") } },
        dismissButton = { TextButton({ upgradeConfirmation = false }) { Text("取消") } }) }
}
@Composable private fun RailCaption(text: String, count: Int) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp)) {
        Text(text, fontSize = 12.sp, color = AtollColors.Muted, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text(count.toString(), fontSize = 10.sp, color = AtollColors.Muted)
    }
}
@Composable private fun RailEmpty(text: String) { Text(text, Modifier.padding(horizontal = 8.dp, vertical = 12.dp), fontSize = 12.sp, color = AtollColors.Muted) }
@Composable private fun ChannelRow(channel: ChannelViewState, selected: Boolean, branch: Boolean, collapsed: Boolean, onToggle: (String) -> Unit, onOpen: (String) -> Unit) {
    // Each visible row owns its timer, so a tick never rebuilds the channel list.
    val now by produceState(System.currentTimeMillis(), channel.activity.isNotEmpty()) {
        if (channel.activity.isNotEmpty()) while (true) { value = System.currentTimeMillis(); delay(1000) }
    }
    Row(Modifier.fillMaxWidth().background(if (selected) AtollColors.Selected else AtollColors.ChannelRail, MaterialTheme.shapes.medium)
        .heightIn(min = 44.dp).clickable { onOpen(channel.id) }
        .padding(start = (8 + channel.depth.coerceAtLeast(0) * 12).dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (branch) TextButton({ onToggle(channel.id) }, Modifier.size(44.dp), contentPadding = PaddingValues(0.dp)) {
            Text(if (collapsed) "›" else "⌄", color = AtollColors.Muted)
        } else Spacer(Modifier.width(44.dp))
        Text("#", fontSize = 12.sp, color = AtollColors.Muted)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(channel.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (channel.runningElapsed.isNotEmpty()) Text(channel.runningElapsed, fontSize = 10.sp, color = AtollColors.Online, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (channel.activity.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                channel.activity.take(2).forEach { active -> Text("● ${active.name} ${activityDuration(active.startedAtMillis, now)}", fontSize = 10.sp, color = AtollColors.Online) }
                if (channel.activity.size > 2) Text("+${channel.activity.size - 2}", fontSize = 10.sp, color = AtollColors.Muted)
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (channel.access.label.isNotEmpty()) Text(channel.access.label, color = AtollColors.Muted, fontSize = 10.sp)
            unreadLabel(channel.unread, channel.unreadPhase).takeIf { it.isNotEmpty() }?.let { label ->
                if (channel.unreadPhase == UnreadPhase.Ready) Badge { Text(label) } else Text(label, color = AtollColors.Muted, fontSize = 12.sp)
            }
        }
    }
}

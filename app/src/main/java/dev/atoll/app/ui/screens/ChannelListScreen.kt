package dev.atoll.app.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.atoll.app.ui.components.*
import dev.atoll.app.ui.theme.AtollColors
import dev.atoll.app.ui.theme.AtollShapes
import kotlinx.coroutines.delay

/** Compatibility entry point; live integration should pass the complete navigation state. */
@Composable
fun ChannelListScreen(channels: List<ChannelViewState>, connection: String, onOpen: (String) -> Unit,
    onCreate: () -> Unit = {}, onActivity: () -> Unit = {}, onSettings: () -> Unit = {}, activeChannelId: String? = null) {
    ChannelListScreen(ChannelNavigationViewState(channels,
        ConnectionPhase.entries.firstOrNull { it.name == connection.uppercase() } ?: ConnectionPhase.OPEN,
        AccountViewState("", "已登录用户"), activeChannelId = activeChannelId), onOpen, onCreate, onActivity, onSettings = onSettings)
}

/**
 * The home destination. Without [onClose] it is the root page with the 频道 · 活动 · 我 bar;
 * with [onClose] it is the same list shown as a drawer over a channel.
 */
@Composable
fun ChannelListScreen(state: ChannelNavigationViewState, onOpen: (String) -> Unit,
    onCreate: () -> Unit = {}, onActivity: () -> Unit = {}, onSpaceAdmin: () -> Unit = {},
    onLogout: () -> Unit = {}, onUpgrade: () -> Unit = {}, onClose: (() -> Unit)? = null, onSettings: () -> Unit = {}) {
    var collapsed by remember { mutableStateOf(emptySet<String>()) }
    var query by rememberSaveable { mutableStateOf("") }
    val branches = state.channels.mapNotNull { it.parentId }.toSet()
    val searching = query.isNotBlank()
    val visible = if (searching) state.channels.filter { it.name.contains(query.trim(), ignoreCase = true) } else visibleChannels(state.channels, collapsed)
    val toggle: (String) -> Unit = { id -> collapsed = if (id in collapsed) collapsed - id else collapsed + id }
    var upgradeConfirmation by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(AtollColors.Ground).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("频道", style = MaterialTheme.typography.headlineSmall)
            ConnectionPill(state.connection)
            Spacer(Modifier.weight(1f))
            IconButton(onCreate, Modifier.size(44.dp)) { Icon(Icons.Outlined.Add, "新建频道", Modifier.size(24.dp)) }
            if (onClose != null) IconButton(onClose, Modifier.size(44.dp)) { Icon(Icons.Outlined.Close, "关闭频道列表", Modifier.size(22.dp)) }
        }
        SearchField(query, { query = it }, Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp))
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 16.dp)) {
            if (!searching) item("activity") {
                Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 16.dp).clip(AtollShapes.Card).background(AtollColors.Surface)
                    .border(1.dp, AtollColors.Line, AtollShapes.Card).clickable(onClick = onActivity).padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(AtollColors.WarningSurface), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.VerifiedUser, null, Modifier.size(19.dp), tint = AtollColors.Warning)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("活动", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text("所有频道里等你处理的审批、提问，以及运行中的工作", fontSize = 13.sp, color = AtollColors.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Text("›", fontSize = 18.sp, color = AtollColors.Faint)
                }
            }
            val members = state.channels.filter { it.access.member }
            val others = state.channels.filterNot { it.access.member }
            item("members-label") { SectionLabel("我的频道", Modifier.padding(horizontal = 8.dp), members.size.toString()) }
            if (members.isEmpty()) item("members-empty") { RailEmpty("还没有加入频道") }
            items(visible.filter { it.access.member }, key = { it.id }) {
                ChannelRow(it, state.activeChannelId == it.id, !searching && it.id in branches, it.id in collapsed, searching, toggle, onOpen)
            }
            item("space-label") { SectionLabel("空间里的其他频道", Modifier.padding(start = 8.dp, end = 8.dp, top = 12.dp), others.size.toString()) }
            if (others.isEmpty()) item("space-empty") { RailEmpty("没有可发现频道") }
            items(visible.filterNot { it.access.member }, key = { it.id }) {
                ChannelRow(it, state.activeChannelId == it.id, !searching && it.id in branches, it.id in collapsed, searching, toggle, onOpen)
            }
            if (searching && visible.isEmpty()) item("no-match") { RailEmpty("没有名称包含“${query.trim()}”的频道") }
            if (state.account.root) state.upgrade?.let { update -> item("upgrade") {
                Column(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    AtollButton(update.label(state.connection), { upgradeConfirmation = true }, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary, enabled = update.canStart)
                    Text("当前版本：${update.current}（只读）", Modifier.fillMaxWidth(), fontSize = 12.sp, color = AtollColors.Muted)
                }
            } }
            if (onClose != null) item("account") { AccountFooter(state.account, onSettings, onSpaceAdmin, onLogout) }
        }
        if (onClose == null) HomeBar(HomeDestination.Channels, onChannels = {}, onActivity = onActivity, onMe = onSettings)
    }
    if (upgradeConfirmation) state.upgrade?.let { update -> AlertDialog(onDismissRequest = { upgradeConfirmation = false },
        title = { Text("升级到 ${update.latest}？") }, text = { Text("升级会重启 Atoll，并暂时中断当前连接和正在进行的工作。频道记录、任务和工作区数据会保留。") },
        confirmButton = { TextButton({ upgradeConfirmation = false; onUpgrade() }) { Text("确认") } },
        dismissButton = { TextButton({ upgradeConfirmation = false }) { Text("取消") } }) }
}

enum class HomeDestination { Channels, Activity, Me }

/** Bottom navigation of the three top-level destinations. */
@Composable
fun HomeBar(selected: HomeDestination, onChannels: () -> Unit, onActivity: () -> Unit, onMe: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(AtollColors.Surface)) {
        HorizontalDivider(color = AtollColors.Line)
        Row(Modifier.fillMaxWidth().heightIn(min = 68.dp)) {
            HomeBarItem("频道", Icons.Outlined.Tag, selected == HomeDestination.Channels, onChannels)
            HomeBarItem("活动", Icons.Outlined.Timeline, selected == HomeDestination.Activity, onActivity)
            HomeBarItem("我", Icons.Outlined.Person, selected == HomeDestination.Me, onMe)
        }
    }
}

@Composable
private fun RowScope.HomeBarItem(label: String, icon: ImageVector, active: Boolean, onClick: () -> Unit) {
    Column(Modifier.weight(1f).heightIn(min = 68.dp).semantics { selected = active }.clickable(role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(56.dp, 30.dp).clip(AtollShapes.Pill).background(if (active) AtollColors.LagoonSurface else Color.Transparent),
            contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(21.dp), tint = if (active) AtollColors.LagoonText else AtollColors.Muted)
        }
        Spacer(Modifier.height(3.dp))
        Text(label, fontSize = 12.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = if (active) AtollColors.Ink else AtollColors.Muted)
    }
}

@Composable
private fun ConnectionPill(phase: ConnectionPhase) {
    val open = phase == ConnectionPhase.OPEN
    Row(Modifier.padding(start = 8.dp).height(24.dp).clip(AtollShapes.Pill)
        .background(if (open) AtollColors.LagoonSurface else AtollColors.WarningSurface).padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PulseDot(if (open) AtollColors.Lagoon else AtollColors.Warning, pulsing = !open)
        Text(when (phase) { ConnectionPhase.OPEN -> "已连接"; ConnectionPhase.CONNECTING -> "连接中"; ConnectionPhase.RECONNECTING -> "重连中"; ConnectionPhase.CLOSED -> "已断开" },
            fontSize = 12.sp, fontWeight = FontWeight.Medium, color = if (open) AtollColors.LagoonText else AtollColors.Warning)
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(14.dp)).background(AtollColors.Sunken).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Outlined.Search, null, Modifier.size(19.dp), tint = AtollColors.Muted)
        BasicTextField(value, onChange, Modifier.weight(1f), singleLine = true, textStyle = TextStyle(fontSize = 15.sp, color = AtollColors.Ink),
            decorationBox = { inner -> if (value.isEmpty()) Text("搜索频道", fontSize = 15.sp, color = AtollColors.Muted); inner() })
        if (value.isNotEmpty()) IconButton({ onChange("") }, Modifier.size(36.dp)) { Icon(Icons.Outlined.Close, "清除搜索", Modifier.size(18.dp), tint = AtollColors.Muted) }
    }
}

@Composable private fun RailEmpty(text: String) { Text(text, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), fontSize = 14.sp, color = AtollColors.Muted) }

@Composable
private fun AccountFooter(account: AccountViewState, onSettings: () -> Unit, onSpaceAdmin: () -> Unit, onLogout: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 20.dp)) {
        GroupCard {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActorAvatar(account.name.ifEmpty { account.id }, account.id, agent = false, size = 36.dp)
                Column(Modifier.weight(1f)) {
                    Text(account.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(account.id, fontSize = 12.sp, color = AtollColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            HorizontalDivider(color = AtollColors.LineSoft)
            GroupRow("设置", onClick = onSettings)
            if (account.root) GroupRow("空间管理", onClick = onSpaceAdmin)
            GroupRow("退出", divider = false, valueColor = AtollColors.Danger, onClick = onLogout)
        }
    }
}

@Composable private fun ChannelRow(channel: ChannelViewState, selected: Boolean, branch: Boolean, collapsed: Boolean, flat: Boolean,
    onToggle: (String) -> Unit, onOpen: (String) -> Unit) {
    // Each visible row owns its timer, so a tick never rebuilds the channel list.
    val now by produceState(System.currentTimeMillis(), channel.activity.isNotEmpty()) {
        if (channel.activity.isNotEmpty()) while (true) { value = System.currentTimeMillis(); delay(1000) }
    }
    val member = channel.access.member
    Row(Modifier.fillMaxWidth().clip(AtollShapes.Row).background(if (selected) AtollColors.Surface else Color.Transparent)
        .heightIn(min = 60.dp).clickable { onOpen(channel.id) }
        .padding(start = if (flat) 12.dp else (channel.depth.coerceAtLeast(0) * 16).dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!flat) {
            if (branch) IconButton({ onToggle(channel.id) }, Modifier.size(40.dp)) {
                Icon(if (collapsed) Icons.Outlined.ChevronRight else Icons.Outlined.KeyboardArrowDown,
                    if (collapsed) "展开 ${channel.name}" else "折叠 ${channel.name}", Modifier.size(18.dp), tint = AtollColors.Muted)
            } else Spacer(Modifier.width(40.dp))
        }
        Column(Modifier.weight(1f).padding(vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("# ", fontSize = 15.sp, color = AtollColors.Faint)
                Text(channel.name, fontSize = 15.sp, fontWeight = if (member) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (member) AtollColors.Ink else AtollColors.Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            when {
                channel.activity.isNotEmpty() -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    PulseDot()
                    Text(channel.activity.take(2).joinToString("、") { "${it.name} ${activityDuration(it.startedAtMillis, now)}" } + " 运行中",
                        Modifier.weight(1f, fill = false), fontSize = 13.sp, color = AtollColors.RunningText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (channel.activity.size > 2) Text("+${channel.activity.size - 2}", fontSize = 13.sp, color = AtollColors.Muted)
                }
                channel.runningElapsed.isNotEmpty() -> Text(channel.runningElapsed, fontSize = 13.sp, color = AtollColors.RunningText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                channel.access.label.isNotEmpty() -> Text(channel.access.label, fontSize = 13.sp, color = AtollColors.Muted, maxLines = 1)
                channel.description.isNotEmpty() -> Text(channel.description, fontSize = 13.sp, color = AtollColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (channel.updated.isNotEmpty()) Text(channel.updated, fontSize = 12.sp, color = AtollColors.Muted)
            unreadLabel(channel.unread, channel.unreadPhase).takeIf { it.isNotEmpty() }?.let { label ->
                if (channel.unreadPhase == UnreadPhase.Ready) CountBadge(label, strong = true) else Text(label, color = AtollColors.Muted, fontSize = 13.sp)
            }
            if (!member) TextChip("查看")
        }
    }
}

package dev.atoll.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.atoll.app.ui.components.*
import dev.atoll.app.ui.theme.AtollShapes
import dev.atoll.app.ui.theme.ChatColors
import dev.atoll.app.ui.screens.ChannelMenuAction.Item
import kotlinx.coroutines.launch

/** The conversation and the panels opened from 聊天信息; files and terminal share one section. */
private enum class ChannelSection(val label: String) { Conversation("动态"), Tasks("任务"), Files("文件"), Members("成员") }

/**
 * Conversation v2: one 48dp header (channel · current agent ⌄ · ···). The agent chips drop down from the title;
 * everything that used to sit in tabs or the filter bar lives on the 聊天信息 page.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChannelScreen(state: ConversationViewState, composer: ComposerViewState, tab: ChannelTab,
    onBack: () -> Unit, onTab: (ChannelTab) -> Unit, onScope: () -> Unit, onActor: (String) -> Unit,
    onText: (String) -> Unit, onSend: () -> Unit, onOlder: () -> Unit,
    onMenu: (ChannelMenuAction) -> Unit = {}, onLink: ((String) -> Unit)? = null, onChannelActivity: (Boolean) -> Unit = {},
    listState: LazyListState = rememberLazyListState(), onJumpToBottom: () -> Unit = {},
    contentOverride: (@Composable () -> Unit)? = null, composerVisible: Boolean = true,
    onAttachment: MessageAttachmentHandler? = null,
    messageContent: (@Composable (MessageViewState, @Composable () -> Unit) -> Unit)? = null,
    newerUnloaded: Boolean = false, connectionPhase: ConnectionPhase = ConnectionPhase.OPEN, onReconnect: () -> Unit = {},
    membersOpen: Boolean = false, memberCount: Int? = null, onRefreshMembers: (() -> Unit)? = null,
    membersRefreshing: Boolean = false, membersRefreshEnabled: Boolean = true,
    currentAgentId: String = "", onSelectAgent: (String) -> Unit = {}, onInfoOpenChange: (Boolean) -> Unit = {}) {
    val section = when {
        membersOpen -> ChannelSection.Members
        tab == ChannelTab.Tasks -> ChannelSection.Tasks
        state.filesOpen || state.terminalOpen -> ChannelSection.Files
        else -> ChannelSection.Conversation
    }
    fun select(target: ChannelSection) { when (target) {
        ChannelSection.Conversation -> {
            if (state.terminalOpen) onMenu(Item.CloseTerminal)
            if (state.filesOpen) onMenu(Item.CloseFiles)
            if (tab != ChannelTab.Conversation || membersOpen) onTab(ChannelTab.Conversation)
        }
        ChannelSection.Tasks -> onTab(ChannelTab.Tasks)
        ChannelSection.Files -> if (!state.filesOpen) onMenu(Item.OpenFiles)
        ChannelSection.Members -> onMenu(Item.Members)
    } }
    var chipsOpen by remember(state.channel.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val markerCount = when {
        state.hasOlder -> 1
        !state.loadingOlder && state.entries.isNotEmpty() && !state.historyStartLabel.isNullOrBlank() -> 1
        else -> 0
    }
    var infoOpen by rememberSaveable(state.channel.id) { mutableStateOf(false) }
    val reportInfo by rememberUpdatedState(onInfoOpenChange)
    LaunchedEffect(infoOpen) { reportInfo(infoOpen) }
    if (chipsOpen) BackHandler { chipsOpen = false }
    val current = state.agents.firstOrNull { it.id == currentAgentId } ?: state.agents.firstOrNull { it.name == composer.agent && composer.agent.isNotEmpty() }
    Box(Modifier.fillMaxSize().background(ChatColors.Ground).safeDrawingPadding().imePadding()) {
    Column(Modifier.fillMaxSize().then(if (infoOpen) Modifier.clearAndSetSemantics {} else Modifier)) {
        val connecting = connectionPhase == ConnectionPhase.CONNECTING || connectionPhase == ConnectionPhase.RECONNECTING
        if (section == ChannelSection.Conversation) ChatHeader(state, current, chipsOpen, connecting, onBack,
            onTitle = { chipsOpen = !chipsOpen }, onInfo = { chipsOpen = false; infoOpen = true })
        else SectionHeader(section.label, { select(ChannelSection.Conversation) },
            onRefresh = onRefreshMembers.takeIf { membersOpen }, refreshing = membersRefreshing, refreshEnabled = membersRefreshEnabled)
        if (connectionPhase == ConnectionPhase.RECONNECTING || connectionPhase == ConnectionPhase.CLOSED) ReconnectNotice(onReconnect)
        if (contentOverride != null) {
            Box(Modifier.weight(1f).fillMaxWidth()) { contentOverride() }
            if (composerVisible) Composer(composer, onText, onSend, onMenu)
        } else if (tab == ChannelTab.Conversation) {
            if (newerUnloaded) HistoryWindowNotice(onJumpToBottom)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp)) {
                    if (state.hasOlder) item("load-older") { Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                        TextButton(onOlder, Modifier.heightIn(min = 44.dp), enabled = !state.loadingOlder) {
                            if (state.loadingOlder) { ChatSpinner(); Spacer(Modifier.width(8.dp)) }
                            Text(if (state.loadingOlder) "正在加载历史…" else "加载更早消息", fontSize = 13.sp, color = ChatColors.Link)
                        }
                    } }
                    if (!state.hasOlder && !state.loadingOlder && state.entries.isNotEmpty()) {
                        state.historyStartLabel?.takeIf { it.isNotBlank() }?.let { label ->
                            item("history-start", contentType = "history-status") { TimelineHistoryStart(label) }
                        }
                    }
                    items(state.entries, key = { it.key }, contentType = { it::class.simpleName }) { entry -> when (entry) {
                        is TimelineEntry.Day -> DaySeparator(entry.text)
                        is TimelineEntry.Notice -> WeakNotice(entry.text)
                        is TimelineEntry.ChannelActivity -> ChannelActivity(entry)
                        is TimelineEntry.Filtered -> Text("${entry.count} 条与你无关的消息 · 显示", Modifier.fillMaxWidth().heightIn(min = 40.dp)
                            .clickable(onClickLabel = "显示全部", onClick = onScope).wrapContentHeight(), fontSize = 12.sp, color = ChatColors.Link, textAlign = TextAlign.Center)
                        is TimelineEntry.Message -> {
                            val default: @Composable () -> Unit = { MessageFrame(entry.value, onLink, onAttachment,
                                onRelatedCall = { onMenu(ChannelMenuAction.Process(it)) }, onQuote = { key ->
                                    val target = state.entries.indexOfFirst { it.key == key || (it as? TimelineEntry.Message)?.value?.id?.startsWith("$key:") == true }
                                    if (target >= 0) scope.launch { listState.animateScrollToItem(target + markerCount) }
                                }) {
                                onMenu(ChannelMenuAction.Process(entry.value.id)) } }
                            if (messageContent != null) messageContent(entry.value, default) else default()
                        }
                    } }
                    if (state.entries.isEmpty()) state.emptyKind?.let { kind ->
                        item("empty", contentType = "empty-feedback") {
                            TimelineEmptyFeedback(kind, state.emptySettled, Modifier.fillParentMaxHeight(.52f))
                        }
                    }
                }
                if (state.newMessages > 0 || listState.canScrollForward) {
                    Row(Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 12.dp).shadow(4.dp, CircleShape).clip(CircleShape)
                        .background(ChatColors.Block).clickable(onClick = onJumpToBottom).heightIn(min = 40.dp).padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Outlined.KeyboardDoubleArrowDown, null, Modifier.size(16.dp), tint = ChatColors.Gray)
                        Text(if (state.newMessages > 0) "${state.newMessages} 条新动态" else "回到最新", fontSize = 13.sp, color = ChatColors.Ink)
                    }
                }
                if (chipsOpen) AgentChips(state, current?.id, onClose = { chipsOpen = false },
                    onSelect = { id -> onSelectAgent(id); chipsOpen = false }, onFilter = onActor, onAdd = { chipsOpen = false; onMenu(Item.Members) })
            }
            if (state.waitingCount > 0) Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).background(ChatColors.Bar).clickable { onMenu(Item.Waiting) }
                .padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${state.waitingCount} 条等待", fontSize = 13.sp, color = ChatColors.Ink)
                Text("· 本轮结束后依次发出", Modifier.weight(1f), fontSize = 13.sp, color = ChatColors.Gray, maxLines = 1)
                Text("›", fontSize = 13.sp, color = ChatColors.Gray)
            }
            if (composerVisible) Composer(composer, onText, onSend, onMenu)
        } else TasksScreen(state.tasks, Modifier.weight(1f)) { onMenu(it) }
    }
    if (infoOpen) ChannelInfoPage(state, newerUnloaded, onClose = { infoOpen = false },
        onAction = { action -> infoOpen = false; onMenu(action) }, onTasks = { infoOpen = false; select(ChannelSection.Tasks) },
        onScope = onScope, onChannelActivity = onChannelActivity, onLatest = { infoOpen = false; onJumpToBottom() })
    }
}

private val chatTitle = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium, color = ChatColors.Ink)

@Composable
private fun ChatHeader(state: ConversationViewState, current: AgentViewState?, expanded: Boolean, connecting: Boolean,
    onBack: () -> Unit, onTitle: () -> Unit, onInfo: () -> Unit) {
    val filtered = state.actorFilters.mapNotNull { id -> state.agents.firstOrNull { it.id == id }?.name }
    Row(Modifier.fillMaxWidth().height(48.dp).background(ChatColors.Ground).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onBack, Modifier.size(44.dp)) { Icon(Icons.Outlined.ChevronLeft, "返回频道列表", Modifier.size(26.dp), tint = ChatColors.Ink) }
        Row(Modifier.weight(1f).fillMaxHeight().clickable(onClickLabel = if (expanded) "收起 Agent 成员" else "展开 Agent 成员", onClick = onTitle)
            .semantics { contentDescription = "${state.channel.name}，${state.agents.size} 个 Agent" + (current?.let { "，正在和 ${it.name} 对话" } ?: "") },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)) {
            Text(state.channel.name, Modifier.weight(1f, fill = false), style = chatTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            when {
                connecting -> { Text("·", style = chatTitle, color = ChatColors.Gray); ChatSpinner(12.dp); Text("连接中", style = chatTitle, color = ChatColors.Gray) }
                filtered.isNotEmpty() -> Text("· 只看 ${filtered.joinToString("、")}", Modifier.weight(1f, fill = false), style = chatTitle,
                    color = ChatColors.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                current != null -> {
                    Text("·", style = chatTitle, color = ChatColors.Gray)
                    if (current.active) ChatRunningDot(Modifier.padding(start = 2.dp))
                    Text(current.name, Modifier.weight(1f, fill = false), style = chatTitle, color = ChatColors.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (state.agents.isNotEmpty()) Icon(if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown, null,
                Modifier.size(16.dp), tint = ChatColors.Gray)
        }
        Box(Modifier.size(44.dp).clickable(onClickLabel = "聊天信息", onClick = onInfo)
            .semantics { contentDescription = if (state.tasks.isEmpty()) "聊天信息" else "聊天信息，${state.tasks.size} 个待处理任务" },
            contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.MoreHoriz, null, Modifier.size(24.dp), tint = ChatColors.Ink)
            if (state.tasks.isNotEmpty()) Box(Modifier.align(Alignment.TopEnd).padding(top = 11.dp, end = 9.dp).size(7.dp).clip(CircleShape).background(ChatColors.Red))
        }
    }
    HorizontalDivider(color = ChatColors.Line)
}

@Composable
private fun SectionHeader(title: String, onBack: () -> Unit, onRefresh: (() -> Unit)? = null, refreshing: Boolean = false, refreshEnabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().height(48.dp).background(ChatColors.Ground).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onBack, Modifier.size(44.dp)) { Icon(Icons.Outlined.ChevronLeft, "返回对话", Modifier.size(26.dp), tint = ChatColors.Ink) }
        Text(title, Modifier.weight(1f), style = chatTitle, textAlign = TextAlign.Center)
        if (onRefresh != null) IconButton(onRefresh, Modifier.size(44.dp).semantics { contentDescription = "刷新名册" }, enabled = refreshEnabled) {
            if (refreshing) ChatSpinner(16.dp) else Icon(Icons.Outlined.Refresh, null, Modifier.size(20.dp), tint = ChatColors.Ink)
        } else Spacer(Modifier.size(44.dp))
    }
    HorizontalDivider(color = ChatColors.Line)
}

/** Filters board: tap a chip to talk to that agent, long-press to show only its messages. */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun BoxScope.AgentChips(state: ConversationViewState, currentId: String?, onClose: () -> Unit,
    onSelect: (String) -> Unit, onFilter: (String) -> Unit, onAdd: () -> Unit) {
    Box(Modifier.matchParentSize().background(ChatColors.Scrim).clickable(remember { MutableInteractionSource() }, null, onClickLabel = "收起", onClick = onClose))
    Column(Modifier.fillMaxWidth().background(ChatColors.Block).padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            state.agents.forEach { agent -> key(agent.id) {
                val chosen = agent.id == currentId
                val only = agent.id in state.actorFilters
                Box(Modifier.heightIn(min = 44.dp), contentAlignment = Alignment.Center) {
                    Row(Modifier.height(32.dp).clip(CircleShape).background(ChatColors.Secondary)
                        .then(if (chosen) Modifier.border(1.dp, ChatColors.Ink, CircleShape) else Modifier)
                        .combinedClickable(onClickLabel = "和 ${agent.name} 对话", onLongClickLabel = if (only) "取消只看" else "只看 ${agent.name}",
                            onLongClick = { onFilter(agent.id) }) { onSelect(agent.id) }
                        .semantics { selected = chosen }.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (agent.active) ChatRunningDot()
                        Text(agent.name, fontSize = 14.sp, fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal, color = ChatColors.Ink, maxLines = 1)
                        if (only) Text("只看", fontSize = 12.sp, color = ChatColors.Gray)
                    }
                }
            } }
            (state.actorFilters - state.agents.map { it.id }.toSet()).forEach { staleId -> key(staleId) {
                Box(Modifier.heightIn(min = 44.dp), contentAlignment = Alignment.Center) {
                    Text("已失效 · $staleId · 只看", Modifier.height(32.dp).clip(CircleShape).background(ChatColors.Secondary)
                        .clickable(onClickLabel = "取消只看") { onFilter(staleId) }.padding(horizontal = 12.dp).wrapContentHeight(), fontSize = 14.sp, color = ChatColors.Gray)
                }
            } }
            Box(Modifier.heightIn(min = 44.dp), contentAlignment = Alignment.Center) {
                Text("+ 添加", Modifier.height(32.dp).clip(CircleShape).border(1.dp, ChatColors.Line, CircleShape).clickable(onClick = onAdd)
                    .padding(horizontal = 12.dp).wrapContentHeight(), fontSize = 14.sp, color = ChatColors.Gray)
            }
        }
        Text("绿点 = 运行中 · 点选切换对话 · 长按只看 TA 的消息", Modifier.padding(top = 4.dp), fontSize = 12.sp, color = ChatColors.Gray)
    }
}

private enum class InfoPage { Main, Display, Advanced }

/** ChannelDetails board (WeChat 聊天信息): members, the channel's panels, display settings and advanced tools. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChannelInfoPage(state: ConversationViewState, newerUnloaded: Boolean, onClose: () -> Unit,
    onAction: (ChannelMenuAction) -> Unit, onTasks: () -> Unit, onScope: () -> Unit, onChannelActivity: (Boolean) -> Unit, onLatest: () -> Unit) {
    var page by rememberSaveable { mutableStateOf(InfoPage.Main) }
    BackHandler { if (page == InfoPage.Main) onClose() else page = InfoPage.Main }
    Column(Modifier.fillMaxSize().background(ChatColors.Ground).clickable(remember { MutableInteractionSource() }, null) {}) {
        SectionHeaderBar(when (page) { InfoPage.Main -> "聊天信息"; InfoPage.Display -> "消息显示"; InfoPage.Advanced -> "设备与高级" }) {
            if (page == InfoPage.Main) onClose() else page = InfoPage.Main
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            when (page) {
                InfoPage.Main -> {
                    FlowRow(Modifier.fillMaxWidth().background(ChatColors.Block).padding(horizontal = 10.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.agents.forEach { agent -> key(agent.id) {
                            MemberCell(agent.name, { ActorAvatar(agent.name, agent.id, agent = true, size = 48.dp) }) { onAction(Item.Members) }
                        } }
                        MemberCell("添加", { Box(Modifier.size(48.dp).border(1.dp, ChatColors.Line, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Add, null, Modifier.size(24.dp), tint = ChatColors.Hint)
                        } }) { onAction(Item.Members) }
                    }
                    InfoGroup {
                        InfoRow("频道名称", state.channel.description.takeIf(String::isNotBlank), state.channel.name) { onAction(Item.Details) }
                        InfoRow("新建子频道", chevron = false, trailingLink = "新建") { onAction(Item.NewChild) }
                    }
                    InfoGroup {
                        InfoRow("任务", trailing = if (state.tasks.isEmpty()) null else "${state.tasks.size} 个待处理", dot = state.tasks.isNotEmpty(), onClick = onTasks)
                        InfoRow("文件") { onAction(Item.OpenFiles) }
                        InfoRow("终端") { onAction(if (state.terminalOpen) Item.CloseTerminal else Item.OpenTerminal) }
                        InfoRow("星标消息") { onAction(Item.Stars) }
                        InfoRow("定时动作") { onAction(Item.Timers) }
                    }
                    InfoGroup {
                        InfoRow("消息显示", "频道活动 ${if (state.showChannelActivity) "显示" else "隐藏"} · 历史 ${if (newerUnloaded) "较早" else "最新"}",
                            if (state.mineOnly) "与我相关" else "全部") { page = InfoPage.Display }
                        InfoRow("设备与高级", "后台任务 · 资源工具 · 性能记录", if (state.runningCount > 0) "${state.runningCount} 个后台任务" else null) { page = InfoPage.Advanced }
                    }
                }
                InfoPage.Display -> {
                    InfoLabel("显示范围")
                    InfoGroup {
                        InfoChoice("与我相关", "只显示发给我、我发出和需要我处理的消息", state.mineOnly) { if (!state.mineOnly) onScope() }
                        InfoChoice("全部", "频道里所有成员的往来", !state.mineOnly) { if (state.mineOnly) onScope() }
                    }
                    InfoGroup {
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(state.showChannelActivity, role = Role.Switch, onValueChange = onChannelActivity)
                            .semantics { contentDescription = "频道活动" }.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("频道活动", fontSize = 16.sp, color = ChatColors.Ink)
                                Text("成员加入、构建完成等系统动态", fontSize = 13.sp, color = ChatColors.Gray)
                            }
                            ChatSwitch(state.showChannelActivity)
                        }
                    }
                    InfoGroup {
                        if (newerUnloaded) InfoRow("历史窗口", "正在看较早的记录", chevron = false, trailingLink = "回到最新", onClick = onLatest)
                        else InfoRow("历史窗口", "正在看最新的消息", chevron = false, onClick = null)
                    }
                }
                InfoPage.Advanced -> InfoGroup {
                    InfoRow("后台任务", trailing = state.runningCount.takeIf { it > 0 }?.toString()) { onAction(Item.Running) }
                    InfoRow("最近阅读") { onAction(Item.Recent) }
                    InfoRow("高级资源工具") { onAction(Item.Resources) }
                    InfoRow("导出性能记录") { onAction(Item.ExportPerformance) }
                    InfoRow("频道管理", "成员、设置与退役") { onAction(Item.Details) }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionHeaderBar(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onBack, Modifier.size(44.dp)) { Icon(Icons.Outlined.ChevronLeft, "返回", Modifier.size(26.dp), tint = ChatColors.Ink) }
        Text(title, Modifier.weight(1f), style = chatTitle, textAlign = TextAlign.Center)
        Spacer(Modifier.size(44.dp))
    }
}

@Composable
private fun MemberCell(name: String, avatar: @Composable () -> Unit, onClick: () -> Unit) {
    Column(Modifier.width(64.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        avatar()
        Text(name, fontSize = 12.sp, color = ChatColors.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun InfoGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(top = 8.dp).fillMaxWidth().background(ChatColors.Block), content = content)
}

@Composable
private fun InfoLabel(text: String) = Text(text, Modifier.padding(start = 16.dp, top = 16.dp, bottom = 0.dp), fontSize = 13.sp, color = ChatColors.Gray)

@Composable
private fun InfoRow(title: String, subtitle: String? = null, trailing: String? = null, dot: Boolean = false, chevron: Boolean = true,
    trailingLink: String? = null, onClick: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(title, fontSize = 16.sp, color = ChatColors.Ink)
            subtitle?.let { Text(it, fontSize = 13.sp, color = ChatColors.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        trailing?.let { Text(it, Modifier.widthIn(max = 180.dp), fontSize = 15.sp, color = ChatColors.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        if (dot) Box(Modifier.size(7.dp).clip(CircleShape).background(ChatColors.Red))
        trailingLink?.let { Text(it, fontSize = 15.sp, color = ChatColors.Link) }
        if (chevron) Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = ChatColors.Hint)
    }
    HorizontalDivider(Modifier.padding(start = 16.dp), color = ChatColors.LineInWhite)
}

@Composable
private fun InfoChoice(title: String, subtitle: String, chosen: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(chosen, role = Role.RadioButton, onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(title, fontSize = 16.sp, color = ChatColors.Ink)
            Text(subtitle, fontSize = 13.sp, color = ChatColors.Gray)
        }
        if (chosen) Icon(Icons.Outlined.Check, null, Modifier.size(20.dp), tint = ChatColors.Green)
    }
    HorizontalDivider(Modifier.padding(start = 16.dp), color = ChatColors.LineInWhite)
}

@Composable
internal fun ChatSwitch(on: Boolean) {
    Box(Modifier.size(50.dp, 30.dp).clip(CircleShape).background(if (on) ChatColors.Green else ChatColors.Line)) {
        Box(Modifier.align(if (on) Alignment.CenterEnd else Alignment.CenterStart).padding(2.dp).size(26.dp).shadow(1.dp, CircleShape)
            .clip(CircleShape).background(Color.White))
    }
}

@Composable
fun Composer(state: ComposerViewState, onText: (String) -> Unit, onSend: () -> Unit, onAction: (ChannelMenuAction) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val canSend = state.text.isNotBlank() && !state.sending
    Column(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 10.dp).clip(AtollShapes.Field).background(ChatColors.Block)
        .border(if (focused) 1.5.dp else 1.dp, if (focused) ChatColors.Ink else ChatColors.Line, AtollShapes.Field)) {
        BasicTextField(state.text, onText, Modifier.fillMaxWidth().heightIn(min = 44.dp, max = 160.dp).onFocusChanged { focused = it.isFocused }
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp), textStyle = TextStyle(color = ChatColors.Ink, fontSize = 16.sp, lineHeight = 23.sp),
            decorationBox = { inner -> if (state.text.isEmpty()) Text("发消息，@ 指定成员，/ 使用命令", color = ChatColors.Gray, fontSize = 16.sp); inner() })
        Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.heightIn(min = 36.dp).clip(AtollShapes.Pill).background(ChatColors.Bar).clickable { onAction(Item.SelectAgent) }
                .padding(start = 10.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(listOf(state.agent, state.model, state.effort).filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { "选择 Agent" },
                    Modifier.widthIn(max = 180.dp), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Outlined.KeyboardArrowDown, null, Modifier.size(16.dp), tint = ChatColors.Gray)
            }
            Spacer(Modifier.weight(1f))
            IconButton({ onAction(Item.Attachments) }, Modifier.size(44.dp)) { Icon(Icons.Outlined.AttachFile, "添加附件", tint = ChatColors.Gray) }
            IconButton({ onAction(Item.ComposerMore) }, Modifier.size(44.dp)) { Icon(Icons.Outlined.Add, "更多操作", tint = ChatColors.Gray) }
            IconButton(onSend, Modifier.size(40.dp).clip(CircleShape).background(if (canSend) ChatColors.Ink else ChatColors.Bar), enabled = canSend) {
                Icon(Icons.Outlined.ArrowUpward, "发送", tint = if (canSend) Color.White else ChatColors.Hint)
            }
        }
    }
}

@Composable
fun TasksScreen(tasks: List<TaskViewState>, modifier: Modifier = Modifier, onAction: (ChannelMenuAction) -> Unit) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SegmentedControl(listOf("与我相关", "全部"), 0, { index -> onAction(if (index == 0) Item.Mine else Item.All) })
            Spacer(Modifier.weight(1f))
            TextChip("待处理", onClick = { onAction(Item.StatusFilter) })
            TextChip("全部类型", onClick = { onAction(Item.TypeFilter) })
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item("label") { SectionLabel("工作项", trailing = tasks.size.toString()) }
            items(tasks, key = { it.id }) { task ->
                Column(Modifier.fillMaxWidth().clip(AtollShapes.Card).background(ChatColors.Block).border(1.dp, ChatColors.LineInWhite, AtollShapes.Card)
                    .clickable { onAction(ChannelMenuAction.Task(task.id)) }.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusPill(task.kind, Tone.Waiting)
                    Text(task.title, style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(task.assignee, color = ChatColors.Gray, fontSize = 13.sp)
                        Text("${task.state} ›", color = ChatColors.Gray, fontSize = 13.sp)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AtollButton("安排自动动作", { onAction(Item.Schedule) }, Modifier.weight(1f), kind = ButtonKind.Secondary)
            AtollButton("新建任务", { onAction(Item.NewTask) }, Modifier.weight(1f))
        }
    }
}

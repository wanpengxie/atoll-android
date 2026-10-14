package dev.atoll.app.ui.screens

import androidx.compose.foundation.*
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
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
import dev.atoll.app.ui.screens.ChannelMenuAction.Item

/** The four channel views; files and terminal share the 文件 tab. */
private enum class ChannelSection(val label: String) { Conversation("动态"), Tasks("任务"), Files("文件"), Members("成员") }

@Composable
fun ChannelScreen(state: ConversationViewState, composer: ComposerViewState, tab: ChannelTab,
    onBack: () -> Unit, onTab: (ChannelTab) -> Unit, onScope: () -> Unit, onActor: (String) -> Unit,
    onText: (String) -> Unit, onSend: () -> Unit, onOlder: () -> Unit,
    onMenu: (ChannelMenuAction) -> Unit = {}, onLink: ((String) -> Unit)? = null, onChannelActivity: (Boolean) -> Unit = {},
    listState: LazyListState = rememberLazyListState(), onJumpToBottom: () -> Unit = {},
    contentOverride: (@Composable () -> Unit)? = null, composerVisible: Boolean = true,
    onAttachment: MessageAttachmentHandler? = null,
    messageContent: (@Composable (MessageViewState, @Composable () -> Unit) -> Unit)? = null,
    newerUnloaded: Boolean = false, connectionPhase: ConnectionPhase = ConnectionPhase.OPEN, onReconnect: () -> Unit = {}) {
    val section = when {
        tab == ChannelTab.Tasks -> ChannelSection.Tasks
        state.filesOpen || state.terminalOpen -> ChannelSection.Files
        else -> ChannelSection.Conversation
    }
    fun select(target: ChannelSection) { when (target) {
        ChannelSection.Conversation -> {
            if (state.terminalOpen) onMenu(Item.CloseTerminal)
            if (state.filesOpen) onMenu(Item.CloseFiles)
            if (tab != ChannelTab.Conversation) onTab(ChannelTab.Conversation)
        }
        ChannelSection.Tasks -> onTab(ChannelTab.Tasks)
        ChannelSection.Files -> if (!state.filesOpen) onMenu(Item.OpenFiles)
        ChannelSection.Members -> onMenu(Item.Members)
    } }
    val expandedTurns = remember(state.channel.id) { mutableStateMapOf<String, Boolean>() }
    LaunchedEffect(state.entries) {
        if (expandedTurns.isNotEmpty()) {
            val retained = state.entries.mapNotNull { (it as? TimelineEntry.Message)?.value?.id }.toSet()
            expandedTurns.keys.retainAll(retained)
        }
    }
    val markerCount = when {
        state.hasOlder -> 1
        !state.loadingOlder && state.entries.isNotEmpty() && !state.historyStartLabel.isNullOrBlank() -> 1
        else -> 0
    }
    val headerPixels = with(LocalDensity.current) { 44.dp.roundToPx() }
    val sticky by remember(state.channel.id, listState, state.entries, markerCount, headerPixels) {
        derivedStateOf {
            // No layout/scroll dependency at all while every strip is closed.
            if (expandedTurns.isEmpty()) null else {
                val layout = listState.layoutInfo
                val top = layout.visibleItemsInfo.firstOrNull()
                val index = (top?.index ?: -1) - markerCount
                if (top == null) null else stickyTurnHeader(state.entries.getOrNull(index), state.entries.getOrNull(index - 1),
                    expandedTurns.keys, top.offset, top.size, layout.viewportStartOffset, layout.viewportEndOffset, headerPixels)
            }
        }
    }
    Column(Modifier.fillMaxSize().background(AtollColors.Ground).safeDrawingPadding().imePadding()) {
        ChannelHeader(state.copy(connection = when (connectionPhase) {
            ConnectionPhase.CONNECTING -> "连接中"
            ConnectionPhase.RECONNECTING -> "重连中"
            ConnectionPhase.CLOSED -> "已断开"
            ConnectionPhase.OPEN -> state.connection
        }), onBack, onMenu, connecting = connectionPhase == ConnectionPhase.CONNECTING || connectionPhase == ConnectionPhase.RECONNECTING)
        if (connectionPhase == ConnectionPhase.RECONNECTING || connectionPhase == ConnectionPhase.CLOSED) ReconnectNotice(onReconnect)
        ChannelTabs(section, state) { select(it) }
        if (contentOverride != null) {
            Box(Modifier.weight(1f).fillMaxWidth()) { contentOverride() }
            if (composerVisible) Composer(composer, onText, onSend, onMenu)
        } else if (tab == ChannelTab.Conversation) {
            FilterBar(state, onScope, onActor, onChannelActivity)
            if (newerUnloaded) HistoryWindowNotice(onJumpToBottom)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 12.dp)) {
                    if (state.hasOlder) item("load-older") { Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                        TextButton(onOlder, Modifier.heightIn(min = 44.dp), enabled = !state.loadingOlder) {
                            if (state.loadingOlder) { Spinner(12.dp, AtollColors.Muted, AtollColors.Line); Spacer(Modifier.width(8.dp)) }
                            Text(if (state.loadingOlder) "正在加载历史…" else "加载更早消息", fontSize = 13.sp, color = AtollColors.Body)
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
                        is TimelineEntry.Message -> {
                            val default: @Composable () -> Unit = { MessageFrame(entry.value, onLink, onAttachment,
                                onRelatedCall = { onMenu(ChannelMenuAction.Process(it)) },
                                onWorkExpanded = { open -> if (open) expandedTurns[entry.value.id] = true else expandedTurns.remove(entry.value.id) }) {
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
                sticky?.let { pin -> StickyTurnHeader(pin, { onMenu(ChannelMenuAction.Process(pin.message.id)) }, Modifier.align(Alignment.TopCenter)) }
                if (state.newMessages > 0 || listState.canScrollForward) {
                    Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp).shadow(6.dp, CircleShape).clip(CircleShape)
                        .background(AtollColors.Ink).clickable(onClick = onJumpToBottom).heightIn(min = 40.dp).padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Outlined.ArrowDownward, null, Modifier.size(16.dp), tint = Color.White)
                        Text(if (state.newMessages > 0) "${state.newMessages} 条新动态" else "回到最新", fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold, color = Color.White)
                    }
                }
            }
            if (state.waitingCount > 0) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                .background(AtollColors.WarningSurface).clickable { onMenu(Item.Waiting) }.heightIn(min = 44.dp).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.Schedule, null, Modifier.size(17.dp), tint = AtollColors.Warning)
                Text("${state.waitingCount} 条等待消息", Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text("展开", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = AtollColors.Warning)
            }
            if (composerVisible) Composer(composer, onText, onSend, onMenu)
        } else TasksScreen(state.tasks, Modifier.weight(1f)) { onMenu(it) }
    }
}

@Composable
private fun ChannelHeader(state: ConversationViewState, onBack: () -> Unit, onMenu: (ChannelMenuAction) -> Unit, connecting: Boolean = false) {
    var menuOpen by remember(state.channel.id) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().background(AtollColors.Surface).heightIn(min = 56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        IconButton(onBack, Modifier.size(44.dp)) { Icon(Icons.Outlined.ChevronLeft, "返回频道列表", Modifier.size(24.dp)) }
        Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable { onMenu(Item.Details) }.padding(horizontal = 4.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("# ", fontSize = 17.sp, color = AtollColors.Faint)
                Text(state.channel.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val running = state.agents.count { it.active }
            val connected = state.connection == "已连接"
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (connecting) Spinner(10.dp, AtollColors.Warning, AtollColors.WarningLine)
                else PulseDot(if (connected) AtollColors.Lagoon else AtollColors.Warning, pulsing = !connected)
                Text(when {
                    !connected -> state.connection
                    state.agents.isEmpty() -> "已连接"
                    running > 0 -> "${state.agents.size} 个 Agent · $running 个运行中"
                    else -> "${state.agents.size} 个 Agent"
                }, fontSize = 12.sp, color = if (connected) AtollColors.Muted else AtollColors.Warning, maxLines = 1)
            }
        }
        Box {
            IconButton({ menuOpen = true }, Modifier.size(44.dp)) { Icon(Icons.Outlined.MoreHoriz, "频道菜单", Modifier.size(22.dp)) }
            DropdownMenu(menuOpen, { menuOpen = false }) {
                val choices = listOf(Item.Details, Item.Stars, Item.Recent, Item.Running, Item.Timers,
                    if (state.terminalOpen) Item.CloseTerminal else Item.OpenTerminal, Item.Resources, Item.NewChild, Item.ExportPerformance, Item.Restart)
                choices.forEach { action -> DropdownMenuItem(
                    text = { Text(if (action == Item.Running) "${action.label}（${state.runningCount}）" else action.label, fontSize = 15.sp) },
                    enabled = action != Item.Restart, onClick = { menuOpen = false; onMenu(action) }) }
            }
        }
    }
}

@Composable
private fun ChannelTabs(section: ChannelSection, state: ConversationViewState, onSelect: (ChannelSection) -> Unit) {
    Row(Modifier.fillMaxWidth().background(AtollColors.Surface).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
        ChannelSection.entries.forEach { choice ->
            val active = choice == section
            Column(Modifier.width(IntrinsicSize.Max).heightIn(min = 44.dp).semantics { selected = active }.clickable(role = Role.Tab) { onSelect(choice) },
                horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.heightIn(min = 42.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(choice.label, fontSize = 15.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (active) AtollColors.Ink else AtollColors.Muted)
                    if (choice == ChannelSection.Tasks && state.tasks.isNotEmpty()) CountBadge(state.tasks.size.toString(), Tone.Waiting)
                    if (choice == ChannelSection.Members && state.agents.isNotEmpty()) Text(state.agents.size.toString(), fontSize = 12.sp, color = AtollColors.Faint)
                }
                Box(Modifier.height(2.dp).fillMaxWidth().background(if (active) AtollColors.Ink else Color.Transparent))
            }
        }
    }
    HorizontalDivider(color = AtollColors.Line)
}

/** Scope and actor filters sit above the list instead of floating over it. */
@Composable
private fun FilterBar(state: ConversationViewState, onScope: () -> Unit, onActor: (String) -> Unit, onChannelActivity: (Boolean) -> Unit) {
    if (!state.identityReady) return
    if (!state.mineOnly) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            SegmentedControl(listOf("与我相关", "全部"), 1, { if (it == 0) onScope() })
            Spacer(Modifier.weight(1f))
            Row(Modifier.heightIn(min = 44.dp).toggleable(state.showChannelActivity, role = Role.Switch, onValueChange = onChannelActivity)
                .semantics { contentDescription = "频道活动" }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("频道活动", fontSize = 13.sp, color = AtollColors.Body)
                Box(Modifier.size(40.dp, 24.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (state.showChannelActivity) AtollColors.Lagoon else AtollColors.ControlLine)) {
                    Box(Modifier.align(if (state.showChannelActivity) Alignment.CenterEnd else Alignment.CenterStart)
                        .padding(3.dp).size(18.dp).clip(CircleShape).background(Color.White))
                }
            }
        }
        return
    }
    ChipRow(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 6.dp)) {
        SegmentedControl(listOf("与我相关", "全部"), if (state.mineOnly) 0 else 1, { index -> if ((index == 0) != state.mineOnly) onScope() })
        if (state.mineOnly && (state.agents.isNotEmpty() || state.actorFilters.isNotEmpty())) Box(Modifier.size(1.dp, 20.dp).background(AtollColors.ControlLine))
        if (state.mineOnly) state.agents.forEach { agent ->
            ActorChip(agent.name, agent.id, agent.id in state.actorFilters, agent.active) { onActor(agent.id) }
        }
        if (state.mineOnly) (state.actorFilters - state.agents.map { it.id }.toSet()).forEach { staleId ->
            TextChip("已失效 · $staleId", selected = false, tone = Tone.Neutral, onClick = { onActor(staleId) })
        }
    }
}

@Composable
fun Composer(state: ComposerViewState, onText: (String) -> Unit, onSend: () -> Unit, onAction: (ChannelMenuAction) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val canSend = state.text.isNotBlank() && !state.sending
    Column(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 10.dp).clip(AtollShapes.Field).background(AtollColors.Surface)
        .border(if (focused) 1.5.dp else 1.dp, if (focused) AtollColors.Ink else AtollColors.ControlLine, AtollShapes.Field)) {
        BasicTextField(state.text, onText, Modifier.fillMaxWidth().heightIn(min = 44.dp, max = 160.dp).onFocusChanged { focused = it.isFocused }
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp), textStyle = TextStyle(color = AtollColors.Ink, fontSize = 16.sp, lineHeight = 23.sp),
            decorationBox = { inner -> if (state.text.isEmpty()) Text("发消息，@ 指定成员，/ 使用命令", color = AtollColors.Muted, fontSize = 16.sp); inner() })
        Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.heightIn(min = 36.dp).clip(AtollShapes.Pill).background(AtollColors.Subtle).clickable { onAction(Item.SelectAgent) }
                .padding(start = 10.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(listOf(state.agent, state.model, state.effort).filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { "选择 Agent" },
                    Modifier.widthIn(max = 180.dp), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Outlined.KeyboardArrowDown, null, Modifier.size(16.dp), tint = AtollColors.Muted)
            }
            Spacer(Modifier.weight(1f))
            IconButton({ onAction(Item.Attachments) }, Modifier.size(44.dp)) { Icon(Icons.Outlined.AttachFile, "添加附件", tint = AtollColors.Muted) }
            IconButton({ onAction(Item.ComposerMore) }, Modifier.size(44.dp)) { Icon(Icons.Outlined.Add, "更多操作", tint = AtollColors.Muted) }
            IconButton(onSend, Modifier.size(40.dp).clip(CircleShape).background(if (canSend) AtollColors.Ink else AtollColors.Sunken), enabled = canSend) {
                Icon(Icons.Outlined.ArrowUpward, "发送", tint = if (canSend) Color.White else AtollColors.Faint)
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
                Column(Modifier.fillMaxWidth().clip(AtollShapes.Card).background(AtollColors.Surface).border(1.dp, AtollColors.Line, AtollShapes.Card)
                    .clickable { onAction(ChannelMenuAction.Task(task.id)) }.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusPill(task.kind, Tone.Waiting)
                    Text(task.title, style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(task.assignee, color = AtollColors.Muted, fontSize = 13.sp)
                        Text("${task.state} ›", color = AtollColors.Muted, fontSize = 13.sp)
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

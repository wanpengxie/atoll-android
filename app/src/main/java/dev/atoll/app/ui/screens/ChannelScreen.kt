package dev.atoll.app.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.atoll.app.ui.components.*
import dev.atoll.app.ui.theme.AtollColors

@Composable
fun ChannelScreen(state: ConversationViewState, composer: ComposerViewState, tab: ChannelTab,
    onBack: () -> Unit, onTab: (ChannelTab) -> Unit, onScope: () -> Unit, onActor: (String) -> Unit,
    onText: (String) -> Unit, onSend: () -> Unit, onOlder: () -> Unit,
    onMenu: (String) -> Unit = {}, onLink: ((String) -> Unit)? = null,
    listState: LazyListState = rememberLazyListState(), onJumpToBottom: () -> Unit = {},
    contentOverride: (@Composable () -> Unit)? = null, composerVisible: Boolean = true) {
    var menuOpen by remember(state.channel.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(AtollColors.Workspace).safeDrawingPadding().imePadding()) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(44.dp)) {
            val titleWidth = (maxWidth / 2 - 90.dp).coerceAtLeast(0.dp)
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onBack, Modifier.size(44.dp)) { Text("‹", fontSize = 24.sp, color = AtollColors.Text) }
                Text(state.channel.name, Modifier.widthIn(max = titleWidth), fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.weight(1f))
                TextButton({ onMenu("成员") }, Modifier.size(44.dp), contentPadding = PaddingValues(0.dp)) { Text("成员", fontSize = 11.sp, color = AtollColors.Muted) }
                Box {
                    IconButton({ menuOpen = true }, Modifier.size(44.dp)) { Icon(Icons.Outlined.MoreHoriz, "频道菜单", Modifier.size(20.dp)) }
                    DropdownMenu(menuOpen, { menuOpen = false }) {
                        val choices = listOf("频道详情", "定时动作", "高级资源工具", "新建子频道", "导出性能记录", "重启频道",
                            if (state.filesOpen) "关闭文件" else "打开文件", "最近阅读", "星标消息", "后台任务(${state.runningCount})",
                            if (state.terminalOpen) "关闭终端" else "打开终端")
                        choices.forEach { label -> DropdownMenuItem(text = { Text(label, fontSize = 13.sp) },
                            enabled = label != "重启频道", onClick = { menuOpen = false; onMenu(label) }) }
                    }
                }
            }
            Row(Modifier.align(Alignment.Center)) {
                ChannelTab.entries.forEach { choice ->
                    Column(Modifier.width(44.dp).fillMaxHeight().clickable { onTab(choice) }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            Text(if (choice == ChannelTab.Conversation) "动态" else "任务", fontSize = 11.sp,
                                fontWeight = if (tab == choice) FontWeight.Bold else FontWeight.Normal,
                                color = if (tab == choice) AtollColors.Text else AtollColors.Muted)
                        }
                        Box(Modifier.width(24.dp).height(2.dp).background(if (tab == choice) AtollColors.Text else AtollColors.Workspace))
                    }
                }
            }
        }
        HorizontalDivider(color = AtollColors.Line)
        if (contentOverride != null) {
            Box(Modifier.weight(1f).fillMaxWidth()) { contentOverride() }
            if (composerVisible) Composer(composer, onText, onSend, onMenu)
        } else if (tab == ChannelTab.Conversation) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(start = 3.dp, end = 3.dp, top = 52.dp, bottom = 6.dp)) {
                if (state.hasOlder) item("load-older") { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TextButton(onOlder, enabled = !state.loadingOlder) { Text(if (state.loadingOlder) "正在加载历史…" else "加载更早消息", fontSize = 11.sp) }
                } }
                items(state.entries, key = { it.key }, contentType = { it::class.simpleName }) { entry -> when (entry) {
                    is TimelineEntry.Day -> DaySeparator(entry.text)
                    is TimelineEntry.Notice -> WeakNotice(entry.text)
                    is TimelineEntry.Message -> MessageFrame(entry.value, onLink) { onMenu("过程") }
                } }
                if (state.entries.isEmpty()) item("empty") { Column(Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("开始一段对话", style = MaterialTheme.typography.titleMedium)
                    Text("发送消息，与频道成员一起工作。", color = AtollColors.Muted, style = MaterialTheme.typography.bodySmall)
                } }
            }
            if (state.newMessages > 0 || listState.canScrollForward) {
                Surface(Modifier.align(Alignment.BottomCenter).padding(8.dp), color = AtollColors.Workspace,
                    shape = CircleShape, shadowElevation = 4.dp, border = BorderStroke(1.dp, AtollColors.ControlLine)) {
                    TextButton(onJumpToBottom) {
                        Text(if (state.newMessages > 0) "↓ ${state.newMessages} 条新动态" else "↓", fontSize = 12.sp, color = AtollColors.Text)
                    }
                }
            }
            FloatingFilters(state, onScope, onActor, Modifier.align(Alignment.TopEnd).padding(8.dp))
            }
            if (state.waitingCount > 0) Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).background(AtollColors.MutedSurface, RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${state.waitingCount} 条等待消息", Modifier.weight(1f), fontSize = 12.sp)
                TextButton({ onMenu("等待区") }) { Text("展开", fontSize = 12.sp) }
            }
            if (composerVisible) Composer(composer, onText, onSend, onMenu)
        } else TasksScreen(state.tasks, Modifier.weight(1f)) { onMenu(it) }
    }
}
@Composable
fun Composer(state: ComposerViewState, onText: (String) -> Unit, onSend: () -> Unit, onAction: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), color = AtollColors.Input,
        shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, AtollColors.ControlLine), shadowElevation = 3.dp) {
        Column(Modifier.padding(6.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                BasicTextField(state.text, onText, Modifier.weight(1f).heightIn(min = 44.dp, max = 150.dp).onFocusChanged { focused = it.isFocused }.padding(8.dp), textStyle = TextStyle(color = AtollColors.Text, fontSize = 16.sp, lineHeight = 24.sp),
                    decorationBox = { inner -> if (state.text.isEmpty()) Text("输入消息；@ 选择成员，/ 使用命令", color = AtollColors.Muted, fontSize = 16.sp); inner() })
                IconButton(onSend, enabled = state.text.isNotBlank() && !state.sending,
                    modifier = Modifier.background(if (state.text.isBlank()) AtollColors.MutedSurface else AtollColors.Accent, RoundedCornerShape(10.dp))) {
                    Icon(Icons.Outlined.ArrowUpward, "发送", tint = if (state.text.isBlank()) AtollColors.Muted else AtollColors.Workspace)
                }
            }
            if (state.text.isNotEmpty() || focused) Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton({ onAction("选择 Agent") }, Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 6.dp)) {
                    Text(listOf(state.agent, state.model, state.effort).filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { "选择 Agent" }, fontSize = 11.sp, color = AtollColors.Muted, maxLines = 1)
                }
                IconButton({ onAction("附件") }) { Icon(Icons.Outlined.AttachFile, "添加附件", tint = AtollColors.Muted) }
                IconButton({ onAction("更多操作") }) { Icon(Icons.Outlined.Add, "更多操作", tint = AtollColors.Muted) }
            }
        }
    }
}
@Composable
fun TasksScreen(tasks: List<TaskViewState>, modifier: Modifier = Modifier, onAction: (String) -> Unit) {
    Column(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("WORK ITEMS", color = AtollColors.Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text("任务", style = MaterialTheme.typography.titleLarge)
            Text("审批、运行中回合、恢复事项和自动动作在这里按真实来源汇总。", fontSize = 12.sp, color = AtollColors.Muted)
            Row {
                OutlinedButton({ onAction("安排自动动作") }, shape = MaterialTheme.shapes.small) { Text("安排自动动作", fontSize = 11.sp) }
                TextButton({ onAction("新建任务") }) { Text("新建任务", fontSize = 11.sp) }
            }
        }
        HorizontalDivider(color = AtollColors.Line)
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton({ onAction("工作项") }) { Text("工作项 ${tasks.size}", fontSize = 12.sp) }
            TextButton({ onAction("等待区") }) { Text("等待区", fontSize = 12.sp, color = AtollColors.Muted) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).background(AtollColors.MutedSurface, RoundedCornerShape(6.dp))) {
            TextButton({ onAction("与我相关") }, Modifier.weight(1f)) { Text("与我相关", fontSize = 12.sp, color = AtollColors.Text) }
            TextButton({ onAction("全部") }, Modifier.weight(1f)) { Text("全部", fontSize = 12.sp, color = AtollColors.Muted) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ onAction("状态筛选") }, Modifier.weight(1f), shape = MaterialTheme.shapes.small) { Text("待处理 ⌄", fontSize = 12.sp, color = AtollColors.Text) }
            OutlinedButton({ onAction("类型筛选") }, Modifier.weight(1f), shape = MaterialTheme.shapes.small) { Text("全部类型 ⌄", fontSize = 12.sp, color = AtollColors.Text) }
        }
        LazyColumn(contentPadding = PaddingValues(16.dp)) {
            items(tasks, key = { it.id }) { task ->
                Column(Modifier.fillMaxWidth().clickable { onAction(task.id) }.padding(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(task.kind, color = AtollColors.Warning, fontSize = 11.sp)
                    Text(task.title, style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(task.assignee, color = AtollColors.Muted, fontSize = 12.sp)
                        Text("${task.state} ›", color = AtollColors.Muted, fontSize = 12.sp)
                    }
                }
                HorizontalDivider(color = AtollColors.Line)
            }
        }
    }
}

@Composable
private fun FloatingFilters(state: ConversationViewState, onScope: () -> Unit, onActor: (String) -> Unit, modifier: Modifier) {
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (state.mineOnly) state.agents.forEach { agent ->
            Surface(shape = CircleShape, color = if (agent.id in state.actorFilters) AtollColors.Selected else AtollColors.Workspace,
                border = BorderStroke(1.dp, AtollColors.ControlLine), shadowElevation = 2.dp) {
                Row(Modifier.heightIn(min = 44.dp).clickable { onActor(agent.id) }.semantics { selected = agent.id in state.actorFilters }.padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (agent.active) Box(Modifier.size(6.dp).background(AtollColors.Online, CircleShape))
                    Text(agent.name, fontSize = 10.sp)
                }
            }
        }
        if (state.mineOnly) (state.actorFilters - state.agents.map { it.id }.toSet()).forEach { staleId ->
            Surface(shape = CircleShape, color = AtollColors.Selected, border = BorderStroke(1.dp, AtollColors.ControlLine)) {
                Box(Modifier.heightIn(min = 44.dp).clickable { onActor(staleId) }.semantics { selected = true }.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                    Text("已失效 · $staleId", color = AtollColors.Muted, fontSize = 10.sp)
                }
            }
        }
        Surface(shape = CircleShape, color = AtollColors.Workspace, border = BorderStroke(1.dp, AtollColors.ControlLine), shadowElevation = 4.dp) {
            Box(Modifier.heightIn(min = 44.dp).clickable(onClick = onScope).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                Text(if (state.mineOnly) "与我相关" else "全部", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

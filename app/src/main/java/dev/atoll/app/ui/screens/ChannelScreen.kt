package dev.atoll.app.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.atoll.app.ui.components.*
import dev.atoll.app.ui.theme.AtollColors

@Composable
fun ChannelScreen(state: ConversationViewState, composer: ComposerViewState, tab: ChannelTab,
    onBack: () -> Unit, onTab: (ChannelTab) -> Unit, onScope: () -> Unit, onActor: (String?) -> Unit,
    onText: (String) -> Unit, onSend: () -> Unit, onOlder: () -> Unit,
    onMenu: (String) -> Unit = {}, onLink: ((String) -> Unit)? = null) {
    Column(Modifier.fillMaxSize().background(AtollColors.Workspace).safeDrawingPadding().imePadding()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.Outlined.Menu, "频道列表") }
            Column(Modifier.weight(1f)) {
                Text(state.channel.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(state.connection, color = AtollColors.Online, fontSize = 10.sp)
            }
            IconButton({ onMenu("文件") }) { Icon(Icons.Outlined.FolderOpen, "文件") }
            IconButton({ onMenu("更多") }) { Icon(Icons.Outlined.MoreHoriz, "频道菜单") }
        }
        TabRow(selectedTabIndex = tab.ordinal, containerColor = AtollColors.Workspace) {
            Tab(tab == ChannelTab.Conversation, { onTab(ChannelTab.Conversation) }, text = { Text("动态") })
            Tab(tab == ChannelTab.Tasks, { onTab(ChannelTab.Tasks) }, text = { Text("任务") })
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(state.mineOnly, onScope, label = { Text(if (state.mineOnly) "与我相关" else "全部", fontSize = 11.sp) })
            state.agents.forEach { agent -> FilterChip(state.actorFilter == agent.id, { onActor(if (state.actorFilter == agent.id) null else agent.id) }, label = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (agent.active) Box(Modifier.size(6.dp).background(AtollColors.Online, CircleShape))
                    Text(agent.name, fontSize = 11.sp)
                }
            }) }
        }
        if (tab == ChannelTab.Conversation) {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 3.dp, vertical = 6.dp)) {
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
            if (state.waitingCount > 0) Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).background(AtollColors.MutedSurface, RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)).padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${state.waitingCount} 条等待消息", Modifier.weight(1f), fontSize = 12.sp)
                TextButton({ onMenu("等待区") }) { Text("展开", fontSize = 12.sp) }
            }
            Composer(composer, onText, onSend, onMenu)
        } else TasksScreen(state.tasks, Modifier.weight(1f)) { onMenu(it) }
    }
}
@Composable
fun Composer(state: ComposerViewState, onText: (String) -> Unit, onSend: () -> Unit, onAction: (String) -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), color = AtollColors.Input,
        shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, AtollColors.ControlLine), shadowElevation = 3.dp) {
        Column(Modifier.padding(6.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                BasicTextField(state.text, onText, Modifier.weight(1f).heightIn(min = 44.dp, max = 150.dp).padding(8.dp), textStyle = TextStyle(color = AtollColors.Text, fontSize = 16.sp, lineHeight = 24.sp),
                    decorationBox = { inner -> if (state.text.isEmpty()) Text("发送消息…", color = AtollColors.Muted, fontSize = 16.sp); inner() })
                IconButton(onSend, enabled = state.text.isNotBlank() && !state.sending,
                    modifier = Modifier.background(if (state.text.isBlank()) AtollColors.MutedSurface else AtollColors.Accent, RoundedCornerShape(10.dp))) {
                    Icon(Icons.Outlined.ArrowUpward, "发送", tint = if (state.text.isBlank()) AtollColors.Muted else AtollColors.Workspace)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton({ onAction("安排自动动作") }) { Text("安排自动动作", fontSize = 12.sp) }
            TextButton({ onAction("新建任务") }) { Text("新建任务", fontSize = 12.sp) }
        }
        Text("工作项 ${tasks.size}  ·  等待区", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("待处理", "已完成", "失败").forEach { label -> AssistChip({ onAction(label) }, label = { Text(label, fontSize = 12.sp) }) }
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(tasks, key = { it.id }) { task -> Surface(Modifier.fillMaxWidth().clickable { onAction(task.id) }, color = AtollColors.ChannelRail, shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, AtollColors.Line)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${task.kind} · ${task.state}", color = AtollColors.Warning, fontSize = 11.sp)
                    Text(task.title, style = MaterialTheme.typography.titleMedium)
                    Text(task.assignee, color = AtollColors.Muted, fontSize = 12.sp)
                }
            } }
        }
    }
}

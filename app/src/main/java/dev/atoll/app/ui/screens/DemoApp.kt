package dev.atoll.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.material3.*
import androidx.compose.runtime.*
import dev.atoll.app.ui.components.RichContentHost
import dev.atoll.app.ui.components.LocalMarkdownCache
import dev.atoll.app.ui.components.MarkdownCache
import dev.atoll.app.ui.theme.AtollTheme

/** Temporary preview host; screens accept state/callbacks for AppGraph integration. */
@Composable
fun DemoApp(initialScreen: String = "login") {
    val cache = remember { MarkdownCache() }
    var drawer by remember { mutableStateOf(initialScreen == "channels") }
    var panel by remember { mutableStateOf<String?>(when (initialScreen) { "process", "running", "starred" -> initialScreen; else -> null }) }
    var stars by remember { mutableStateOf(ContentDemoData.stars) }
    var screen by remember { mutableStateOf(initialScreen) }
    var login by remember { mutableStateOf(LoginViewState("https://tardis.tail6bc2a1.ts.net", "root")) }
    var tab by remember { mutableStateOf(if (initialScreen == "tasks") ChannelTab.Tasks else ChannelTab.Conversation) }
    var mine by remember { mutableStateOf(true) }
    var actorFilters by remember { mutableStateOf(emptySet<String>()) }
    var draft by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(DemoData.channels.first()) }
    BackHandler(screen != "login") { screen = if (screen == "channels") "login" else "channels" }
    AtollTheme {
        CompositionLocalProvider(LocalMarkdownCache provides cache) {
        RichContentHost {
            when (screen) {
                "content", "math", "mermaid", "code", "images", "json", "fold" -> ContentGallery(screen, { screen = "channel" })
                "login" -> LoginScreen(login, { login = it }, { screen = "channels" }, { menu = "注册账号" })
                else -> ChannelScreen(ConversationViewState(selected, DemoData.agents, DemoData.entries, DemoData.tasks, mine, actorFilters, waitingCount = 2),
                    ComposerViewState(draft, "sol-4", "6.1 Sol", "high"), tab,
                    { drawer = true }, { tab = it }, { mine = !mine }, { id -> actorFilters = if (id in actorFilters) actorFilters - id else actorFilters + id }, { draft = it }, { draft = "" },
                    { menu = "已到频道最早一条动态" }, { action -> when {
                        action == ChannelMenuAction.Item.Stars -> panel = "starred"
                        action == ChannelMenuAction.Item.Running -> panel = "running"
                        action is ChannelMenuAction.Process -> panel = "process"
                        else -> menu = when (action) { is ChannelMenuAction.Item -> action.label; is ChannelMenuAction.Task -> action.taskId; is ChannelMenuAction.Process -> "过程" }
                    } }, { menu = "文件预览：$it" })
            }
            if (drawer) ChannelDrawer(ContentDemoData.navigation(selected.id), { drawer = false }, { id -> selected = ContentDemoData.channels.first { it.id == id }; screen = "channel" },
                { menu = "新建频道" }, { menu = "活动" }, { menu = "空间管理" }, { drawer = false; screen = "login" }, { menu = "升级已开始" })
            when (panel) {
                "process" -> ProcessPanel(ContentDemoData.process, { panel = null })
                "running" -> RunningDrawer(ContentDemoData.subtasks, { it == "m1" }, { panel = null })
                "starred" -> StarredDrawer(stars, { it == "m1" }, { id -> stars = stars.filterNot { it.id == id } }, { panel = null })
            }
            menu?.let { title -> AlertDialog(onDismissRequest = { menu = null }, title = { Text(title) },
                text = { Text("界面预览；此操作将在接线阶段连接节点。") }, confirmButton = { TextButton({ menu = null }) { Text("关闭") } }) }
        }
        }
    }
}
object DemoData {
    val channels = listOf(
        ChannelViewState("c0.dev", "c0.dev", "Atoll 开发与协作", 3, 2, "刚刚"),
        ChannelViewState("c0.dev.web", "c0.dev.web", "原生 Android 客户端", 1, 4, "12:42"),
        ChannelViewState("dsh-personal-agent.competitor-research", "competitor-research", "竞品研究与资料整理", 0, 0, "昨天"),
        ChannelViewState("c0", "c0", "空间管理", 0, 0, "周一"),
    )
    val agents = listOf(AgentViewState("claude", "claude", true), AgentViewState("sol-4", "sol-4", true), AgentViewState("sol-1", "sol-1"))
    val entries = listOf(
        TimelineEntry.Day("day-1", "今天 · 10 月 13 日"),
        TimelineEntry.Message(MessageViewState("m1", "root", "开发一个原生的 Android 前端吧，布局样式参考现在 web。", "12:38", recipient = "claude", self = true)),
        TimelineEntry.Message(MessageViewState("m2", "claude", "我会按照完整版本安排任务，先把核心通信、缓存和界面并行开发。\n\n- **协议**：一条 WebSocket\n- **缓存**：本地 SQLite，后台补齐历史\n- **界面**：Kotlin + Jetpack Compose", "12:39", agent = true)),
        TimelineEntry.Notice("notice-1", "sol-4 已加入频道"),
        TimelineEntry.Message(MessageViewState("m3", "sol-4", "已完成主题和时间线组件。Markdown 支持 `inline code`、~~删除线~~和[文件链接](/workspace/README.md:12)。", "12:42", agent = true, status = MessageStatus.Processing, progress = "正在编译界面")),
        TimelineEntry.Message(MessageViewState("m4", "sol-4", "```kotlin\nval screen = ChannelScreen(state)\n```\n\n> 主线程只负责显示，解析放在后台。\n\n| 页面 | 状态 |\n| --- | --- |\n| 登录 | 完成 |\n| 频道 | 验证中 |", "12:43", agent = true, continuation = true)),
    )
    val tasks = listOf(TaskViewState("task-1", "审核 Android 界面与移动端样式", "审批", "root", "需要你处理"),
        TaskViewState("task-2", "实现本地缓存与历史同步", "任务", "sol-3", "进行中"),
        TaskViewState("task-3", "模拟器编译和逐页截图", "Agent 回合", "sol-4", "进行中"))
}

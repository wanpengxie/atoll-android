package dev.atoll.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.atoll.app.ui.components.*
import dev.atoll.app.ui.theme.AtollColors
import kotlinx.serialization.json.*

/** Review-only gallery, not part of the app's navigation contract. */
@Composable
fun ContentGallery(page: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(AtollColors.Workspace).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onBack, Modifier.size(44.dp)) { Text("‹", color = AtollColors.Text, fontSize = 24.sp) }
            Text("内容预览 · $page", style = MaterialTheme.typography.titleMedium)
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (page) {
                "math" -> item { Markdown("demo-math", ContentDemoData.math) }
                "mermaid" -> {
                    item { Markdown("demo-mermaid", "```mermaid\nflowchart LR\n A[本地缓存] --> B[后台同步]\n B --> C[频道时间线]\n```") }
                    item { Markdown("demo-invalid-mermaid", "```mermaid\nthis is not a valid diagram !!!\n```") }
                }
                "code" -> item { Markdown("demo-code", ContentDemoData.code) }
                "images" -> {
                    item { Markdown("demo-image", "![Atoll](https://tardis.tail6bc2a1.ts.net/icon-512.png)") }
                    item { Markdown("demo-image-error", "![缺失图片](https://tardis.tail6bc2a1.ts.net/missing-w2-image.png)") }
                }
                "fold" -> {
                    item { FoldableMarkdown("demo-long", ContentDemoData.longText) }
                    item { Text("最新消息不折叠", fontSize = 12.sp); FoldableMarkdown("demo-latest", ContentDemoData.longText, latest = true) }
                }
                "json" -> {
                    item { StructuredResult(ResultViewState(ContentDemoData.json)) }
                    item { StructuredResult(ResultViewState(errorCode = "receiver_unavailable", detail = "the receiver's process ended before it answered", errorData = ContentDemoData.json)) }
                    item { StructuredResult(ResultViewState(emptyText = true)) }
                    item { StructuredResult(ResultViewState(acknowledgement = true)) }
                }
                else -> {
                    item { ProgressTrail(ContentDemoData.trail, {}) }
                    item { SubTaskTree(ContentDemoData.subtasks, onOpen = {}) }
                    item { Markdown("demo-combined", ContentDemoData.code + "\n\n" + ContentDemoData.math) }
                }
            }
        }
    }
}
object ContentDemoData {
    private val now = System.currentTimeMillis()
    val channels = listOf(
        ChannelViewState("c0", "c0", unread = 120, activity = listOf(AgentActivityViewState("r1", "claude", now - 63000), AgentActivityViewState("r2", "sol-4", now - 42000), AgentActivityViewState("r3", "sol-1", now - 12000))),
        ChannelViewState("c0.dev", "c0.dev", parentId = "c0", depth = 1, unreadPhase = UnreadPhase.Recovering),
        ChannelViewState("c0.dev.web", "c0.dev.web", parentId = "c0.dev", depth = 2, access = ChannelAccess.MemberStale),
        ChannelViewState("c0.public", "c0.public", access = ChannelAccess.Discoverable, unreadPhase = UnreadPhase.Unknown),
        ChannelViewState("observer", "研究频道", access = ChannelAccess.ObserverActive),
    )
    fun navigation(active: String) = ChannelNavigationViewState(channels, ConnectionPhase.OPEN, AccountViewState("root", "已登录用户", root = true), active,
        UpgradeViewState("v0.06", "v0.07"))
    val json = Json.parseToJsonElement("""{"ok":true,"channel":"c0.dev","agent":{"name":"sol-4","options":{"model":"6.1 Sol","effort":"high"}},"items":[1,2,3],"nothing":null}""")
    val records = listOf(
        ProcessRecordViewState("p1", "思考中…", "16:40", body = "准备核对界面和协议。"),
        ProcessRecordViewState("p2", "tool: workspace.bash 完成", "16:41", ProcessKind.Tool,
            input = Json.parseToJsonElement("""{"command":"./gradlew assembleDebug"}"""), outputText = "BUILD SUCCESSFUL\n" + "编译记录\n".repeat(600), body = "编译 Android 客户端。", identifiers = listOf("工具调用编号" to "call-001")),
        ProcessRecordViewState("p3", "tool: workspace.read 失败", "16:42", ProcessKind.Tool, output = Json.parseToJsonElement("""{"error":"file_not_found"}""")),
    )
    val process = ProcessPanelViewState("检查原生界面", "request-001", "agent.ask", "processing", records, 42, selectedRecordId = "p2")
    val trail = ProgressTrailViewState("检查原生界面", records, true, now - 63000, "16:40")
    val subtasks = listOf(
        SubTaskViewState("sub-1", "实现协议层", "claude", SubTaskKind.Agent, SubTaskPhase.Running, now - 180000, "m1", "正在核对帧格式", request = "实现 WebSocket 协议。", result = "已完成 attach。"),
        SubTaskViewState("sub-2", "编译客户端", "sol-4", SubTaskKind.Shell, SubTaskPhase.Running, now - 60000, "missing-message", "正在编译 Kotlin"),
        SubTaskViewState("sub-3", "缓存同步测试", "sol-3", SubTaskKind.Agent, SubTaskPhase.Completed, now - 240000, "m1", result = "测试通过。"),
        SubTaskViewState("sub-4", "失败案例", "sol-2", SubTaskKind.Agent, SubTaskPhase.Failed, now - 120000, result = "接收方已不可用。"),
    )
    val stars = listOf(StarredMessageViewState("m1", "root", "10/13 16:40", "开发完整的原生客户端。"), StarredMessageViewState("missing", "claude", "10/12 14:12", "历史记录尚未加载。"))
    val code = "```kotlin\npackage dev.atoll.app\n\n// 主线程只负责显示\nfun greet(name: String): String {\n    val attempts = 3\n    return \"Hello, \" + name\n}\n```"
    val math = "行内公式：\\(E = mc^2\\)，以及 $" + "x^2 + y^2$。\n\n\\[\\frac{1}{\\sqrt{2\\pi}}\\int_{-\\infty}^{\\infty} e^{-x^2/2} dx = 1\\]\n\n$$\\sum_{n=1}^{\\infty} \\frac{1}{n^2} = \\frac{\\pi^2}{6}$$"
    val longText = (1..32).joinToString("\n") { "第 $it 行：所有内容在后台准备，时间线按消息缓存。" }
}

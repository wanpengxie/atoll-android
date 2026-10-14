package dev.atoll.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atoll.app.tasks.ApprovalCard
import dev.atoll.app.tasks.TaskAction
import dev.atoll.app.tasks.TaskFact
import dev.atoll.app.ui.components.*
import dev.atoll.app.ui.screens.*
import dev.atoll.app.ui.theme.AtollTheme
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Renders the conversation v2 boards (docs/design/CHAT_V2.md) with the real components and the boards' own copy,
 * so each screenshot can be laid next to its artboard. No account, node or write operation.
 */
@RunWith(AndroidJUnit4::class)
class ChatV2BoardsUiTest {
    @get:Rule val compose = createComposeRule()
    private val now = System.currentTimeMillis()
    private val agents = listOf(AgentViewState("agent:builder", "builder", active = true), AgentViewState("agent:reviewer", "reviewer"),
        AgentViewState("agent:tester", "tester", active = true), AgentViewState("agent:planner", "planner"),
        AgentViewState("agent:deployer", "deployer"), AgentViewState("agent:test-writer", "test-writer"))
    private val tasks = (1..3).map { TaskViewState("t$it", "任务 $it", "审批", "builder", "待处理") }

    private fun agent(id: String, name: String, text: String, time: String, continuation: Boolean = false, status: MessageStatus? = null,
        progress: String? = null, trail: ProgressTrailViewState? = null, part: MessagePart = MessagePart.Single, turn: TurnViewState? = null,
        result: ResultViewState? = null, related: List<RelatedCallViewState> = emptyList(), subTasks: List<SubTaskViewState> = emptyList(),
        quote: String? = null, human: Boolean = false) =
        TimelineEntry.Message(MessageViewState(id, name, text, time, agent = !human, continuation = continuation, status = status, progress = progress,
            trail = trail, part = part, turnState = turn, result = result, relatedCalls = related, subTasks = subTasks,
            senderId = (if (human) "human:" else "agent:") + name, quote = quote))
    private fun mine(id: String, text: String, quote: String? = null, turn: TurnViewState? = null, recipient: String? = null) =
        TimelineEntry.Message(MessageViewState(id, "root", text, "", self = true, quote = quote, turnState = turn, recipient = recipient, senderId = "human:root"))
    private val running = ProgressTrailViewState("跑 UI 测试", listOf(ProcessRecordViewState("r1", "跑 UI 测试", "10:44")), true, now - 134_000)

    private fun approval(ask: Boolean) = TaskFact("approval:${if (ask) "ask" else "approve"}", "a", "c", "approval",
        if (ask) "changelog 写进哪个版本？" else "测试通过后推送到 release/1.4", state = "waiting", requesterActorId = "agent:builder",
        actions = listOf(TaskAction.APPROVE, TaskAction.REJECT), actionableBySelf = true, dueAt = (now + 14 * 60_000 + 30_000).toString(),
        diagnostic = buildJsonObject { put("requestType", if (ask) "human.ask" else "human.approve") })

    private var list: androidx.compose.foundation.lazy.LazyListState? = null
    @Composable private fun Board(entries: List<TimelineEntry>, state: (ConversationViewState) -> ConversationViewState = { it },
        content: @Composable (MessageViewState, @Composable () -> Unit) -> Unit = { message, default -> Row(message, default) }) {
        val cache = remember { MarkdownCache() }
        AtollTheme { CompositionLocalProvider(LocalMarkdownCache provides cache) {
            ChannelScreen(state(ConversationViewState(ChannelViewState("c", "atoll-web"), agents = agents, entries = entries, tasks = tasks,
                hasOlder = false, mineOnly = false, waitingCount = 0)), ComposerViewState(agent = "builder"), ChannelTab.Conversation,
                onBack = {}, onTab = {}, onScope = {}, onActor = {}, onText = {}, onSend = {}, onOlder = {}, composerVisible = false,
                currentAgentId = "agent:builder", messageContent = content, listState = rememberLazyListState().also { list = it })
        } }
    }

    @Composable private fun Row(message: MessageViewState, default: @Composable () -> Unit) {
        when {
            message.id.startsWith("approval:") -> ApprovalCard(approval(message.id == "approval:ask"), mapOf("agent:builder" to "builder"), timeline = true) { _, _, _ -> }
            message.turnState?.compact == true -> CompactTurnRow(message, {}, {}, true)
            message.turnState?.kind == TurnKind.Failed -> CompositionLocalProvider(LocalTurnResend provides TurnResend({}, true)) { default() }
            else -> default()
        }
    }

    private val main = listOf(
        TimelineEntry.Day("d", "今天 10:20"),
        agent("p1", "planner", "1.4 的发布清单整理好了，三项窄屏问题合并成“修复窄屏顶栏”。", "10:20"),
        mine("m1", "好，reviewer 先过一遍顶栏"),
        agent("r1", "reviewer", "审查完成，有 1 处需要处理：顶栏在 360dp 宽度下频道名被截断，建议把标题区改成可伸缩布局。", "10:31"),
        mine("m2", "@builder 按这个修，顺带把登录页键盘遮挡也处理掉，改完跑一遍 UI 测试"),
        agent("b1", "builder", "两处都改好了：标题区用可伸缩布局；登录表单改成顶部对齐、随键盘滚动。正在跑 UI 测试…", "10:42",
            status = MessageStatus.Processing, progress = "跑 UI 测试", trail = running),
        TimelineEntry.Message(MessageViewState("approval:approve", "builder", "", "", agent = true)))

    @Test fun main() {
        compose.setContent { Board(main) }
        compose.onNodeWithText("请你审批").assertExists()
        screenshot("Main")
        compose.onNodeWithContentDescription("atoll-web", substring = true).performClick()
        screenshot("Filters")
        compose.onNodeWithContentDescription("atoll-web", substring = true).performClick()
        compose.onNodeWithContentDescription("聊天信息", substring = true).performClick()
        screenshot("ChannelDetails")
        compose.onNodeWithText("消息显示").performClick()
        screenshot("ChannelDetails-display")
    }

    @Test fun chatStates() {
        val code = (1..42).joinToString("\n") { if (it == 1) "Row(Modifier.fillMaxWidth()) {" else "    Text(title, Modifier.weight(1f)) // line $it" }
        val collab = listOf(
            RelatedCallViewState("c1", "命名", "builder", listOf("reviewer"), "10:48", MessageStatus.Completed, conversation = true, text = "帮我看下这个 diff 的命名"),
            RelatedCallViewState("c2", "命名", "reviewer", listOf("builder"), "10:48", MessageStatus.Processing, conversation = true, text = "处理中 00:41"),
            RelatedCallViewState("c3", "命名", "reviewer", listOf("builder"), "10:49", MessageStatus.Completed, conversation = true, text = "好"))
        val steps = listOf(
            SubTaskViewState("s1", "修复顶栏截断", "builder", SubTaskKind.Agent, SubTaskPhase.Completed, 0),
            SubTaskViewState("s2", "登录页键盘遮挡", "builder", SubTaskKind.Agent, SubTaskPhase.Completed, 0),
            SubTaskViewState("s3", "跑 UI 测试", "tester", SubTaskKind.Agent, SubTaskPhase.Running, now - 190_000),
            SubTaskViewState("s4", "推送 release/1.4", "deployer", SubTaskKind.Agent, SubTaskPhase.Failed, 0))
        val entries = listOf(
            TimelineEntry.Day("d1", "昨天 22:10"), TimelineEntry.Notice("n1", "alice 把 tester 加入了频道"),
            agent("a1", "alice", "今晚把 1.4 发出去，有问题直接 @ 我", "22:10", human = true),
            agent("a2", "alice", "release 分支我已经切好了", "22:10", continuation = true, human = true),
            TimelineEntry.Filtered("f1", 6),
            mine("m1", "先别推，等 tester 跑完", quote = "reviewer：审查完成，有 1 处需要处理：顶栏在 360dp 宽度下频道名被截断…"),
            agent("b1", "builder", "好，推送挂起，等测试结果。", "10:44", quote = "我：先别推，等 tester 跑完"),
            mine("m2", "@builder @tester 各自确认下 360dp 的截图"),
            agent("b2", "builder", "截图已更新，标题完整显示。", "10:46"),
            agent("t1", "tester", "我这边 3 台设备都正常。", "10:46"),
            agent("b3", "builder", "改动涉及命名，先请 reviewer 确认。", "10:48", related = collab),
            agent("b4", "builder", "拆成 4 步：", "10:50", subTasks = steps, status = MessageStatus.Processing, trail = running.copy(startedAtMillis = now - 302_000), progress = "调用了 7 个工具"),
            agent("b5", "builder", "TopBar 改成这样：\n\n```kotlin\n$code\n```", "10:52"),
            agent("t2", "tester", "模拟器启动后测试卡住。", "11:24", status = MessageStatus.Failed, part = MessagePart.Answer,
                turn = TurnViewState(TurnKind.Failed, "t2", resendable = true), result = ResultViewState(errorCode = "unanswered_timeout", detail = "30 分钟")),
            agent("b6", "builder", "正在整理变更说明…", "11:26", part = MessagePart.Answer, turn = TurnViewState(TurnKind.Stopped, "b6")),
            mine("m3", "部署到 staging", turn = TurnViewState(TurnKind.Lost, "m3", lostReason = "restart", receiverId = "agent:deployer"), recipient = "deployer"),
            mine("m4", "把 360dp 改成 320dp", turn = TurnViewState(TurnKind.Preempted, "m4", "m5")),
            mine("m5", "顺便更新 changelog", turn = TurnViewState(TurnKind.Merged, "m5", "m6")),
            mine("m7", "Discard this isolated UI test request.", turn = TurnViewState(TurnKind.Cancelled, "m7")),
            mine("m6", "先停下，优先修登录页"),
            TimelineEntry.Message(MessageViewState("approval:ask", "builder", "", "", agent = true)))
        compose.setContent { Board(entries) }
        // One screenshot per numbered case group, scrolled so the group starts at the top.
        listOf(0, 5, 10, 11, 12, 13, 15).forEachIndexed { page, index ->
            compose.runOnIdle { kotlinx.coroutines.runBlocking { list!!.scrollToItem(index) } }
            compose.waitForIdle()
            screenshot("ChatStates-$page")
        }
    }

    @Test fun messageActionsProcessAndCollaboration() {
        compose.setContent { Board(main.take(6), content = { message, default ->
            if (message.id == "r1") {
                val envelope = dev.atoll.app.core.Envelope(buildJsonObject {
                    put("id", "r1"); put("channel_id", "c"); put("kind", "response"); put("type", "agent.ask")
                    put("sender", buildJsonObject { put("id", "agent:reviewer"); put("kind", "agent") })
                })
                dev.atoll.app.compose.TimelineMessageActions(message, envelope, null, "human:root", emptyList(), true, true, false, emptySet(),
                    onReply = {}, onEdit = {}, onCreateTask = { _, _ -> }, onToggleStar = { _, _ -> }, onOpenProcess = {}, content = default)
            } else default()
        }) }
        compose.onAllNodesWithText("审查完成", substring = true, useUnmergedTree = true).onLast().performTouchInput { longClick() }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("atoll.message.actions").fetchSemanticsNodes().isNotEmpty() }
        screenshot("MessageActions")
    }

    @Test fun processSheetCollabSheetAndHumanAsk() {
        val records = listOf(
            ProcessRecordViewState("p1", "读取文件", "0.4s"), ProcessRecordViewState("p2", "搜索代码", "1.1s"),
            ProcessRecordViewState("p3", "编辑文件", "3.2s"), ProcessRecordViewState("p4", "编译检查失败", "8.6s"),
            ProcessRecordViewState("p5", "补全导入并重新编译", "7.9s"), ProcessRecordViewState("p6", "子 Agent · test-runner", "01:02"))
        val panel = ProcessPanelViewState("修复登录页键盘遮挡", "req", "agent.ask", "processing", records, startTime = "10:42",
            startedAtMillis = now - 134_000, agent = AgentViewState("agent:builder", "builder"),
            request = MessageViewState("req", "root", "我 → builder：登录页在小屏上按钮被键盘挡住了，帮我修一下，改完跑一遍 UI 测试。", "10:42"))
        val show = mutableStateOf("process")
        compose.setContent { AtollTheme {
            when (show.value) {
                "process" -> ProcessPanel(panel, {}, onStop = {}, onCancel = {}, onGuide = {})
                "collab" -> CollaborationContent(CollaborationViewState(
                    RelatedCallViewState("root", "修顶栏截断和登录页键盘遮挡", "我", listOf("builder"), "10:42", MessageStatus.Processing), listOf(
                        RelatedCallViewState("rv", "帮我看一下 LoginScreen 的 diff", "builder", listOf("reviewer"), "10:44", MessageStatus.Completed,
                            conversation = true, parentId = "root", text = "没问题。布局改法可以，建议给键盘场景补一个 UI 测试。", request = "帮我看一下 LoginScreen 的 diff"),
                        RelatedCallViewState("tw", "起草键盘遮挡的 UI 测试", "reviewer", listOf("test-writer"), "10:45", MessageStatus.Processing,
                            conversation = true, parentId = "rv", text = "已起草 2 个用例，正在请 tester 在 360dp 模拟器上跑。", request = "起草键盘遮挡的 UI 测试，覆盖 360dp 宽度"),
                        RelatedCallViewState("ts", "在 360dp 模拟器上跑 LoginScreenUiTest", "test-writer", listOf("tester"), "10:46", MessageStatus.Processing,
                            conversation = true, parentId = "tw", text = "模拟器已启动，第 1/2 项通过，第 2 项在跑。")), "builder"))
                else -> Column(Modifier.padding(12.dp)) {
                    ApprovalCard(approval(false).copy(description = "改动 2 个文件 · +18 −9；UI 测试 42 项通过"), mapOf("agent:builder" to "builder")) { _, _, _ -> }
                    ApprovalCard(approval(true), mapOf("agent:builder" to "builder")) { _, _, _ -> }
                }
            }
        } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("修复登录页键盘遮挡").fetchSemanticsNodes().isNotEmpty() }
        compose.mainClock.advanceTimeBy(1_000); compose.waitForIdle(); screenshot("Process")
        compose.runOnIdle { show.value = "collab" }; compose.waitForIdle(); screenshot("CollabSheet")
        compose.runOnIdle { show.value = "ask" }; compose.waitForIdle(); screenshot("HumanAsk")
    }

    private val Int.dp get() = androidx.compose.ui.unit.Dp(toFloat())

    private fun screenshot(name: String) {
        val directory = File(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?: "/sdcard/Android/media/dev.atoll.app/additional_test_output", "chat-v2")
        check(directory.mkdirs() || directory.isDirectory)
        // The device screenshot also holds sheets and popovers, which live in their own windows.
        compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            ?: compose.onAllNodes(isRoot()).onLast().captureToImage().asAndroidBitmap()
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

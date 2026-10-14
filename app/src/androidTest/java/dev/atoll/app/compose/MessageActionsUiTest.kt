package dev.atoll.app.compose

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atoll.app.core.Envelope
import dev.atoll.app.core.Row
import dev.atoll.app.model.Turn
import dev.atoll.app.ui.components.*
import dev.atoll.app.ui.screens.*
import dev.atoll.app.ui.theme.AtollColors
import dev.atoll.app.ui.theme.AtollTheme
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MessageActionsUiTest {
    @get:Rule val compose = createComposeRule()
    private var toolbar: TextToolbar? = null
    private val roster = listOf(ComposerActor("agent:reviewer", "reviewer", "agent"))
    private fun envelope(id: String, sender: String = "agent:reviewer", response: Boolean = true) = Envelope(buildJsonObject {
        put("id", id); put("channel_id", "fixture"); put("type", "agent.ask"); put("kind", if (response) "response" else "request")
        put("sender", buildJsonObject { put("id", sender); put("kind", if (sender == "self") "human" else "agent") })
        put("audience", buildJsonArray { add("agent:reviewer") }); put("payload", buildJsonObject { put("body", buildJsonObject { put("status", if (response) "completed" else "") }) })
    })
    private val body = "审查完成，有 1 处需要处理：顶栏在 360dp 宽度下频道名被截断。"
    private fun answer() = MessageViewState("answer", "reviewer", body, "10:31", agent = true,
        status = MessageStatus.Completed, trail = ProgressTrailViewState("过程", List(6) {
            ProcessRecordViewState("record-$it", "步骤 $it", "10:31") }, false))
    private fun running() = Turn(envelope("request", "self", false), 1, listOf(Row("fixture", 2, Envelope(buildJsonObject {
        put("id", "progress"); put("kind", "response"); put("status", "processing")
        put("payload", buildJsonObject { put("body", buildJsonObject { put("status", "processing"); put("controls", buildJsonArray {
            for (word in listOf("agent.replace", "agent.interrupt")) add(buildJsonObject { put("word", word) })
        }) }) })
    }))))
    @Composable private fun Fixture(content: @Composable () -> Unit) {
        AtollTheme {
            val cache = remember { MarkdownCache() }
            val textToolbar = LocalTextToolbar.current
            SideEffect { toolbar = textToolbar }
            CompositionLocalProvider(LocalMarkdownCache provides cache) {
                Surface(color = AtollColors.Ground, modifier = Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().padding(horizontal = 12.dp, vertical = 48.dp)) { content() }
                }
            }
        }
    }
    private fun open(id: String) {
        compose.onNodeWithTag("atoll.message.$id").performTouchInput { longClick() }
        awaitMenu()
    }
    private fun awaitMenu() {
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag("atoll.message.actions").isDisplayed() }.getOrDefault(false) }
        compose.onNodeWithTag("atoll.message.actions").assertIsDisplayed()
        compose.runOnIdle { assertEquals("Long press must not open the system selection toolbar", TextToolbarStatus.Hidden, toolbar?.status) }
    }
    @Test fun actualMarkdownLongPressOpensBottomMenuThenFiveTabProcessSheet() {
        val message = answer()
        val request = envelope("request", "self", false)
        val terminal = envelope("answer")
        val turn = Turn(request, 1, terminal = terminal)
        var opened by mutableStateOf("")
        compose.setContent { Fixture {
            TimelineMessageActions(message, terminal, turn, "self", roster, true, true, false, emptySet(),
                onReply = {}, onEdit = {}, onCreateTask = { _, _ -> }, onToggleStar = { _, _ -> }, onOpenProcess = { opened = it }) {
                MessageFrame(message)
            }
            if (opened.isNotEmpty()) ProcessPanel(ProcessPanelViewState("reviewer", opened, "agent.ask", "completed",
                message.trail!!.records), { opened = "" })
        } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText(body).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText(body, useUnmergedTree = true).onLast().performTouchInput { longClick() }
        awaitMenu()
        compose.onNodeWithText("6 条").assertIsDisplayed()
        compose.onNodeWithText("追问 reviewer").assertIsDisplayed()
        compose.onNodeWithText("查看过程与相关调用").assertHeightIsAtLeast(52.dp)
        screenshot("message-actions")
        compose.onNodeWithText("查看过程与相关调用").performClick()
        compose.runOnIdle { assertEquals("request", opened) }
        for (label in listOf("过程", "协作", "子任务", "调用", "账本")) compose.onNodeWithText(label).assertIsDisplayed()
        screenshot("message-process")
    }
    @Test fun ownRunningRequestKeepsEditAndStopAndBlocksConcurrentControls() {
        val turn = running()
        val editing = mutableStateOf("")
        val busy = mutableStateOf(emptySet<String>())
        val events = mutableListOf<String>()
        val message = MessageViewState("request", "我", "修复这个问题", "10:31", self = true, recipient = "reviewer", part = MessagePart.Request)
        compose.setContent { Fixture {
            TimelineMessageActions(message, turn.request, turn, "self", roster, true, true, false,
                setOf("agent:reviewer"), editingTargetId = editing.value, onReply = {}, onEdit = { events += "edit:$it" },
                onStop = { events += "stop:$it" }, busyActors = busy.value, onOpenProcess = {}) { MessageFrame(message) }
        } }
        open("request")
        compose.onNodeWithText("编辑").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf("edit:request"), events) }
        open("request")
        compose.onNodeWithText("停止本轮").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf("edit:request", "stop:request"), events); editing.value = "other"; busy.value = setOf("agent:reviewer") }
        open("request")
        compose.onNodeWithText("编辑").assertIsNotEnabled()
        compose.onNodeWithText("停止本轮").assertIsNotEnabled()
        screenshot("message-own-disabled")
    }
    @Test fun readonlySheetAllowsProcessCopyAndStarButNoWriteActions() {
        val message = answer()
        var tasks = 0
        var stars = 0
        compose.setContent { Fixture {
            TimelineMessageActions(message, envelope("answer"), null, "self", roster, false, false, false, emptySet(),
                onReply = { error("read only") }, onEdit = { error("read only") }, onCreateTask = { _, _ -> tasks++ },
                onToggleStar = { _, _ -> stars++ }, onOpenProcess = {}) { MessageFrame(message) }
        } }
        open("answer")
        compose.onNodeWithText("从这里新建任务").assertIsNotEnabled()
        compose.onNodeWithText("编辑").assertDoesNotExist()
        compose.onNodeWithText("停止本轮").assertDoesNotExist()
        compose.onNodeWithText("追问 reviewer").assertDoesNotExist()
        compose.onNodeWithText("查看过程与相关调用").assertIsEnabled()
        compose.onNodeWithText("复制正文与结构化结果").assertIsEnabled()
        compose.onNodeWithText("星标").performClick()
        compose.runOnIdle { assertEquals(0, tasks); assertEquals(1, stars) }
    }
    @Test fun textSelectionContainsFullTerminalResult() {
        val message = answer().copy(result = ResultViewState(buildJsonObject { put("answer", 42) }))
        compose.setContent { Fixture {
            TimelineMessageActions(message, envelope("answer"), null, "self", roster, true, true, false, emptySet(),
                onReply = {}, onEdit = {}) { MessageFrame(message) }
        } }
        // Long-press the message prose; the JSON disclosure header has its own tap action.
        compose.onAllNodesWithText(body, useUnmergedTree = true).onLast().performTouchInput { longClick() }
        awaitMenu()
        compose.onNodeWithText("选择文本").performClick()
        compose.onNodeWithTag("atoll.message.selection").assertExists()
        compose.onNodeWithText(messageCopyText(message)).assertIsDisplayed()
        compose.onNodeWithText(messageCopyText(message)).performTouchInput { longClick() }
        screenshot("message-selection")
        compose.onNodeWithContentDescription("关闭").performClick()
        compose.onNodeWithTag("atoll.message.selection").assertDoesNotExist()
    }
    @Test fun markdownOutsideTimelineRemainsSelectable() {
        compose.setContent { Fixture { Markdown("outside-timeline", body) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText(body).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText(body, useUnmergedTree = true).onLast().performTouchInput { longClick() }
        compose.waitUntil(5_000) { toolbar?.status == TextToolbarStatus.Shown }
        compose.onNodeWithTag("atoll.message.actions").assertDoesNotExist()
        screenshot("outside-timeline-selection")
    }
    @Test fun swipeStillRepliesWithoutOpeningMenu() {
        var replied: ReplyTarget? = null
        val message = answer()
        compose.setContent { Fixture {
            TimelineMessageActions(message, envelope("answer"), null, "self", roster, true, true, false, emptySet(),
                onReply = { replied = it }, onEdit = {}) { Text(message.text, Modifier.fillMaxWidth()) }
        } }
        compose.onNodeWithTag("atoll.message.answer").performTouchInput { swipeRight() }
        compose.runOnIdle { assertEquals("answer", replied?.sourceId); assertEquals("agent:reviewer", replied?.senderId) }
        compose.onNodeWithTag("atoll.message.actions").assertDoesNotExist()
    }
    private fun screenshot(name: String) {
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        // Compose clock settling does not advance the platform window fade animation.
        android.os.SystemClock.sleep(350)
        val folder = File(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?: "/sdcard/Android/media/dev.atoll.app/additional_test_output", "message-actions").also { it.mkdirs() }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}

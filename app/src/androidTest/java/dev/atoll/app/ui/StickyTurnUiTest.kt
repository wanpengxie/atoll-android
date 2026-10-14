package dev.atoll.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atoll.app.ui.screens.*
import dev.atoll.app.ui.theme.AtollTheme
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class StickyTurnUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun onlyExpandedTallTurnPinsItsHeaderThenUnpinsAfterCollapseOrLeavingTheTurn() {
        val calls = (1..4).map { index -> RelatedCallViewState("call:$index", "协作任务 $index", "builder", listOf("worker-$index"), "12:00",
            MessageStatus.Completed, text = "已完成检查并留下详细记录 $index", conversation = true) }
        val answer = MessageViewState("opaque:answer", "builder", (1..30).joinToString("\n\n") { "这是一段较长的回复，保留这轮请求的上下文。段落 $it。" },
            "12:01", agent = true, status = MessageStatus.Completed, relatedCalls = calls, part = MessagePart.Answer)
        val entries = mutableStateOf(listOf(
            TimelineEntry.Message(MessageViewState("opaque:request", "root", "修顶栏截断和登录页键盘遮挡", "12:00", self = true, part = MessagePart.Request)),
            TimelineEntry.Message(answer)) + (1..20).map { TimelineEntry.Message(MessageViewState("after:$it", "root", "后续消息 $it", "12:02")) })
        var scrollTo: (Int, Int) -> Unit = { _, _ -> }
        val opened = mutableListOf<ChannelMenuAction>()
        compose.setContent { AtollTheme {
            val list = rememberLazyListState()
            val scope = rememberCoroutineScope()
            val pixels = with(LocalDensity.current) { 100.dp.roundToPx() }
            SideEffect { scrollTo = { index, offset -> scope.launch { list.scrollToItem(index, if (offset == 0) 0 else pixels) } } }
            ChannelScreen(ConversationViewState(ChannelViewState("test", "test"), entries = entries.value,
                hasOlder = false, historyStartLabel = null, identityReady = false), ComposerViewState(), ChannelTab.Conversation,
                {}, {}, {}, {}, {}, {}, {}, onMenu = { opened += it }, listState = list, composerVisible = false)
        } }
        compose.onNodeWithContentDescription("粘顶回合标题").assertDoesNotExist()
        compose.onNodeWithText("协作 4").performScrollTo().performClick()
        compose.runOnIdle { scrollTo(1, 1) }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("粘顶回合标题").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("粘顶回合标题").assertIsDisplayed()
        compose.onNodeWithText("builder · 修顶栏截断和登录页键盘遮挡").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(ChannelMenuAction.Process("opaque:answer"), opened.last()) }
        screenshot("sticky-turn")
        compose.runOnIdle { scrollTo(5, 0) }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("粘顶回合标题").fetchSemanticsNodes().isEmpty() }
        compose.runOnIdle { scrollTo(1, 0) }
        compose.onNodeWithText("收起").performScrollTo().performClick()
        compose.runOnIdle { scrollTo(1, 1) }
        compose.onNodeWithContentDescription("粘顶回合标题").assertDoesNotExist()
        screenshot("sticky-collapsed")
    }
    private fun screenshot(name: String) {
        compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
        val directory = File(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?: "/sdcard/Android/media/dev.atoll.app/additional_test_output", "sticky-turn").also { it.mkdirs() }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
}

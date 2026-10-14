package dev.atoll.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atoll.app.ui.components.*
import dev.atoll.app.ui.screens.*
import dev.atoll.app.ui.theme.AtollTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Designed views in isolation: no account, node, or write operation. */
@RunWith(AndroidJUnit4::class)
class CollaborationActivityUiTest {
    @get:Rule val compose = createComposeRule()
    private fun call(id: String, title: String, sender: String, recipient: String, parent: String = "",
        type: String = "agent.ask", status: MessageStatus = MessageStatus.Completed) = RelatedCallViewState(id,
        title, sender, listOf(recipient), "10:43", status, conversation = true, parentId = parent, requestType = type,
        request = title, text = if (status == MessageStatus.Completed) "已经核对了请求中的内容。" else "")

    @Composable private fun Fixture(content: @Composable () -> Unit) {
        val cache = remember { MarkdownCache() }
        AtollTheme { CompositionLocalProvider(LocalMarkdownCache provides cache) {
            Surface { Box(Modifier.fillMaxSize().safeDrawingPadding()) { content() } }
        } }
    }

    @Test fun collaborationNavigatesByPathAndSwipeAndKeepsAPendingQuestionActionable() {
        val state = CollaborationViewState(call("root", "修顶栏和登录键盘遮挡", "我", "builder"), listOf(
            call("review", "审查键盘布局", "builder", "reviewer", "root"),
            call("test", "起草测试", "reviewer", "test-writer", "review"),
            call("question", "要覆盖横屏吗", "reviewer", "我", "review", "human.ask", MessageStatus.Processing)), "builder")
        var opened = ""
        compose.setContent { Fixture { CollaborationContent(state, initialRequestId = "review", onOpenRequest = { opened = it }) } }
        compose.onNodeWithContentDescription("线程路径").assertExists()
        screenshot("collaboration-reviewer")
        compose.onNodeWithContentDescription("进入协作 起草测试").performClick()
        compose.onNodeWithText("test-writer").assertExists()
        screenshot("collaboration-deep-path")
        compose.onRoot().performTouchInput { swipeLeft() }
        compose.onNodeWithContentDescription("进入协作 要覆盖横屏吗").performClick()
        compose.onNodeWithText("回原位置处理").performClick()
        compose.runOnIdle { assertEquals("question", opened) }
        screenshot("collaboration-question")
        compose.onNodeWithText("builder").performClick()
        compose.onNodeWithContentDescription("进入协作 审查键盘布局").assertExists()
    }

    @Test fun activityGroupsExpandAndOnlyAllOffersThePersistedPreferenceControl() {
        val group = TimelineEntry.ChannelActivity("activity-one", listOf(
            ChannelActivityItem("joined", 1, "reviewer 已加入频道", "16:02"),
            ChannelActivityItem("build", 2, "开始构建成员 release-bot（第 2 次尝试）", "16:03"),
            ChannelActivityItem("failed", 3, "成员 release-bot 构建失败（第 2 次尝试，已停止）", "16:05", failed = true)))
        val state = mutableStateOf(ConversationViewState(ChannelViewState("fixture", "atoll-web"),
            entries = listOf(group), mineOnly = false, hasOlder = false))
        compose.setContent { Fixture { ChannelScreen(state.value, ComposerViewState(), ChannelTab.Conversation,
            onBack = {}, onTab = {}, onScope = { state.value = state.value.copy(mineOnly = !state.value.mineOnly) },
            onActor = {}, onText = {}, onSend = {}, onOlder = {}, composerVisible = false,
            onChannelActivity = { show -> state.value = state.value.copy(showChannelActivity = show, entries = if (show) listOf(group) else emptyList()) }) } }
        compose.onNodeWithContentDescription("频道活动").assertIsOn()
        compose.onNodeWithContentDescription("展开 3 条频道活动").performClick()
        compose.onNodeWithText("reviewer 已加入频道 · 16:02").assertIsDisplayed()
        compose.onNodeWithText("成员 release-bot 构建失败（第 2 次尝试，已停止） · 16:05").assertIsDisplayed()
        screenshot("activity-expanded-all")
        compose.onNodeWithContentDescription("收起 3 条频道活动").performClick()
        compose.onNodeWithText("reviewer 已加入频道 · 16:02").assertDoesNotExist()
        compose.onNodeWithContentDescription("频道活动").performClick().assertIsOff()
        compose.onNodeWithContentDescription("展开 3 条频道活动").assertDoesNotExist()
        screenshot("activity-disabled-all")
        compose.onNodeWithText("与我相关").performClick()
        compose.onNodeWithContentDescription("频道活动").assertDoesNotExist()
    }

    private fun screenshot(name: String) {
        val directory = File(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?: "/sdcard/Android/media/dev.atoll.app/additional_test_output", "collaboration-activity")
        check(directory.mkdirs() || directory.isDirectory)
        File(directory, "$name.png").outputStream().use {
            compose.onAllNodes(isRoot()).onLast().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}

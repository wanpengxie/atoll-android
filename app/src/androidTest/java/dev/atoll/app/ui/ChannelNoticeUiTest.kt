package dev.atoll.app.ui

import android.graphics.Bitmap
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atoll.app.ui.screens.*
import dev.atoll.app.ui.theme.AtollTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ChannelNoticeUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun historicalWindowNoticeSitsBelowFiltersAndReturnsToLatest() {
        val detached = mutableStateOf(false)
        val files = mutableStateOf(false)
        var jumps = 0
        compose.setContent { AtollTheme {
            ChannelScreen(ConversationViewState(ChannelViewState("test", "频道")), ComposerViewState(""),
                ChannelTab.Conversation, {}, {}, {}, {}, {}, {}, {}, composerVisible = false,
                newerUnloaded = detached.value, onJumpToBottom = { jumps++; detached.value = false },
                contentOverride = if (files.value) ({ Text("文件内容") }) else null)
        } }
        val notice = "正在看较早的记录。这里不显示运行和等待状态。"
        compose.onNodeWithText(notice).assertDoesNotExist()
        compose.runOnIdle { detached.value = true }
        compose.onNodeWithText(notice).assertIsDisplayed()
        screenshot("history-window")
        assertTrue(compose.onNodeWithText(notice).fetchSemanticsNode().boundsInRoot.top >=
            compose.onNodeWithText("与我相关").fetchSemanticsNode().boundsInRoot.bottom)
        compose.onNodeWithText("回到最新").performClick()
        compose.runOnIdle { assertEquals(1, jumps) }
        compose.onNodeWithText(notice).assertDoesNotExist()
        compose.runOnIdle { detached.value = true; files.value = true }
        compose.onNodeWithText("文件内容").assertIsDisplayed()
        compose.onNodeWithText(notice).assertDoesNotExist()
    }

    @Test fun reconnectNoticeKeepsCachedContentBelowTheHeaderAndRetriesWithoutNavigating() {
        val phase = mutableStateOf(ConnectionPhase.OPEN)
        var retries = 0
        compose.setContent { AtollTheme {
            ChannelScreen(ConversationViewState(ChannelViewState("test", "atoll-web"), hasOlder = false, identityReady = false,
                entries = listOf(TimelineEntry.Message(MessageViewState("cached", "builder", "缓存中的记录", "10:44")))), ComposerViewState(""),
                ChannelTab.Conversation, {}, {}, {}, {}, {}, {}, {}, composerVisible = false,
                connectionPhase = phase.value, onReconnect = { retries++ })
        } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("缓存中的记录").fetchSemanticsNodes().isNotEmpty() }
        val notice = "连接中断。你仍可浏览已缓存的记录，消息会先存在本机。"
        compose.onNodeWithText(notice).assertDoesNotExist()
        for ((connection, caption) in listOf(ConnectionPhase.RECONNECTING to "重连中", ConnectionPhase.CONNECTING to "连接中", ConnectionPhase.CLOSED to "已断开")) {
            compose.runOnIdle { phase.value = connection }
            compose.onNodeWithText(caption).assertIsDisplayed()
            compose.onNodeWithText(notice).assertIsDisplayed()
            compose.onNodeWithText("缓存中的记录").assertIsDisplayed()
            val bounds = compose.onNodeWithText(notice).fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.top >= compose.onNodeWithText(caption).fetchSemanticsNode().boundsInRoot.bottom)
            assertTrue(bounds.bottom <= compose.onNodeWithText("动态").fetchSemanticsNode().boundsInRoot.top)
            compose.onNodeWithText("立即重试").assertIsEnabled().performClick()
            if (connection == ConnectionPhase.RECONNECTING) screenshot("reconnect-banner")
        }
        compose.runOnIdle { assertEquals(3, retries); phase.value = ConnectionPhase.OPEN }
        compose.onNodeWithText(notice).assertDoesNotExist()
        compose.onNodeWithText("已连接").assertIsDisplayed()
        compose.onNodeWithText("缓存中的记录").assertIsDisplayed()
    }

    private fun screenshot(name: String) {
        val directory = File(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?: "/sdcard/Android/media/dev.atoll.app/additional_test_output", "channel-notices")
        check(directory.mkdirs() || directory.isDirectory)
        File(directory, "$name.png").outputStream().use {
            compose.onAllNodes(isRoot()).onLast().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}

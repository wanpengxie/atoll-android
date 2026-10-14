package dev.atoll.app.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.atoll.app.ui.screens.*
import dev.atoll.app.ui.theme.AtollTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

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
        assertTrue(compose.onNodeWithText(notice).fetchSemanticsNode().boundsInRoot.top >=
            compose.onNodeWithText("与我相关").fetchSemanticsNode().boundsInRoot.bottom)
        compose.onNodeWithText("回到最新").performClick()
        compose.runOnIdle { assertEquals(1, jumps) }
        compose.onNodeWithText(notice).assertDoesNotExist()
        compose.runOnIdle { detached.value = true; files.value = true }
        compose.onNodeWithText("文件内容").assertIsDisplayed()
        compose.onNodeWithText(notice).assertDoesNotExist()
    }
}

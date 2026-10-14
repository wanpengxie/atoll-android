package dev.atoll.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atoll.app.governance.*
import dev.atoll.app.ui.theme.AtollTheme
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NewChannelUiTest {
    @get:Rule val compose = createComposeRule()
    private val fixture = GovernanceState(channel = "parent", writable = true,
        memberChannels = setOf("parent"), directory = GovernanceDirectory(channels = listOf(buildJsonObject {
            put("id", "parent"); put("qualified_name", "atoll")
        })), entries = listOf(buildJsonObject { put("id", "builder"); put("name", "builder") }))

    @Test fun threeStartModesKeepTheOriginalCreatePayloadAndFixedFooter() {
        var submitted: ChannelCreateForm? = null
        var hideKeyboard: () -> Unit = {}
        compose.setContent {
            val keyboard = LocalSoftwareKeyboardController.current
            hideKeyboard = { keyboard?.hide() }
            AtollTheme { ChannelCreateContent(fixture, "me", emptyList(),
            { submitted = it; "created-request" }, {}, {}, onClose = {}) } }
        compose.onNodeWithText("创建频道").assertIsNotEnabled()
        compose.onNodeWithContentDescription("频道名称").performTextInput("release_1")
        compose.onNodeWithText("创建频道").assertIsNotEnabled()
        compose.onNodeWithContentDescription("频道名称").performTextReplacement("release-1-4")
        compose.onNodeWithText("创建频道").assertIsEnabled()
        compose.runOnIdle { hideKeyboard() }
        screenshot("new-channel-blank")
        compose.onNodeWithText("复制频道").performClick()
        compose.onNodeWithText("复制哪个频道").assertExists()
        compose.onNodeWithText("挑选成员").performClick()
        compose.onNodeWithTag("channel-create-form").performScrollToNode(hasText("builder"))
        compose.onNodeWithText("builder").performClick()
        screenshot("new-channel-pick")
        compose.onNodeWithText("创建频道").performClick()
        compose.runOnIdle {
            assertEquals("parent", submitted?.parent)
            assertEquals("me", submitted?.selfPrincipal)
            assertEquals(ChannelStart.Pick, submitted?.start)
            assertEquals("builder", (submitted?.members?.single()?.get("id") as JsonPrimitive).content)
        }
    }
    @Test fun uncertainCreationIsReadOnlyAndRefreshesFactsWithoutResubmitting() {
        var writes = 0; var refresh = ""
        val uncertain = ChannelCreation("pending", "parent", "release-1-4", 0, accepted = true,
            ledger = true, targetId = "target", observable = true, serving = true, uncertain = true)
        compose.setContent { AtollTheme { ChannelCreateContent(fixture, "me", listOf(uncertain),
            { writes++; "duplicate" }, {}, { refresh = it }, onClose = {}) } }
        compose.onNodeWithText("等待频道就绪…").assertIsNotEnabled()
        compose.onNodeWithContentDescription("频道名称").assertIsNotEnabled()
        compose.onNodeWithText("刷新创建事实").performScrollTo().performClick()
        compose.onNodeWithText("服务已启动，等待成员关系确认。").assertIsDisplayed()
        screenshot("new-channel-converging")
        compose.runOnIdle { assertEquals(0, writes); assertEquals("pending", refresh) }
    }
    private fun screenshot(name: String) {
        val dir = File(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?: "/sdcard/Android/media/dev.atoll.app/additional_test_output", "new-channel")
        check(dir.mkdirs() || dir.isDirectory)
        File(dir, "$name.png").outputStream().use {
            compose.onAllNodes(isRoot()).onLast().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}

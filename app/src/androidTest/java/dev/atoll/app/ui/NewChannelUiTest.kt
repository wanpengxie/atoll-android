package dev.atoll.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atoll.app.governance.*
import dev.atoll.app.ui.components.FullScreenForm
import dev.atoll.app.ui.theme.AtollTheme
import kotlinx.serialization.json.*
import org.junit.Assert.*
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import dev.atoll.app.MainActivity
import dev.atoll.app.app.AppPreferences
import kotlinx.coroutines.runBlocking
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NewChannelUiTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val prepare = object : ExternalResource() {
        override fun before() {
            val server = InstrumentationRegistry.getArguments().getString("atollUrl")
            require(server == "http://100.99.232.10:18080")
            runBlocking { AppPreferences(InstrumentationRegistry.getInstrumentation().targetContext).saveAccount(server!!, "root") }
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(prepare).around(compose)
    private fun content(block: @Composable () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { compose.activity.setContent(content = block) }
    }

    private val fixture = GovernanceState(channel = "parent", writable = true,
        memberChannels = setOf("parent"), directory = GovernanceDirectory(channels = listOf(buildJsonObject {
            put("id", "parent"); put("qualified_name", "atoll")
        })), entries = listOf(buildJsonObject { put("id", "builder"); put("name", "builder") }))

    @Test fun threeStartModesKeepTheOriginalCreatePayloadAndFixedFooter() {
        var submitted: ChannelCreateForm? = null
        var hideKeyboard: () -> Unit = {}
        content {
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
        content { AtollTheme { ChannelCreateContent(fixture, "me", listOf(uncertain),
            { writes++; "duplicate" }, {}, { refresh = it }, onClose = {}) } }
        compose.onNodeWithText("等待频道就绪…").assertIsNotEnabled()
        compose.onNodeWithContentDescription("频道名称").assertIsNotEnabled()
        compose.onNodeWithText("刷新创建事实").performScrollTo().performClick()
        compose.onNodeWithText("服务已启动，等待成员关系确认。").assertIsDisplayed()
        screenshot("new-channel-converging")
        compose.runOnIdle { assertEquals(0, writes); assertEquals("pending", refresh) }
    }
    @Test fun activityOverlayFooterRemainsClickableAboveTheOpenKeyboard() {
        var submitted: ChannelCreateForm? = null
        var dialogView: android.view.View? = null
        var showKeyboard: () -> Unit = {}
        content { AtollTheme {
            FullScreenForm({}) {
                dialogView = LocalView.current
                val keyboard = LocalSoftwareKeyboardController.current
                showKeyboard = { keyboard?.show() }
                ChannelCreateContent(fixture, "me", emptyList(), { submitted = it; "sent" }, {}, {}, onClose = {})
            }
        } }
        compose.onNodeWithContentDescription("频道名称").performClick().performTextInput("keyboard-visible")
        compose.runOnIdle { showKeyboard(); ViewCompat.requestApplyInsets(compose.activity.window.decorView) }
        compose.waitUntil(10_000) { dialogView?.let { ViewCompat.getRootWindowInsets(it)?.isVisible(WindowInsetsCompat.Type.ime()) } == true }
        compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
        val button = compose.onNodeWithText("创建频道")
        val view = checkNotNull(dialogView)
        val insets = checkNotNull(ViewCompat.getRootWindowInsets(view))
        val location = IntArray(2); view.getLocationOnScreen(location)
        val buttonBottom = button.fetchSemanticsNode().boundsInWindow.bottom + location[1]
        val keyboardTop = android.graphics.Rect().also { view.getWindowVisibleDisplayFrame(it) }.bottom
        val visible = android.graphics.Rect(); view.getWindowVisibleDisplayFrame(visible)
        println("FOOTER_GEOMETRY view=${view.height} root=${view.rootView.height} ime=${insets.getInsets(WindowInsetsCompat.Type.ime()).bottom} location=${location[1]} visibleBottom=${visible.bottom} buttonBottom=$buttonBottom")
        val rawShot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
        val shotDir = File(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: "/sdcard/Android/media/dev.atoll.app/additional_test_output", "new-channel").also { it.mkdirs() }
        File(shotDir, "new-channel-keyboard-device.png").outputStream().use { rawShot.compress(Bitmap.CompressFormat.PNG, 100, it) }; rawShot.recycle()
        File(shotDir, "keyboard-geometry.json").writeText("""{"view_height":${view.height},"root_height":${view.rootView.height},"ime_bottom":${insets.getInsets(WindowInsetsCompat.Type.ime()).bottom},"view_y":${location[1]},"visible_bottom":${visible.bottom},"button_bottom":$buttonBottom}""")
        button.assertIsDisplayed().assertIsEnabled()
        assertTrue("Footer must be above IME: $buttonBottom <= $keyboardTop", buttonBottom <= keyboardTop)
        screenshot("new-channel-keyboard-open")
        button.performClick()
        compose.runOnIdle { assertEquals("keyboard-visible", submitted?.name) }
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

package dev.atoll.app.ui

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atoll.app.roster.*
import dev.atoll.app.ui.components.FullScreenForm
import dev.atoll.app.ui.theme.AtollTheme
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
class MemberConfigUiTest {
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

    private val info = MemberConfig.parse("""{"class":"codex","body":{"class":"codex"},"desired_host":"local-device","effective":{"api_key":"${'$'}global.provider_key","deploy_token":"${'$'}global.deploy_token","selections":[{"model":"gpt-test","effort":"high","effort_label":"高"}],"default":0},"own_config":{"values":{"api_key":"${'$'}global.provider_key","deploy_token":"${'$'}global.deploy_token"},"revision":5},"build":{"result":"ok","description":{"channel_revision":2},"config":{"revision":5}}}""")
    private fun state() = RosterUiState(selected = RosterActor("agent:builder", "builder", "agent", "class codex"), online = true, writable = true,
        member = info, editor = MemberEditor("config", info, text = info.obj("own_config").obj("values").toString()), choices = RosterChoices(globalNames = listOf("provider_key")))
    @Test fun groupedFormKeepsReferencesAndExposesTheMinimalDiffAcrossTabs() {
        val state = mutableStateOf(state())
        var saves = 0; var hideKeyboard: () -> Unit = {}
        content {
            val keyboard = LocalSoftwareKeyboardController.current; hideKeyboard = { keyboard?.hide() }
            AtollTheme { MemberConfigContent(state.value, onUpdate = { state.value = state.value.copy(editor = it) }, onSave = { saves++ }) }
        }
        compose.onNodeWithText("保存配置").assertIsNotEnabled()
        compose.onNodeWithText("引用的全局 key 不存在：global/deploy_token。请在空间管理添加。").assertExists()
        screenshot("member-config-form")
        compose.onNodeWithContentDescription("deploy_token").performScrollTo().performTextReplacement("${'$'}global.provider_key")
        compose.runOnIdle { hideKeyboard() }
        compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
        compose.onNodeWithText("保存配置").assertIsEnabled()
        compose.onNodeWithText("将提交的变更 · 1 处").performScrollTo()
        compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
        compose.onNodeWithText("将提交的变更 · 1 处").performClick()
        compose.onNodeWithText("RFC 7396 patch").assertExists()
        screenshot("member-config-diff")
        compose.onNodeWithText("JSON", useUnmergedTree = true).performClick()
        compose.onNodeWithText("成员配置 JSON").assertExists()
        compose.onNodeWithText("保存配置").performClick()
        compose.runOnIdle {
            assertEquals(1, saves)
            assertEquals("${'$'}global.provider_key", MemberConfig.parse(state.value.editor!!.text).text("deploy_token"))
        }
    }
    @Test fun restartRequiresConfirmationAndOfflineAccessDisablesWrites() {
        val state = mutableStateOf(state().copy(editor = null))
        var restarts = 0
        content { AtollTheme { MemberConfigContent(state.value, onRestart = { restarts++ }) } }
        compose.onNodeWithText("重启").performClick()
        compose.runOnIdle { assertEquals(0, restarts) }
        screenshot("member-config-restart-confirmation")
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(0, restarts) }
        compose.onNodeWithText("重启").performClick()
        compose.onNodeWithText("确认重启").performClick()
        compose.runOnIdle { assertEquals(1, restarts); state.value = state.value.copy(online = false) }
        compose.onNodeWithText("重启").assertIsNotEnabled()
        compose.onNodeWithText("保存配置").assertIsNotEnabled()
        compose.onNodeWithText("能力", useUnmergedTree = true).performClick()
        compose.onNodeWithText("该 Actor 没有公布可调用能力。").assertExists()
    }
    @Test fun selectionOnlyConfigurationShowsModelRowsWithoutAnEmptyConfigurationNotice() {
        val selectionOnly = MemberConfig.parse("""{"class":"codex","body":{"class":"codex"},"effective":{"selections":[{"model":"gpt-test","effort":"high"}],"default":0}}""")
        content { AtollTheme { MemberConfigContent(state().copy(member = selectionOnly, editor = null)) } }
        compose.onNodeWithText("模型").assertExists()
        compose.onNodeWithText("默认推理强度").assertExists()
        compose.onNodeWithText("没有简单字段；可在 JSON 标签查看和编辑完整配置。").assertDoesNotExist()
        screenshot("member-config-selection-only")
    }
    @Test fun memberSaveFooterRemainsClickableAboveTheOpenKeyboard() {
        val state = mutableStateOf(state())
        var saves = 0; var dialogView: android.view.View? = null
        var showKeyboard: () -> Unit = {}
        content { AtollTheme {
            FullScreenForm({}) {
                dialogView = LocalView.current
                val keyboard = LocalSoftwareKeyboardController.current
                showKeyboard = { keyboard?.show() }
                MemberConfigContent(state.value, onUpdate = { state.value = state.value.copy(editor = it) }, onSave = { saves++ })
            }
        } }
        compose.onNodeWithContentDescription("deploy_token").performScrollTo().performClick().performTextReplacement("${'$'}global.provider_key")
        compose.runOnIdle { showKeyboard(); ViewCompat.requestApplyInsets(compose.activity.window.decorView) }
        compose.waitUntil(10_000) { dialogView?.let { ViewCompat.getRootWindowInsets(it)?.isVisible(WindowInsetsCompat.Type.ime()) } == true }
        compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
        val button = compose.onNodeWithText("保存配置").assertIsDisplayed().assertIsEnabled()
        val view = checkNotNull(dialogView); val insets = checkNotNull(ViewCompat.getRootWindowInsets(view))
        val location = IntArray(2); view.getLocationOnScreen(location)
        val bottom = button.fetchSemanticsNode().boundsInWindow.bottom + location[1]
        val keyboardTop = android.graphics.Rect().also { view.getWindowVisibleDisplayFrame(it) }.bottom
        assertTrue("Save footer must be above IME: $bottom <= $keyboardTop", bottom <= keyboardTop)
        screenshot("member-config-keyboard-open")
        button.performClick(); compose.runOnIdle { assertEquals(1, saves) }
    }
    private fun screenshot(name: String) {
        val dir = File(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?: "/sdcard/Android/media/dev.atoll.app/additional_test_output", "member-config")
        check(dir.mkdirs() || dir.isDirectory)
        File(dir, "$name.png").outputStream().use {
            compose.onAllNodes(isRoot()).onLast().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}

package dev.atoll.app.ui

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atoll.app.app.WorkspaceSettings
import dev.atoll.app.app.WorkspaceState
import dev.atoll.app.net.MemoryKeyValueStore
import dev.atoll.app.terminal.TerminalPreferences
import dev.atoll.app.terminal.TerminalTheme
import dev.atoll.app.ui.screens.*
import dev.atoll.app.ui.theme.AtollTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MeDesignUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun meKeepsHomeNavigationAndExistingActionsWhileThemePersists() {
        val store = MemoryKeyValueStore()
        val terminal = TerminalPreferences(store)
        val state = WorkspaceState(login = LoginViewState("http://100.99.232.10:18080"),
            navigation = ChannelNavigationViewState(emptyList(), ConnectionPhase.OPEN, AccountViewState("human:self", "测试账号", root = true),
                upgrade = UpgradeViewState("1.0", phase = "current", available = false)))
        var channels = 0; var activity = 0; var logout = 0; var admin = 0; var exports = 0
        compose.setContent { AtollTheme { WorkspaceSettings(state, null, terminal, {}, { exports++ }, { logout++ },
            onSpaceAdmin = { admin++ }, onChannels = { channels++ }, onActivity = { activity++ }) } }
        compose.onNode(hasText("我") and hasClickAction()).assertIsSelected().assertIsDisplayed()
        compose.onNode(hasText("频道") and hasClickAction()).performClick()
        compose.onNode(hasText("活动") and hasClickAction()).performClick()
        compose.onNode(hasText("空间管理") and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithContentDescription("终端深色主题").assertIsOn().performClick().assertIsOff()
        compose.onNodeWithText("导出诊断").assertExists()
        compose.onNode(hasText("导出诊断") and hasClickAction()).assertDoesNotExist()
        compose.onNodeWithText("退出当前账号").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, channels); assertEquals(1, activity); assertEquals(1, admin); assertEquals(1, logout); assertEquals(0, exports)
            assertEquals(TerminalTheme.LIGHT, TerminalPreferences(store).theme.value)
        }
        compose.onNode(hasText("我") and hasClickAction()).assertIsDisplayed()
        compose.mainClock.advanceTimeBy(600); compose.waitForIdle(); Thread.sleep(400)
        val args = InstrumentationRegistry.getArguments()
        val output = File(args.getString("additionalTestOutputDir") ?: "/sdcard/Android/media/dev.atoll.app/additional_test_output", "me-design").also { it.mkdirs() }
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        File(output, "Me.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
    }
}

package dev.atoll.app.ui

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.atoll.app.ui.screens.*
import dev.atoll.app.ui.theme.AtollTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LoginDesignUiTest {
    @get:Rule val compose = createComposeRule()
    private var keyboard: SoftwareKeyboardController? = null
    @Test fun localEditsSurviveUpstreamUpdatesAndOnlySubmitWritesToOwner() {
        val upstream = mutableStateOf(LoginViewState("http://node", "initial", ""))
        val submitted = mutableListOf<LoginViewState>()
        compose.setContent { keyboard = LocalSoftwareKeyboardController.current; AtollTheme { LoginScreen(upstream.value, submitted::add) } }
        compose.onNodeWithContentDescription("服务器地址").performTextReplacement("http://edited")
        compose.onNodeWithContentDescription("账号").performTextReplacement("edited")
        compose.onNodeWithContentDescription("密码").performTextReplacement("test-password")
        compose.runOnIdle { assertTrue(submitted.isEmpty()); upstream.value = upstream.value.copy(server = "http://late", account = "late") }
        compose.onNodeWithContentDescription("服务器地址").assertTextContains("http://edited")
        compose.onNodeWithContentDescription("账号").assertTextContains("edited")
        screenshot("Login-filled")
        compose.onNodeWithText("进入 Atoll").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(LoginViewState("http://edited", "edited", "test-password"), submitted.single()) }
    }
    @Test fun footerSwitchesAuthModeWithoutLosingInputAndErrorBelongsToItsSubmittedFields() {
        val submitted = mutableListOf<LoginViewState>()
        compose.setContent { keyboard = LocalSoftwareKeyboardController.current; AtollTheme { LoginScreen(LoginViewState("http://node", "root", "", error = "账号或密码不正确"), submitted::add) } }
        compose.onNodeWithContentDescription("密码").performTextReplacement("test-password")
        screenshot("Login-error")
        compose.onNodeWithText("账号或密码不正确").assertIsDisplayed()
        compose.onNodeWithContentDescription("账号").performTextReplacement("changed")
        compose.onNodeWithText("账号或密码不正确").assertDoesNotExist()
        compose.onNodeWithText("注册", useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription("邮箱").assertTextContains("changed")
        compose.onNodeWithContentDescription("显示名（可选）").performTextReplacement("示例")
        compose.onNodeWithContentDescription("密码").performTextReplacement("test-password")
        compose.onNode(hasText("创建账号") and hasClickAction()).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(AuthMode.Register, submitted.single().mode); assertEquals("示例", submitted.single().displayName) }
    }
    private fun screenshot(name: String) {
        compose.runOnIdle { keyboard?.hide() }
        compose.mainClock.advanceTimeBy(600); compose.waitForIdle(); Thread.sleep(400)
        val args = InstrumentationRegistry.getArguments()
        val output = File(args.getString("additionalTestOutputDir") ?: "/sdcard/Android/media/dev.atoll.app/additional_test_output", "login-design").also { it.mkdirs() }
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        File(output, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
    }
}

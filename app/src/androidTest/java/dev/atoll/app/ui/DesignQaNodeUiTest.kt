package dev.atoll.app.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atoll.app.AtollApp
import dev.atoll.app.MainActivity
import dev.atoll.app.app.*
import dev.atoll.app.ui.screens.*
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File

/** Reads existing content on the isolated node; logs out only its fresh UI cookie. */
@RunWith(AndroidJUnit4::class)
class DesignQaNodeUiTest {
    private val args = InstrumentationRegistry.getArguments()
    private val server = args.getString("atollUrl").also { require(it == "http://100.99.232.10:18080") }!!
    private val channel = args.getString("atollChannelId").orEmpty().also { require(it.matches(Regex("[a-f0-9-]{36}"))) }
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val compose = createAndroidComposeRule<MainActivity>()
    private val prepare = object : ExternalResource() {
        override fun before() = runBlocking<Unit> {
            listOfNotNull(context.filesDir, context.getExternalFilesDir(null)).forEach { File(it, "integration-auth.json").delete() }
            val preferences = AppPreferences(context)
            preferences.saveAccount(server, "root"); preferences.forgetBootstrap(server, "root")
            val namespace = accountNamespace(server, "root")
            preferences.put(namespace, "last-channel", "")
            preferences.put(namespace, "$channel.surface", "")
            preferences.put(namespace, "$channel.browse", "")
            preferences.saveOptions(namespace, channel, ChannelViewOptions())
            context.getSharedPreferences("identity_$namespace", Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(prepare).around(compose)
    private val graph get() = (context.applicationContext as AtollApp).graph
    private fun screenshot(name: String) {
        compose.mainClock.advanceTimeBy(600); compose.waitForIdle()
        Thread.sleep(400) // Android window animations run outside Compose's test clock.
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        val output = File(args.getString("additionalTestOutputDir") ?: "/sdcard/Android/media/dev.atoll.app/additional_test_output", "design-qa").also { it.mkdirs() }
        File(output, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
    }
    @Test fun captureSixLiveDestinationsWithoutSendingWork() {
        compose.onNodeWithText("登录 Atoll").assertIsDisplayed(); screenshot("Login")
        compose.onNodeWithContentDescription("服务器地址").performTextReplacement(server)
        compose.onNodeWithContentDescription("账号").performTextReplacement("root")
        try { compose.onNodeWithContentDescription("密码").performTextReplacement(args.getString("atollPassword").orEmpty()) }
        catch (_: Throwable) { throw AssertionError("Password input failed") }
        compose.runOnUiThread {
            compose.activity.currentFocus?.clearFocus()
            androidx.core.view.WindowInsetsControllerCompat(compose.activity.window, compose.activity.window.decorView)
                .hide(androidx.core.view.WindowInsetsCompat.Type.ime())
        }
        compose.waitForIdle()
        compose.onNodeWithText("进入 Atoll").performScrollTo().performClick()
        compose.waitUntil(40_000) { graph.services.value?.server == server && graph.state.value.identityConfirmed && graph.state.value.navigation.connection == ConnectionPhase.OPEN }
        compose.onNodeWithText("我的频道").assertIsDisplayed(); screenshot("Channels")
        compose.onNodeWithText("我", useUnmergedTree = true).performClick()
        compose.onNodeWithText("退出当前账号").assertExists(); screenshot("Settings")
        compose.onNode(hasText("频道") and hasClickAction()).performClick()
        compose.runOnIdle { graph.openChannel(channel) }
        compose.waitUntil(30_000) { graph.state.value.route?.channel == channel && graph.state.value.timelineSource?.timeline?.isNotEmpty() == true }
        compose.onNodeWithContentDescription("聊天信息", substring = true).assertIsDisplayed(); screenshot("Main")
        compose.runOnIdle { graph.files(true) }
        compose.waitUntil(20_000) { compose.onAllNodesWithText("上传").fetchSemanticsNodes().isNotEmpty() }
        screenshot("Files")
        compose.runOnIdle { graph.files(false); graph.panel(WorkspacePanel.Members) }
        compose.waitUntil(20_000) { compose.onAllNodesWithContentDescription("刷新名册").fetchSemanticsNodes().isNotEmpty() }
        screenshot("Members")
        compose.runOnIdle { graph.closeFocus(); graph.home() }
        compose.waitUntil(20_000) { graph.state.value.route == null && graph.state.value.globalPanel == null }
        compose.onNode(hasText("我") and hasClickAction()).performClick()
        compose.onNodeWithText("退出当前账号").performScrollTo().performClick()
        compose.waitUntil(20_000) { graph.services.value == null && graph.state.value.phase == WorkspacePhase.Login }
        compose.onNodeWithText("进入 Atoll").assertExists()
        // Logout belongs to the fresh UI cookie, never the shared automation session.
        val token = args.getString("atollToken").orEmpty()
        if (token.isNotEmpty()) okhttp3.OkHttpClient().newCall(okhttp3.Request.Builder().url("$server/api/identity/session")
            .header("Authorization", "Bearer $token").build()).execute().use { check(it.code == 200) { "Shared test session was revoked" } }
    }
}

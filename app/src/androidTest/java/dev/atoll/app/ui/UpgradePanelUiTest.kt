package dev.atoll.app.ui

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atoll.app.ui.screens.*
import dev.atoll.app.ui.theme.AtollTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** All states and callbacks are local fakes; this suite never creates an update client. */
@RunWith(AndroidJUnit4::class)
class UpgradePanelUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun upgradingRequiresAnExplicitConfirmationAndCancellingDoesNotRequestIt() {
        val update = mutableStateOf(UpgradeViewState("v1", "v2")); var requests = 0
        compose.setContent { AtollTheme { UpgradePanel(update.value, ConnectionPhase.OPEN, {}, {
            requests++; update.value = update.value.copy(phase = "starting")
        }) } }
        compose.onNodeWithText("正在运行的 Agent 回合会被打断").assertIsDisplayed()
        compose.onNodeWithTag("upgrade-action").performClick()
        compose.onNodeWithText("确认升级到 v2？").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, requests) }
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(0, requests) }
        compose.onNodeWithTag("upgrade-action").performClick()
        compose.onNodeWithText("确认升级").performClick()
        compose.runOnIdle { assertEquals(1, requests) }
        compose.onNodeWithTag("upgrade-action").assertIsNotEnabled()
        compose.onNodeWithText("正在请求升级").assertIsDisplayed()
    }

    @Test fun allMonitorPhasesAndOfflinePermissionsRenderWithoutMakingRequests() {
        val update = mutableStateOf(UpgradeViewState("v1", "v2"))
        val connection = mutableStateOf(ConnectionPhase.OPEN)
        compose.setContent { AtollTheme { UpgradePanel(update.value, connection.value, {}, {}) } }
        for (phase in listOf("available", "starting", "downloading", "verifying", "installing", "restarting", "checking", "current", "unsupported", "unavailable", "failed")) {
            compose.runOnIdle { update.value = UpgradeViewState(if (phase == "current") "v2" else "v1", "v2", phase,
                available = phase !in setOf("current", "unsupported", "unavailable", "checking")) }
            if (phase in setOf("available", "failed")) compose.onNodeWithTag("upgrade-action").assertIsEnabled()
            else compose.onNodeWithTag("upgrade-action").assertIsNotEnabled()
            if (phase == "restarting") {
                compose.onNodeWithText("节点重启中").assertIsDisplayed()
                screenshot("upgrade-restarting")
                compose.runOnIdle { connection.value = ConnectionPhase.RECONNECTING }
                compose.onNodeWithText("节点已断开").assertIsDisplayed()
                compose.onNodeWithText("重新连接并核对版本").assertIsDisplayed()
                screenshot("upgrade-reconnecting")
                compose.runOnIdle { connection.value = ConnectionPhase.OPEN }
            }
        }
        compose.runOnIdle { connection.value = ConnectionPhase.RECONNECTING; update.value = UpgradeViewState("v1", "v2") }
        compose.onNodeWithTag("upgrade-action").assertIsNotEnabled()
    }

    @Test fun readOnlyVersionDetailsCannotUpgrade() {
        compose.setContent { AtollTheme { UpgradePanel(UpgradeViewState("v1", "v2"), ConnectionPhase.OPEN, {}) } }
        compose.onNodeWithTag("upgrade-action").assertIsNotEnabled()
    }

    private fun screenshot(name: String) {
        val directory = File(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?: "/sdcard/Android/media/dev.atoll.app/additional_test_output", "upgrade-panel")
        check(directory.mkdirs() || directory.isDirectory)
        compose.waitForIdle()
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(directory, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}

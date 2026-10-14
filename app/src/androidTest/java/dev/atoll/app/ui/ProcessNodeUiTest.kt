package dev.atoll.app.ui

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atoll.app.AtollApp
import dev.atoll.app.MainActivity
import dev.atoll.app.app.*
import dev.atoll.app.ui.screens.LoginViewState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Read existing test-node turns; validates the real background projection and WorkspaceRoot mount. */
@RunWith(AndroidJUnit4::class)
class ProcessNodeUiTest {
    private val args = InstrumentationRegistry.getArguments()
    private val server = args.getString("atollUrl").also { require(it == "http://100.99.232.10:18080") }!!
    private val channel = args.getString("atollChannelId").orEmpty().also { require(it.matches(Regex("[a-f0-9-]{36}"))) }
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun endedNodeTurnLoadsFivePagesWithItsOwnLedgerAndDisabledGuidance() {
        val graph = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AtollApp).graph
        compose.runOnIdle { graph.login(LoginViewState(server, "root", args.getString("atollPassword").orEmpty())) }
        compose.waitUntil(40_000) { graph.services.value?.server == server && graph.state.value.identityConfirmed }
        compose.runOnIdle { graph.openChannel(channel) }
        compose.waitUntil(30_000) { graph.state.value.route?.channel == channel &&
            graph.state.value.timelineSource?.timeline?.any { it.turn?.terminal != null && it.turn.request.type == "agent.ask" } == true }
        val turn = graph.state.value.timelineSource!!.timeline.last { it.turn?.terminal != null && it.turn.request.type == "agent.ask" }.turn!!
        compose.runOnIdle { graph.route(focusedChannelLink(channel, FocusKind.Turn, turn.requestId)) }
        compose.waitUntil(20_000) { compose.onAllNodesWithContentDescription("过程面板").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("插话引导").assertIsNotEnabled()
        for ((tab, name) in listOf("过程" to "process", "协作" to "collaboration", "子任务" to "tasks", "调用" to "calls", "账本" to "ledger")) {
            // Only the sheet's own tab; the timeline behind the scrim also says 过程.
            compose.onAllNodes(hasText(tab, substring = true) and hasClickAction() and hasAnyAncestor(hasContentDescription("过程面板"))).onFirst().performClick()
            if (tab == "协作") compose.onAllNodesWithText("的回合", substring = true).onFirst().assertExists()
            if (tab == "账本") {
                compose.onNodeWithText(turn.requestId).assertIsDisplayed()
                compose.onNodeWithText(turn.terminal!!.id).assertIsDisplayed()
            }
            compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
            val directory = File(args.getString("additionalTestOutputDir") ?: "/sdcard/Android/media/dev.atoll.app/additional_test_output", "process-node").also { it.mkdirs() }
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
        compose.onNodeWithText("收起").performClick()
        compose.onNodeWithContentDescription("过程面板").assertDoesNotExist()
    }
}

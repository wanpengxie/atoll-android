package dev.atoll.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atoll.app.core.Envelope
import dev.atoll.app.tasks.*
import dev.atoll.app.ui.components.PageTitle
import dev.atoll.app.ui.theme.AtollTheme
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ActivityInboxUiTest {
    @get:Rule val compose = createComposeRule()
    private fun approval(type: String = "human.approve") = TaskFact("approval:c:r", "r", "c", "approval", "允许发布修复？",
        state = "waiting", actions = listOf(TaskAction.APPROVE, TaskAction.REJECT), assigneeActorIds = listOf("self"),
        diagnostic = buildJsonObject { put("requestType", type) })
    private fun snapshot(item: TaskFact) = ActivitySnapshot(listOf(ActivityItem("activity:${item.key}", "c", "项目频道",
        "approval", item.title, item.state, 1, Envelope.EMPTY, approval = item)), emptyList(), false)

    @Test fun directDecisionsDoNotNavigateAndHandledCardsKeepDetails() {
        val commands = mutableStateOf<Map<String,TaskCommandState>>(emptyMap())
        val calls = mutableListOf<String>(); var details = 0
        compose.setContent { AtollTheme {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            PageTitle("活动")
            ActivityCenterScreen(snapshot(approval()), Modifier.weight(1f), onSource = { details++ }, approvalCommands = commands.value,
                onResolve = { _, action -> calls += action })
            }
        } }
        screenshot("activity-direct-approval")
        compose.onNodeWithText("批准").assertIsDisplayed().performClick()
        compose.onNodeWithText("拒绝").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf(TaskAction.APPROVE, TaskAction.REJECT), calls); assertEquals(0, details) }
        compose.onNodeWithText("详情").performClick()
        compose.runOnIdle { assertEquals(1, details); commands.value = mapOf(approval().key to TaskCommandState("disabled", "已处理")) }
        compose.onNodeWithText("已处理").assertIsDisplayed()
        compose.onNodeWithText("等待中").assertDoesNotExist()
        compose.onNodeWithText("批准").assertDoesNotExist()
        compose.onNodeWithText("拒绝").assertDoesNotExist()
        compose.onNodeWithText("详情").assertIsDisplayed()
        screenshot("activity-approval-handled")
    }

    @Test fun readOnlyAndBusyCardsCannotSubmitAndNetworkFailureCanRetry() {
        val item = mutableStateOf(approval().copy(actions = emptyList()))
        val commands = mutableStateOf<Map<String,TaskCommandState>>(emptyMap())
        compose.setContent { AtollTheme { ActivityCenterScreen(snapshot(item.value), approvalCommands = commands.value, onResolve = { _, _ -> }) } }
        compose.onNodeWithText("批准").assertIsNotEnabled()
        compose.onNodeWithText("拒绝").assertIsNotEnabled()
        compose.runOnIdle { item.value = approval(); commands.value = mapOf(item.value.key to TaskCommandState("submitting")) }
        compose.onNodeWithText("批准").assertIsNotEnabled()
        compose.onNodeWithText("拒绝").assertIsNotEnabled()
        compose.runOnIdle { commands.value = mapOf(item.value.key to TaskCommandState("failed", error = "测试连接失败")) }
        compose.onNodeWithText("测试连接失败").assertIsDisplayed()
        compose.onNodeWithText("批准").assertIsEnabled()
        compose.onNodeWithText("拒绝").assertIsEnabled()
        compose.runOnIdle { item.value = item.value.copy(state = "completed") }
        compose.onNodeWithText("已处理").assertIsDisplayed()
        compose.onNodeWithText("批准").assertDoesNotExist()
        compose.onNodeWithText("测试连接失败").assertDoesNotExist()
    }

    @Test fun questionsKeepTheirSourceLinkAndCannotBeAnsweredWithApprovalButtons() {
        compose.setContent { AtollTheme { ActivityCenterScreen(snapshot(approval("human.ask")), onResolve = { _, _ -> error("approval cannot answer a question") }) } }
        compose.onNodeWithText("批准").assertDoesNotExist()
        compose.onNodeWithText("拒绝").assertDoesNotExist()
        compose.onNodeWithText("返回来源 ›").assertIsDisplayed()
    }

    private fun screenshot(name: String) {
        val directory = File(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?: "/sdcard/Android/media/dev.atoll.app/additional_test_output", "activity-inbox")
        check(directory.mkdirs() || directory.isDirectory)
        File(directory, "$name.png").outputStream().use {
            compose.onAllNodes(isRoot()).onLast().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}

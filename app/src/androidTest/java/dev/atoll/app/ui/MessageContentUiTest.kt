package dev.atoll.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atoll.app.ui.components.*
import dev.atoll.app.ui.screens.*
import dev.atoll.app.ui.theme.AtollTheme
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Pure view-state tests: no account, node or attachment fetch. */
@RunWith(AndroidJUnit4::class)
class MessageContentUiTest {
    @get:Rule val compose = createComposeRule()
    private val attachment = MessageAttachmentViewState("file-1", "报告.pdf", "application/pdf", 2048,
        "daemon://local-device/c0.android-test/report.pdf")

    @Composable
    private fun TestContent(content: @Composable () -> Unit) {
        AtollTheme {
            CompositionLocalProvider(LocalMarkdownCache provides MarkdownCache()) {
                Surface { Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) { content() } }
            }
        }
    }

    @Test fun bothDirectionsShowResultsTrailsSubTasksAndAttachments() {
        val self = mutableStateOf(true)
        val message = MessageViewState("message", "root", "", "12:00",
            result = ResultViewState(value = JsonPrimitive("accepted"), title = "审批回执"),
            trail = ProgressTrailViewState("已结束", listOf(ProcessRecordViewState("record", "处理完成", "12:00")), false),
            subTasks = listOf(SubTaskViewState("task", "核对文件", "worker", SubTaskKind.Agent, SubTaskPhase.Completed, 0)),
            attachments = listOf(attachment))
        compose.setContent { TestContent { MessageFrame(message.copy(self = self.value)) } }
        for (direction in listOf(true, false)) {
            compose.runOnIdle { self.value = direction }
            compose.onNodeWithText("审批回执").assertIsDisplayed()
            compose.onNodeWithText("1 条过程记录").assertIsDisplayed().performClick()
            compose.onNodeWithText("处理完成").assertIsDisplayed()
            compose.onNodeWithText("核对文件").assertIsDisplayed()
            compose.onNodeWithText("报告.pdf").assertIsDisplayed()
            compose.onNodeWithText("PDF · 2.0 KB").assertIsDisplayed()
            screenshot(if (direction) "message-self" else "message-other")
            // Restore the collapsed trail before switching bubble direction.
            compose.onNodeWithText("1 条过程记录").performClick()
        }
    }

    @Test fun previewAndDownloadDispatchDistinctAttachmentActions() {
        val actions = mutableListOf<Pair<MessageAttachmentViewState, MessageAttachmentAction>>()
        compose.setContent { TestContent { MessageFrame(MessageViewState("message", "root", "", "12:00", self = true,
            attachments = listOf(attachment)), onAttachment = { value, action -> actions += value to action }) } }
        compose.onNodeWithText("报告.pdf").performClick()
        compose.onNodeWithContentDescription("下载 报告.pdf").performClick()
        compose.runOnIdle { assertEquals(listOf(attachment to MessageAttachmentAction.Preview,
            attachment to MessageAttachmentAction.Download), actions) }
        screenshot("attachment-actions")
    }

    @Test fun legacyLinkCallbackPreviewsWithoutOfferingDownload() {
        var opened: String? = null
        compose.setContent { TestContent { MessageFrame(MessageViewState("message", "agent", "", "12:00",
            attachments = listOf(attachment)), onLink = { opened = it }) } }
        compose.onNodeWithText("报告.pdf").performClick()
        compose.runOnIdle { assertEquals(attachment.uri, opened) }
        compose.onNodeWithContentDescription("下载 报告.pdf").assertDoesNotExist()
    }

    @Test fun selfFailureAndAcknowledgementRemainVisible() {
        val failure = mutableStateOf(true)
        compose.setContent { TestContent { MessageFrame(MessageViewState("receipt", "root", "", "12:00", self = true,
            result = if (failure.value) ResultViewState(errorCode = "forbidden", detail = "测试拒绝原因")
                else ResultViewState(acknowledgement = true))) } }
        compose.onNodeWithText("无权在此发言").assertIsDisplayed()
        compose.onNodeWithText("测试拒绝原因").assertIsDisplayed()
        compose.runOnIdle { failure.value = false }
        compose.onNodeWithText("✓ 已完成").assertIsDisplayed()
    }

    @Test fun gfmTaskCheckboxesExposeTheirStateAndAreReadOnly() {
        compose.setContent { TestContent { Markdown("task-list", "- [x] 已完成任务\n- [ ] 待办任务\n- 普通列表项") } }
        compose.waitUntil(10_000) {
            compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState)).fetchSemanticsNodes().size == 2
        }
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
            .assertIsDisplayed().assertIsNotEnabled().assertHasNoClickAction()
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
            .assertIsDisplayed().assertIsNotEnabled().assertHasNoClickAction()
        compose.onNodeWithText("已完成任务").assertIsDisplayed()
        compose.onNodeWithText("待办任务").assertIsDisplayed()
        compose.onNodeWithText("普通列表项").assertIsDisplayed()
        screenshot("gfm-task-list")
    }

    private fun screenshot(name: String) {
        val directory = File(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?: "/sdcard/Android/media/dev.atoll.app/additional_test_output", "message-content")
        check(directory.mkdirs() || directory.isDirectory)
        File(directory, "$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}

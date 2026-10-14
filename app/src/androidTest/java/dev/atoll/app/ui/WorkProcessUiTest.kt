package dev.atoll.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atoll.app.ui.components.*
import dev.atoll.app.ui.screens.*
import dev.atoll.app.ui.theme.AtollTheme
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class WorkProcessUiTest {
    @get:Rule val compose = createComposeRule()
    private fun call(index: Int, conversation: Boolean = false) = RelatedCallViewState("call:$index", "任务 $index", "claude", listOf("sol-$index"),
        "12:01", MessageStatus.Completed, text = "回复摘要 $index", result = ResultViewState(JsonPrimitive("隐藏结果 $index")),
        conversation = conversation, request = "原始请求 $index")
    private val records = listOf(ProcessRecordViewState("stage", "读取配置", "12:00", body = "隐藏过程详情"),
        ProcessRecordViewState("tool", "workspace.bash", "12:01", ProcessKind.Tool,
            input = JsonPrimitive("输入参数"), outputText = "工具输出", identifiers = listOf("审计编号" to "audit:opaque")))
    private fun task(index: Int) = SubTaskViewState("task:$index", "核对文件 $index", "sol-$index", SubTaskKind.Agent, SubTaskPhase.Completed, 0,
        requestId = "task-request:$index", result = "隐藏任务详情 $index")
    private val panel get() = ProcessPanelViewState("实现过程面板", "request:opaque", "agent.ask", "completed", records,
        requestSeq = 42, terminalId = "terminal:opaque", request = MessageViewState("request:opaque", "root", "实现原生 Android 客户端", "12:00"),
        agent = AgentViewState("agent:claude", "claude"), relatedCalls = listOf(call(1)), subTasks = listOf(task(1)))

    @Test fun chipsExpandIndependentlyWithThreePlainPreviewsAndCollapseWithoutRichComposition() {
        val opened = mutableListOf<String>()
        val message = MessageViewState("answer", "claude", "完成摘要", "12:02", agent = true, status = MessageStatus.Completed,
            trail = ProgressTrailViewState("实现", records, false), relatedCalls = (1..4).map { call(it, true) } + (5..8).map { call(it) },
            subTasks = (1..4).map(::task))
        compose.setContent { AtollTheme { Surface { Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState())) {
            MessageFrame(message, onRelatedCall = { opened += it }, onMore = { opened += "root" })
        } } } }
        compose.onNodeWithText("协作 4").assertIsNotSelected()
        compose.onNodeWithText("回复摘要 1", substring = true).assertDoesNotExist()
        compose.onNodeWithText("协作 4").performClick().assertIsSelected()
        compose.onNodeWithText("子 Agent 4").performClick().assertIsSelected()
        compose.onNodeWithText("调用 4").performClick().assertIsSelected()
        compose.onNodeWithText("回复摘要 1", substring = true).assertExists()
        compose.onNodeWithText("回复摘要 4", substring = true).assertDoesNotExist()
        compose.onNodeWithText("隐藏结果 1").assertDoesNotExist()
        compose.onNodeWithText("隐藏任务详情 1").assertDoesNotExist()
        compose.onNodeWithText("隐藏过程详情").assertDoesNotExist()
        screenshot("turn-expanded")
        compose.onNodeWithText("任务 1").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("call:1"), opened) }
        compose.onNodeWithText("收起").performScrollTo().performClick()
        compose.onNodeWithText("最近两步").assertDoesNotExist()
        compose.onNodeWithText("回复摘要 1", substring = true).assertDoesNotExist()
        compose.onNodeWithText("过程 2 ›").performClick()
        compose.runOnIdle { assertEquals(listOf("call:1", "root"), opened) }
        screenshot("turn-collapsed")
    }

    @Test fun sheetComposesOnlySelectedTabAndRecordDetailsAndKeepsLedgerIdentity() {
        var collaborationCompositions = 0
        compose.setContent { AtollTheme { ProcessPanel(panel, {}, collaborationContent = {
            SideEffect { collaborationCompositions++ }; Text("协作内容槽")
        }) } }
        compose.onNodeWithText("工具输出").assertDoesNotExist()
        compose.onNodeWithText("workspace.bash").performClick()
        compose.onNodeWithText("工具输出").assertIsDisplayed()
        screenshot("process-record")
        compose.runOnIdle { assertEquals(0, collaborationCompositions) }
        compose.onNodeWithText("协作").performClick()
        compose.onNodeWithText("协作内容槽").assertIsDisplayed()
        compose.onNodeWithText("工具输出").assertDoesNotExist()
        screenshot("process-collaboration")
        compose.onNodeWithText("子任务").performClick()
        compose.onNodeWithText("核对文件 1").assertIsDisplayed()
        compose.onNodeWithText("隐藏任务详情 1").assertDoesNotExist()
        screenshot("process-tasks")
        compose.onNodeWithText("调用").performClick()
        compose.onNodeWithContentDescription("展开 关联调用 任务 1").performClick()
        compose.onNodeWithText("结构化结果").performClick()
        compose.onNodeWithText("隐藏结果 1", substring = true).assertExists()
        screenshot("process-calls")
        compose.onNodeWithText("账本").performClick()
        compose.onNodeWithText("request:opaque").assertIsDisplayed()
        compose.onNodeWithText("terminal:opaque").assertIsDisplayed()
        compose.onNodeWithText("audit:opaque").assertIsDisplayed()
        compose.onNodeWithText("协作内容槽").assertDoesNotExist()
        screenshot("process-ledger")
    }

    @Test fun terminalGuideIsDisabledAndRunningGuideUsesCallbackWhileBusyPreventsClick() {
        val state = mutableStateOf(panel)
        val busy = mutableStateOf(false)
        val guides = mutableListOf<String>()
        compose.setContent { AtollTheme { ProcessPanel(state.value, {}, onGuide = { guides += it }, controlsBusy = busy.value) } }
        compose.onNodeWithContentDescription("插话引导").assertIsNotEnabled()
        compose.runOnIdle { state.value = panel.copy(status = "processing", executionTurnId = "execution:opaque") }
        compose.onNodeWithContentDescription("插话引导").assertIsEnabled().performTextInput("更精简一点")
        compose.onNodeWithContentDescription("发送引导").performClick()
        compose.runOnIdle { busy.value = true; assertEquals(listOf("更精简一点"), guides) }
        compose.onNodeWithContentDescription("发送引导").assertIsNotEnabled()
        compose.onNodeWithContentDescription("插话引导").assertIsNotEnabled()
        compose.runOnIdle { busy.value = false; state.value = panel }
        compose.onNodeWithContentDescription("插话引导").assertIsNotEnabled()
    }

    @Test fun initialRecordAndActionSlotRemainCompatible() {
        compose.setContent { AtollTheme { ProcessPanel(panel.copy(selectedRecordId = "tool"), {}, actions = { Text("面板操作") }) } }
        compose.onNodeWithText("工具输出").assertIsDisplayed()
        compose.onNodeWithText("面板操作").assertIsDisplayed()
        screenshot("process-actions")
    }
    private fun screenshot(name: String) {
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        val directory = File(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?: "/sdcard/Android/media/dev.atoll.app/additional_test_output", "work-process")
        check(directory.mkdirs() || directory.isDirectory)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}

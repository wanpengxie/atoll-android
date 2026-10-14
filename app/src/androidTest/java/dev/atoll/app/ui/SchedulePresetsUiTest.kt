package dev.atoll.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atoll.app.tasks.*
import dev.atoll.app.ui.theme.AtollTheme
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.*

@RunWith(AndroidJUnit4::class)
class SchedulePresetsUiTest {
    @get:Rule val compose = createComposeRule()
    private val now = Instant.parse("2026-10-14T06:30:00Z")
    private val zone = ZoneId.of("Asia/Singapore")
    @Test fun defaultPresetsCustomPickerAndAdvancedPayloadKeepExistingScheduleAndCancelContract() {
        val sent = mutableListOf<Triple<Long, String, JsonObject>>()
        val cancelled = mutableListOf<String>()
        val timer = AutomationRecord("timer:opaque", "channel", 60_000, "agent.ask", buildJsonObject { put("text", "待取消的提醒") }, now.toEpochMilli())
        compose.setContent { AtollTheme { Surface { TimedActionsForm(listOf(timer), true,
            onSchedule = { delay, type, body -> sent += Triple(delay, type, body) }, onCancel = { cancelled += it },
            modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding(), clock = { now }, zone = zone,
            pickCustom = { _, selected -> selected(LocalDateTime.of(2026, 10, 16, 9, 0)) }) } } }
        compose.onNodeWithText("1 小时后").assertIsSelected()
        compose.onNodeWithText("延迟（毫秒）").assertDoesNotExist()
        compose.onNodeWithText("Payload JSON").assertDoesNotExist()
        compose.onNodeWithText("将在今天 15:30 触发").assertIsDisplayed()
        screenshot("schedule-default")
        compose.onNodeWithText("安排定时动作").performClick()
        compose.onNodeWithText("10 分钟后").performClick()
        compose.onNodeWithText("安排定时动作").performClick()
        compose.onNodeWithText("明天 09:00").performClick()
        compose.onNodeWithText("将在明天 09:00 触发").assertIsDisplayed()
        compose.onNodeWithText("安排定时动作").performClick()
        compose.onNodeWithText("自定义…").performClick()
        compose.onNodeWithText("2026-10-16 09:00").assertIsDisplayed()
        compose.onNodeWithText("安排定时动作").performClick()
        screenshot("schedule-custom")
        compose.onNodeWithText("高级：消息类型与 JSON 负载", substring = true).performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasText("Payload JSON")).performScrollTo().performTextReplacement("{\"text\":\"新提醒\",\"extra\":7}")
        compose.onNode(hasSetTextAction() and hasText("消息类型")).performScrollTo().performTextReplacement("human.note")
        compose.onNodeWithText("安排定时动作").performClick()
        screenshot("schedule-advanced")
        compose.onNodeWithTag("schedule.form.list").performScrollToNode(hasText("取消", substring = false))
        compose.onNodeWithText("取消", substring = false).performClick()
        compose.runOnIdle {
            assertEquals(listOf(3_600_000L, 600_000L, 66_600_000L, 153_000_000L, 153_000_000L), sent.map { it.first })
            assertEquals("human.note", sent.last().second)
            assertEquals(JsonPrimitive(7), sent.last().third["extra"])
            assertEquals(listOf("timer:opaque"), cancelled)
        }
    }
    @Test fun readonlyBusyMissingDateAndInvalidJsonDisableScheduling() {
        val writable = mutableStateOf(false)
        val busy = mutableStateOf(false)
        compose.setContent { AtollTheme { Surface { TimedActionsForm(emptyList(), writable.value, busy.value,
            onSchedule = { _, _, _ -> fail("invalid form submitted") }, onCancel = {},
            modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding(), clock = { now }, zone = zone, pickCustom = { _, _ -> }) } } }
        compose.onNodeWithText("安排定时动作").assertIsNotEnabled()
        compose.runOnIdle { writable.value = true; busy.value = true }
        compose.onNodeWithText("正在提交…").assertIsNotEnabled()
        compose.runOnIdle { busy.value = false }
        compose.onNodeWithText("自定义…").performClick()
        compose.onNodeWithText("请选择日期与时间").assertIsDisplayed()
        compose.onNodeWithText("安排定时动作").assertIsNotEnabled()
        compose.onNodeWithText("1 小时后").performClick()
        compose.onNodeWithText("高级：消息类型与 JSON 负载", substring = true).performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasText("Payload JSON")).performScrollTo().performTextReplacement("invalid JSON")
        compose.onNodeWithText("安排定时动作").assertIsNotEnabled()
    }
    private fun screenshot(name: String) {
        compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
        val args = InstrumentationRegistry.getArguments()
        val directory = File(args.getString("additionalTestOutputDir") ?: "/sdcard/Android/media/dev.atoll.app/additional_test_output", "schedule-presets").also { it.mkdirs() }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
}

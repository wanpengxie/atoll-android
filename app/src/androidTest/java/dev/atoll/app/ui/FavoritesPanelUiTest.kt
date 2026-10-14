package dev.atoll.app.ui

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atoll.app.files.FileReference
import dev.atoll.app.ui.screens.*
import dev.atoll.app.ui.theme.AtollTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FavoritesPanelUiTest {
    @get:Rule val compose = createComposeRule()
    private val saved = listOf(
        StarredMessageViewState("loaded", "reviewer", "今天 10:31", "审查完成，有 1 处需要处理：顶栏在 360dp 宽度下频道名被截断。", resultFields = 3),
        StarredMessageViewState("missing", "planner", "昨天 18:20", "先冻结 main，再交付 APK。"),
        StarredMessageViewState("self", "我", "周一", "所有 UI 改动都应在手机上截图验证。", agent = false, self = true))

    @Test fun starsShowProvenBadgesJumpAndRemoveOnLongPress() {
        val rows = mutableStateOf(saved); val jumps = mutableListOf<String>(); val removed = mutableListOf<String>(); var closes = 0
        compose.setContent { AtollTheme {
            FavoritesPanel("atoll-web", FavoritesTab.Starred, rows.value, emptyList(), {},
                onJump = { jumps += it; it == "loaded" }, onRemove = { id -> removed += id; rows.value = rows.value.filterNot { it.id == id } },
                onOpenFile = {}, onClose = { closes++ })
        } }
        compose.onNodeWithText("收藏").assertIsDisplayed()
        compose.onNodeWithText("# atoll-web").assertIsDisplayed()
        compose.onNodeWithText("结构化结果 · 3 个字段").assertIsDisplayed()
        screenshot("favorites-stars")
        compose.onNodeWithText(saved[1].text).performClick()
        compose.onNodeWithText("这条消息还没加载到页面上，往上翻到它附近后再点。").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, closes) }
        compose.onNodeWithText(saved[1].text).performTouchInput { longClick() }
        compose.onNodeWithText(saved[1].text).assertDoesNotExist()
        compose.onNodeWithText(saved[0].text).performClick()
        compose.runOnIdle { assertEquals(listOf("missing", "loaded"), jumps); assertEquals(listOf("missing"), removed); assertEquals(1, closes) }
    }

    @Test fun recentTabPreservesCrossChannelReferencesAndTheirLineWhenOpening() {
        val tab = mutableStateOf(FavoritesTab.Starred)
        val reference = FileReference("other", "daemon://device/ops/report.md", "report.md", line = 42)
        val opened = mutableListOf<FileReference>()
        compose.setContent { AtollTheme {
            FavoritesPanel("atoll-web", tab.value, saved, listOf(reference), { tab.value = it }, { true }, {}, { opened += it }, {},
                channelNames = mapOf("other" to "ops"))
        } }
        compose.onNodeWithText("最近阅读").performClick()
        compose.onNodeWithText("report.md").assertIsDisplayed()
        compose.onNodeWithText("# ops · 第 42 行").assertIsDisplayed()
        compose.onNodeWithText(saved[0].text).assertDoesNotExist()
        screenshot("favorites-recent")
        compose.onNodeWithText("report.md").performClick()
        compose.runOnIdle { assertEquals(listOf(reference), opened) }
        compose.onNodeWithText("星标消息 ${saved.size}").performClick()
        compose.onNodeWithText(saved[0].text).assertIsDisplayed()
    }

    @Test fun emptyTabsKeepTheirOwnExplanations() {
        val tab = mutableStateOf(FavoritesTab.Starred)
        compose.setContent { AtollTheme { FavoritesPanel("频道", tab.value, emptyList(), emptyList(), { tab.value = it }, { false }, {}, {}, {}) } }
        compose.onNodeWithText("还没有星标消息").assertIsDisplayed()
        compose.onNodeWithText("最近阅读").performClick()
        compose.onNodeWithText("尚未阅读文件").assertIsDisplayed()
    }

    private fun screenshot(name: String) {
        val directory = File(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?: "/sdcard/Android/media/dev.atoll.app/additional_test_output", "favorites")
        check(directory.mkdirs() || directory.isDirectory)
        compose.waitForIdle()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.waitForIdle(500, 3000)
        val bitmap = checkNotNull(automation.takeScreenshot())
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}

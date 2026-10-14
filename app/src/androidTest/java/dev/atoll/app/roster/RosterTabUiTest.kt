package dev.atoll.app.roster

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atoll.app.governance.*
import dev.atoll.app.ui.screens.*
import dev.atoll.app.ui.theme.AtollTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RosterTabUiTest {
    @get:Rule val compose = createComposeRule()
    private val rows = listOf(
        RosterActor("agent:builder", "builder", "agent", "class coder", "代码实现 · class coder", bound = true, deviceOnline = true),
        RosterActor("agent:reviewer", "reviewer", "agent", "class reviewer", "代码审查 · class reviewer", bound = true, deviceOnline = true),
        RosterActor("agent:planner", "planner", "agent", "class planner", "规划 · class planner", bound = true, deviceOnline = true),
        RosterActor("tool:release", "release-bot", "tool", "class release", "构建工具", bound = false),
        RosterActor("human:self", "你", "human", bound = true, deviceOnline = true),
        RosterActor("human:lin", "Lin", "human", bound = true, deviceOnline = true))
    private fun state(writable: Boolean = true) = RosterUiState(rows, current = true, selfId = "human:self", writable = writable, online = true)

    @Composable private fun Frame(members: Boolean = true, count: Int = rows.size, onTab: (ChannelTab) -> Unit = {},
        onMenu: (ChannelMenuAction) -> Unit = {}, refresh: () -> Unit = {}, content: @Composable () -> Unit) {
        AtollTheme {
            ChannelScreen(ConversationViewState(ChannelViewState("fixture", "atoll-web"),
                agents = listOf(AgentViewState("agent:builder", "builder", true), AgentViewState("agent:reviewer", "reviewer"), AgentViewState("agent:planner", "planner"))),
                ComposerViewState(), ChannelTab.Conversation, onBack = {}, onTab = onTab, onScope = {}, onActor = {}, onText = {}, onSend = {},
                onOlder = {}, onMenu = onMenu, composerVisible = false, contentOverride = content,
                membersOpen = members, memberCount = count, onRefreshMembers = refresh)
        }
    }
    @Test fun rosterOpensUnderASectionHeaderWithRefreshAndAddEntry() {
        var refreshed = 0
        var selected = ""
        var added = 0
        compose.setContent { Frame(refresh = { refreshed++ }) { RosterContent(state(), onSelect = { selected = it.id }, onAdd = { added++ }) } }
        compose.onNodeWithText("成员").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回对话").assertIsDisplayed()
        compose.onNodeWithText("Agent 与工具 · 4").assertIsDisplayed()
        compose.onNodeWithText("人 · 2").assertIsDisplayed()
        compose.onNodeWithText("你 · 我").assertIsDisplayed()
        compose.onNodeWithContentDescription("刷新名册").performClick()
        compose.onNodeWithText("builder").performClick()
        compose.onNodeWithText("添加成员").assertHeightIsAtLeast(androidx.compose.ui.unit.Dp(52f)).performClick()
        compose.runOnIdle { assertEquals(1, refreshed); assertEquals("agent:builder", selected); assertEquals(1, added) }
        compose.onNodeWithText("关闭").assertDoesNotExist()
        screenshot("members-tab")
    }
    @Test fun membersReturnToTheConversationAndReopenFromChatInfo() {
        var members by mutableStateOf(true)
        compose.setContent { Frame(members, onTab = { members = false }, onMenu = { if (it == ChannelMenuAction.Item.Members) members = true }) {
            if (members) RosterContent(state(), onSelect = {}, onAdd = {}) else Text("动态内容")
        } }
        compose.onNodeWithContentDescription("返回对话").performClick()
        compose.onNodeWithText("动态内容").assertIsDisplayed()
        compose.onNodeWithContentDescription("频道成员列表").assertDoesNotExist()
        compose.onNodeWithContentDescription("聊天信息", substring = true).performClick()
        compose.onNodeWithText("添加").performClick()
        compose.onNodeWithText("builder").assertIsDisplayed()
        compose.onNodeWithText("成员").assertIsDisplayed()
    }
    @Test fun readonlyRosterPreservesMemberNavigationAndDisablesAdd() {
        var selected = ""
        compose.setContent { Frame { RosterContent(state(false), onSelect = { selected = it.id }, onAdd = { error("read only") }) } }
        compose.onNodeWithText("添加成员").assertIsNotEnabled()
        compose.onNodeWithText("reviewer").performClick()
        compose.runOnIdle { assertEquals("agent:reviewer", selected) }
    }
    @Test fun addMemberSubmitRemainsVisibleAndClickableWithKeyboardOpen() {
        val writes = mutableListOf<GovernanceCommand>()
        val port = object : GovernancePort {
            override suspend fun execute(channel: String, command: GovernanceCommand, id: String, accepted: () -> Unit): JsonObject {
                writes += command; accepted(); return JsonObject(emptyMap())
            }
            override suspend fun directory(channel: String) = GovernanceDirectory()
            override suspend fun channels(parent: String) = emptyList<JsonObject>()
            override suspend fun resource(channel: String, payload: JsonObject) = JsonObject(emptyMap())
        }
        val vm = GovernanceViewModel(port, "root", MutableSharedFlow())
        vm.context("fixture", "human:self", true, setOf("fixture"))
        var adding by mutableStateOf(false)
        var keyboard = 0
        try {
            compose.setContent { Frame {
                val ime = WindowInsets.ime.getBottom(LocalDensity.current)
                SideEffect { keyboard = ime }
                Box(Modifier.fillMaxSize()) {
                    RosterContent(state(), onSelect = {}, onAdd = { adding = true })
                    if (adding) AddMemberOverlay({ adding = false }) { AddMemberForm(vm) }
                }
            } }
            compose.onNodeWithText("添加成员").performClick()
            compose.onNodeWithText("参与者：请选择").performClick()
            compose.onNodeWithText("直接按 Class 新建…").performClick()
            val field = compose.onNode(hasSetTextAction() and hasText("Class"))
            field.performClick(); field.performTextReplacement("echo")
            compose.waitUntil(5_000) { keyboard > 0 }
            compose.onNodeWithText("添加到频道").performScrollTo().assertIsDisplayed().assertIsEnabled()
            screenshot("members-add-keyboard")
            compose.onNodeWithText("添加到频道").performClick()
            compose.waitUntil(5_000) { writes.size == 1 }
            compose.runOnIdle {
                assertTrue("Submit must happen with the keyboard open", keyboard > 0)
                assertEquals("system.member.create", writes.single().word)
                assertEquals(JsonPrimitive("echo"), writes.single().payload["body"]?.jsonObject?.get("class"))
            }
        } finally { vm.close() }
    }
    private fun screenshot(name: String) {
        compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
        android.os.SystemClock.sleep(350)
        val folder = File(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?: "/sdcard/Android/media/dev.atoll.app/additional_test_output", "roster-tab").also { it.mkdirs() }
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}

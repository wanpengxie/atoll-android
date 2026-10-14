package dev.atoll.app.ui

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

/** Read-only production navigation: neither closing path sends a member command. */
@RunWith(AndroidJUnit4::class)
class MemberNavigationNodeUiTest {
    private val args = InstrumentationRegistry.getArguments()
    private val server = args.getString("atollUrl").also { require(it == "http://100.99.232.10:18080") }!!
    private val channel = args.getString("atollChannelId").orEmpty().also { require(it.matches(Regex("[a-f0-9-]{36}"))) }
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun waitNode(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        compose.waitUntil(40_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        return compose.onAllNodes(matcher).onFirst()
    }
    @Test fun closeAndSystemBackBothRestoreTheMembersTab() {
        val graph = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AtollApp).graph
        compose.runOnIdle { graph.login(LoginViewState(server, "root", args.getString("atollPassword").orEmpty())) }
        compose.waitUntil(40_000) { graph.services.value?.server == server && graph.state.value.identityConfirmed }
        compose.runOnIdle { graph.openChannel(channel) }
        compose.waitUntil(40_000) { graph.state.value.route?.channel == channel }
        waitNode(hasText("成员") and hasClickAction()).performClick()
        waitNode(hasContentDescription("频道成员列表"))
        fun verifyMembers() {
            waitNode(hasContentDescription("频道成员列表"))
            compose.onNode(hasText("成员") and hasClickAction()).assertIsSelected()
            val focus = RouteFocus.parse(graph.state.value.route?.focus, graph.state.value.route?.tab.orEmpty())
            check(focus?.kind == FocusKind.Panel && focus.key == WorkspacePanel.Members.key)
        }
        waitNode(hasText("test-codex") and hasClickAction()).performClick()
        waitNode(hasContentDescription("返回成员")).performClick()
        verifyMembers()
        waitNode(hasText("test-codex") and hasClickAction()).performClick()
        waitNode(hasContentDescription("返回成员"))
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        verifyMembers()
    }
}

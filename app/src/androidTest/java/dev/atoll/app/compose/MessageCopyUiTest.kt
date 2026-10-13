package dev.atoll.app.compose

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.atoll.app.core.Envelope
import dev.atoll.app.ui.components.ResultViewState
import dev.atoll.app.ui.screens.MessageViewState
import dev.atoll.app.ui.theme.AtollTheme
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Test copy affordances in isolation: no login, actor or app navigation. */
@RunWith(AndroidJUnit4::class)
class MessageCopyUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun buttonCopiesStreamingTextAndCompleteTerminalJson() = checkCopy(longPress = false)
    @Test fun longPressCopiesStreamingTextAndCompleteTerminalJson() = checkCopy(longPress = true)

    private fun checkCopy(longPress: Boolean) {
        var copied: AnnotatedString? = null
        val clipboard = object : ClipboardManager {
            override fun getText(): AnnotatedString? = copied
            override fun setText(annotatedString: AnnotatedString) { copied = annotatedString }
        }
        val message = MessageViewState("copy-fixture", "agent", "流式正文", "", result =
            ResultViewState(buildJsonObject { put("answer", 42); put("complete", true) }))
        val envelope = Envelope(buildJsonObject {
            put("id", message.id); put("channel_id", "fixture"); put("kind", "response")
            put("type", "agent.ask"); put("sender", buildJsonObject { put("id", "agent:fixture") })
        })
        compose.setContent {
            AtollTheme {
                CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                    TimelineMessageActions(message, envelope, null, "self", emptyList(), false, false,
                        false, emptySet(), onReply = {}, onEdit = {}) { Text(message.text) }
                }
            }
        }
        if (longPress) compose.onAllNodesWithText(message.text, useUnmergedTree = true).onLast().performTouchInput { longClick() }
        else compose.onNodeWithText("复制").performClick()
        compose.onNodeWithText("✓ 已复制").assertExists()
        compose.runOnIdle {
            assertEquals("流式正文\n\n```json\n{\n  \"answer\": 42,\n  \"complete\": true\n}\n```", copied?.text)
        }
    }
}

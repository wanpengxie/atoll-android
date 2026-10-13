package dev.atoll.app.compose

import dev.atoll.app.ui.components.ResultViewState
import dev.atoll.app.ui.screens.MessageViewState
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class MessageCopyTextTest {
    private fun message(text: String = "", result: ResultViewState? = null) =
        MessageViewState("id", "agent", text, "", result = result)
    private fun copiedJson(message: MessageViewState): JsonElement {
        val text = messageCopyText(message)
        assertTrue(text.startsWith("```json\n")); assertTrue(text.endsWith("\n```"))
        return Json.parseToJsonElement(text.removePrefix("```json\n").removeSuffix("\n```"))
    }

    @Test fun existingMarkdownIsPreservedAndOnlyOuterWhitespaceIsTrimmed() {
        val text = "  # Title\n\n```kotlin\nval x = 1\n```\n  "
        assertEquals(text.trim(), messageCopyText(message(text, ResultViewState(buildJsonObject { put("ignored", true) }))))
    }

    @Test fun structuredResultIncludesAllRowsAndKeepsNestedTypes() {
        val value = buildJsonObject {
            put("items", buildJsonArray { repeat(75) { add(it) } })
            put("nested", buildJsonObject { put("null", JsonNull); put("boolean", true); put("string", "a\nb") })
        }
        assertEquals(value, copiedJson(message(result = ResultViewState(value))))
    }

    @Test fun arraysAndExplicitNullAreCopyableResults() {
        for (value in listOf(buildJsonArray { add(1); add("two") }, JsonNull))
            assertEquals(value, copiedJson(message(result = ResultViewState(value))))
    }

    @Test fun errorsCopyCodeDetailAndCompleteBusinessDataWithoutTheirDisplayTitle() {
        val data = buildJsonObject { put("data", buildJsonObject { put("retryable", false) }); put("target", "request-id") }
        val expected = buildJsonObject { data.forEach { (k, v) -> put(k, v) }; put("error_code", "cas_mismatch"); put("detail", "hold changed") }
        assertEquals(expected, copiedJson(message(result = ResultViewState(title = "display-only", errorCode = "cas_mismatch", detail = "hold changed", errorData = data))))
        assertEquals(buildJsonObject { put("error_code", "cancelled") }, copiedJson(message(result = ResultViewState(errorCode = "cancelled"))))
    }

    @Test fun emptyTextAndAcknowledgementDoNotInventClipboardContent() {
        for (result in listOf(null, ResultViewState(emptyText = true), ResultViewState(acknowledgement = true)))
            assertEquals("", messageCopyText(message(" \n ", result)))
    }
}

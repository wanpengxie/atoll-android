package dev.atoll.app.compose

import dev.atoll.app.ui.screens.MessageViewState
import kotlinx.serialization.json.*

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
private val clipboardJson = Json { prettyPrint = true; prettyPrintIndent = "  " }

/** The mapped content, including the full JSON rather than the tree's visible page. */
internal fun messageCopyText(message: MessageViewState): String {
    message.text.trim().takeIf(String::isNotEmpty)?.let { return it }
    val result = message.result ?: return ""
    val value = if (result.errorCode != null) buildJsonObject {
        // Keep the machine-readable error and all business data together.
        when (val data = result.errorData) {
            is JsonObject -> data.forEach { (key, value) -> put(key, value) }
            null -> Unit
            else -> put("data", data)
        }
        put("error_code", result.errorCode)
        if (result.detail.isNotEmpty()) put("detail", result.detail)
    } else result.value ?: return "" // Empty text and bare acknowledgements have no body to copy.
    // Match web's textOf JSON presentation and keep Markdown/code-fence copy behavior.
    return "```json\n${clipboardJson.encodeToString(JsonElement.serializer(), value)}\n```"
}

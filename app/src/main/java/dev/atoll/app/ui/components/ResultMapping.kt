package dev.atoll.app.ui.components

import dev.atoll.app.core.Envelope
import dev.atoll.app.core.string
import dev.atoll.app.model.ConversationVisibility
import kotlinx.serialization.json.*

internal data class MappedResult(val text: String = "", val result: ResultViewState? = null)

internal fun resultBusiness(body: JsonObject): JsonObject = JsonObject(body.filterKeys {
    it !in ConversationVisibility.terminalTransportFields && it !in setOf("attachments", "files")
        && !(it == "content" && content(body).any { block -> block["type"].string() in setOf("text", "resource_link") })
})

/** Match web StructuredResult; protocol bookkeeping never becomes business JSON. */
internal fun terminalResult(envelope: Envelope, requestType: String): MappedResult {
    val body = envelope.body
    val business = resultBusiness(body)
    if (envelope.status == "failed") {
        val code = if (body["cancelled"] == JsonPrimitive(true)) "cancelled" else body["error_code"].string().ifEmpty { body["reason"].string() }
        return MappedResult(result = ResultViewState(errorCode = code, detail = body["detail"].string(),
            errorData = business.takeIf { it.isNotEmpty() }))
    }
    if ("text" in body) {
        val text = body["text"].string()
        if (text.isEmpty()) return if (hasAttachments(body)) MappedResult() else MappedResult(result = ResultViewState(emptyText = true))
        val parsed = if (text.trimStart().firstOrNull() in listOf('{', '[')) runCatching { Json.parseToJsonElement(text) }.getOrNull() else null
        return if (parsed is JsonObject || parsed is JsonArray) MappedResult(result = ResultViewState(parsed, "JSON 结果")) else MappedResult(text)
    }
    if (content(body).any { it["type"].string() in setOf("text", "resource_link") })
        return MappedResult(content(body).filter { it["type"].string() == "text" }.map { it["text"].string().trim() }.filter(String::isNotEmpty).joinToString("\n\n"))
    if (business.isNotEmpty()) {
        val title = if (requestType == "actor.describe" || "words" in body && "class" in body)
            body["class"].string().takeIf(String::isNotEmpty)?.let { "$it 的能力" } ?: "Actor 能力" else "结构化结果"
        return MappedResult(result = ResultViewState(business, title))
    }
    return if (hasAttachments(body)) MappedResult() else MappedResult(result = ResultViewState(acknowledgement = true))
}

private fun content(body: JsonObject) = (body["content"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
private fun hasAttachments(body: JsonObject): Boolean =
    (body["attachments"] as? JsonArray ?: body["files"] as? JsonArray).orEmpty().any { it is JsonObject } ||
        content(body).any { it["type"].string() == "resource_link" && it["uri"].string().isNotBlank() }

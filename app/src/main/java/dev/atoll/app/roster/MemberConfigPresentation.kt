package dev.atoll.app.roster

import kotlinx.serialization.json.*

private val memberPrettyJson = Json { prettyPrint = true }

internal data class MemberConfigField(val path: String, val value: JsonPrimitive, val source: String)

/** Existing configuration is the schema: never fabricate a provider's keys or model catalog. */
internal fun memberConfigFields(info: JsonObject, editor: MemberEditor?): List<MemberConfigField> {
    val edited = editor?.takeIf { it.kind == "config" }?.let { runCatching { MemberConfig.parse(it.text) }.getOrNull() }
    val values = if (edited == null) info.obj("effective") else mergeConfigValues(info.obj("effective"), edited)
    val sources = MemberConfig.sourceRows(info).associate { it.path to it.label }
    val fields = mutableListOf<MemberConfigField>()
    fun visit(value: JsonObject, prefix: String) {
        value.forEach { (key, child) ->
            val path = if (prefix.isEmpty()) key else "$prefix.$key"
            when (child) {
                is JsonObject -> visit(child, path)
                is JsonPrimitive -> if (child != JsonNull && key != "default") fields += MemberConfigField(path, child, sources[path].orEmpty())
                else -> Unit // Structured values remain fully editable through the JSON tab.
            }
        }
    }
    visit(values, "")
    return fields
}
internal fun mergeConfigValues(before: JsonObject, overlay: JsonObject): JsonObject = JsonObject(before.toMutableMap().apply {
    overlay.forEach { (key, value) ->
        if (value == JsonNull) remove(key)
        else put(key, if (value is JsonObject) mergeConfigValues(before.obj(key), value) else value)
    }
})
internal fun editedMemberValue(editor: MemberEditor, path: String, value: JsonPrimitive): MemberEditor {
    val original = MemberConfig.parse(editor.text)
    val next = mergeConfigValues(original, MemberConfig.patchAtPath(path, value))
    val text = memberPrettyJson.encodeToString(JsonObject.serializer(), next)
    return editor.copy(text = text, selectionStart = text.length, selectionEnd = text.length)
}
internal fun memberEditorChange(state: RosterUiState): MemberChange? {
    val actor = state.selected ?: return null
    val editor = state.editor ?: return null
    return runCatching {
        if (editor.kind == "entry") MemberConfig.entryChange(actor.memberId, editor.info, editor.sourceMode, editor.reference, editor.text, editor.requires)
        else MemberConfig.configChange(actor.memberId, editor.info, editor.host, editor.text)
    }.getOrNull()
}

package dev.atoll.app.roster

import dev.atoll.app.core.string
import kotlinx.serialization.json.*

data class MemberSource(val path: String, val layer: String, val label: String, val value: JsonElement?)
data class Placeholder(val key: String, val hint: String)
data class ReferenceInsertion(val text: String, val cursor: Int)
data class MemberChange(val word: String, val payload: JsonObject) {
    val changed: Boolean get() = payload.keys.any { it != "member" }
}

/** Web member-config.js: JSON editors produce minimal RFC 7396 patches. */
object MemberConfig {
    private val labels = mapOf("default" to "Class 默认值", "actor" to "Actor 描述", "member" to "成员条目", "config" to "这一台的配置")
    fun editable(kind: String) = kind == "agent" || kind == "tool"
    fun sameJSON(left: JsonElement?, right: JsonElement?): Boolean = when {
        left == null || left == JsonNull -> right == null || right == JsonNull
        left is JsonObject && right is JsonObject -> left.keys == right.keys && left.all { sameJSON(it.value, right[it.key]) }
        left is JsonArray && right is JsonArray -> left.size == right.size && left.indices.all { sameJSON(left[it], right[it]) }
        left is JsonPrimitive && right is JsonPrimitive && !left.isString && !right.isString && left.doubleOrNull != null && right.doubleOrNull != null -> left.double == right.double
        else -> left == right
    }
    fun mergePatch(before: JsonElement?, after: JsonElement?): JsonObject {
        val previous = before as? JsonObject ?: empty
        val next = after as? JsonObject ?: empty
        return buildJsonObject {
            for ((key, value) in next) {
                val old = previous[key]
                if (key !in previous) put(key, value)
                else if (old is JsonObject && value is JsonObject) mergePatch(old, value).takeIf { it.isNotEmpty() }?.let { put(key, it) }
                else if (!sameJSON(old, value)) put(key, value)
            }
            previous.keys.filter { it !in next }.forEach { put(it, JsonNull) }
        }
    }
    fun patchAtPath(path: String, value: JsonElement): JsonObject {
        val keys = path.split('.').filter(String::isNotEmpty)
        require(keys.isNotEmpty()) { "键路径为空" }
        return keys.asReversed().fold(value) { child, key -> buildJsonObject { put(key, child) } } as JsonObject
    }
    fun valueAtPath(value: JsonElement?, path: String): JsonElement? = path.split('.').filter(String::isNotEmpty).fold(value) { child, key -> (child as? JsonObject)?.get(key) }
    fun sourceRows(detail: JsonObject?): List<MemberSource> = detail?.obj("sources")?.entries?.sortedBy { it.key }?.map { (path, layer) ->
        val name = layer.string()
        MemberSource(path, name, labels[name] ?: name, valueAtPath(detail["effective"], path))
    }.orEmpty()
    fun parse(text: String): JsonObject {
        val value = try { Json.parseToJsonElement(text.trim().ifEmpty { "{}" }) }
        catch (failure: Exception) { throw IllegalArgumentException("配置 JSON 格式无效：${failure.message}") }
        require(value is JsonObject) { "配置必须是 JSON 对象" }
        return value
    }
    fun globalName(name: String) = name.matches(Regex("[a-z0-9_-]{1,64}"))
    fun globalReferences(value: JsonElement?): Set<String> {
        val names = linkedSetOf<String>()
        fun visit(current: JsonElement?) {
            when (current) {
                is JsonPrimitive -> if (current.isString && current.content.startsWith("\$global.")) current.content.removePrefix("\$global.").takeIf(::globalName)?.let(names::add)
                is JsonArray -> current.forEach(::visit)
                is JsonObject -> current.values.forEach(::visit)
                else -> Unit
            }
        }
        visit(value); return names.toSet()
    }
    fun missingGlobals(value: JsonElement?, existing: Collection<String>) = (globalReferences(value) - existing.toSet()).sorted().map { "global/$it" }
    fun insertGlobal(text: String, start: Int?, end: Int?, name: String): ReferenceInsertion {
        require(globalName(name)) { "全局 key 名称无效" }
        val from = (start ?: text.length).coerceIn(0, text.length)
        val to = (end ?: from).coerceIn(from, text.length)
        val literal = JsonPrimitive("\$global.$name").toString()
        return ReferenceInsertion(text.replaceRange(from, to, literal), from + literal.length)
    }
    fun rosterBodyLabel(body: String) = if (body == "human") "人" else body
    fun bodyLabel(body: JsonElement?): String {
        val value = body as? JsonObject ?: return ""
        return when { value.bool("human") == true -> "人"; value.text("actor").isNotEmpty() -> "Actor 描述 ${value.text("actor")}"; value.text("class").isNotEmpty() -> "Class ${value.text("class")}"; else -> "" }
    }
    fun missing(detail: JsonObject?): List<Placeholder> = detail?.array("missing")?.mapNotNull { raw ->
        val row = raw as? JsonObject ?: return@mapNotNull null
        row.text("key").takeIf { it.isNotEmpty() }?.let { Placeholder(it, row.text("hint")) }
    }.orEmpty()
    fun entryChange(member: String, info: JsonObject, mode: String, reference: String, paramsText: String, requiresText: String): MemberChange {
        require(member.isNotBlank()) { "成员 ID 为空" }; require(mode in setOf("actor", "class"))
        val ref = reference.trim()
        val body = if (ref.isNotEmpty()) buildJsonObject { put(mode, ref) } else null
        val patch = mergePatch(info["params"], parse(paramsText))
        val requires = JsonArray(requiresText.split(Regex("[\\s,，]+")).filter(String::isNotEmpty).map(::JsonPrimitive))
        return MemberChange("system.member.set", buildJsonObject {
            put("member", member)
            if (body != null && !sameJSON(body, info["body"] ?: empty)) put("body", body)
            if (patch.isNotEmpty()) put("params", patch)
            if (!sameJSON(requires, info["requires"] ?: JsonArray(emptyList()))) put("requires", if (requires.isEmpty()) JsonNull else requires)
        })
    }
    fun configChange(member: String, info: JsonObject, host: String, valuesText: String): MemberChange {
        val own = info.obj("own_config")
        val patch = mergePatch(own["values"], parse(valuesText))
        return MemberChange("system.member.config.set", buildJsonObject {
            put("member", member)
            if (host != own.text("desired_host")) put("desired_host", host)
            if (patch.isNotEmpty()) put("values", patch)
        })
    }
    val empty = JsonObject(emptyMap())
}
internal fun JsonObject.text(key: String) = get(key).string()
internal fun JsonObject.obj(key: String) = get(key) as? JsonObject ?: MemberConfig.empty
internal fun JsonObject.array(key: String) = get(key) as? JsonArray ?: JsonArray(emptyList())
internal fun JsonObject.bool(key: String) = (get(key) as? JsonPrimitive)?.booleanOrNull
internal fun JsonObject.number(key: String) = (get(key) as? JsonPrimitive)?.longOrNull ?: 0L

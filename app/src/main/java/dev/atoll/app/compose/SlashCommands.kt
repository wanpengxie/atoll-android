package dev.atoll.app.compose

import kotlinx.serialization.json.*

class ComposerFailure(val code: String, detail: String) : IllegalArgumentException(detail)
data class SlashDefinition(val name: String, val type: String, val scope: String = "agent", val label: String, val usage: String = "/$name", val minArgs: Int = 0, val maxArgs: Int = 0, val schema: JsonObject? = null)
sealed interface ParsedCommand {
    data class Escaped(val text: String) : ParsedCommand
    data class Command(val definition: SlashDefinition, val payload: JsonObject) : ParsedCommand
}
object SlashCommands {
    val static = listOf(
        SlashDefinition("compact", "agent.compact", label = "压缩上下文"),
        SlashDefinition("new", "agent.new", label = "新建对话"),
        SlashDefinition("restart", "system.member.restart", "system-target", "重启 Agent"),
        SlashDefinition("model", "agent.select", label = "切换模型", usage = "/model [model] [effort]", maxArgs = 2),
        SlashDefinition("fork", "agent.fork", label = "分叉对话"),
        SlashDefinition("context", "agent.context", label = "查看上下文"),
        SlashDefinition("status", "actor.describe", label = "查看状态"),
        SlashDefinition("introduce", "system.member.create", "system", "创建成员", "/introduce <class 或 actor:id[@v]> [name]", 1, 2),
        SlashDefinition("admit", "system.member.admit", "system", "准入用户", "/admit <principal>", 1, 1),
        SlashDefinition("members", "system.member.list", "system", "成员列表"),
        SlashDefinition("channels", "system.channel.list", "system", "频道列表", "/channels [parent]", maxArgs = 1),
    )
    fun definitions(manifest: JsonObject?): List<SlashDefinition> {
        val words = manifest?.get("words") as? JsonObject ?: return static
        val reserved = static.map { it.name }.toSet()
        val dynamic = words.mapNotNull { (type, value) ->
            if (!type.startsWith("agent.")) return@mapNotNull null
            val name = type.removePrefix("agent.")
            if (!Regex("[a-z][a-z0-9._-]*").matches(name) || name in reserved) return@mapNotNull null
            val word = value as? JsonObject ?: return@mapNotNull null
            if (!requestAllowed(word)) return@mapNotNull null
            val schema = (word["input_schema"] ?: word["inputSchema"]) as? JsonObject ?: return@mapNotNull null
            if (CommandSchema.definitionError(schema) != null) return@mapNotNull null
            SlashDefinition(name, type, label = word.text("description").ifEmpty { name }, usage = "/$name <JSON payload>", schema = schema)
        }.sortedBy { it.name }
        return static + dynamic
    }
    fun requestAllowed(word: JsonObject): Boolean {
        val kinds = word["allowedKinds"] ?: word["allowed_kinds"] ?: return true
        return kinds is JsonArray && kinds.any { (it as? JsonPrimitive)?.content == "request" }
    }
    fun parse(value: String, definitions: List<SlashDefinition> = static): ParsedCommand? {
        val text = value.trim()
        if (!text.startsWith('/')) return null
        if (text.startsWith("//")) return ParsedCommand.Escaped(text.drop(1))
        val tokens = text.split(Regex("\\s+"))
        val name = tokens.first().drop(1)
        val definition = definitions.find { it.name == name } ?: throw ComposerFailure("composer_command_unknown", "未知命令 /$name；普通正文以 / 开头时请写成 //$name")
        val args = tokens.drop(1)
        val schema = definition.schema
        if (schema != null) {
            val raw = text.drop(tokens.first().length).trim()
            val payload = if (raw.isEmpty()) JsonObject(emptyMap()) else try { Json.parseToJsonElement(raw) as? JsonObject ?: throw IllegalArgumentException() } catch (_: Exception) { throw ComposerFailure("composer_command_payload_invalid", "命令 /$name 的 payload 必须是 JSON 对象") }
            val error = CommandSchema.definitionError(schema) ?: CommandSchema.valueError(payload, schema)
            if (error != null) throw ComposerFailure("composer_command_payload_invalid", error)
            return ParsedCommand.Command(definition, payload)
        }
        if (args.size !in definition.minArgs..definition.maxArgs) throw ComposerFailure("composer_command_usage", "用法：${definition.usage}")
        val payload = buildJsonObject {
            when (name) {
                "model" -> { args.getOrNull(0)?.let { put("model", it) }; args.getOrNull(1)?.let { put("effort", it) } }
                "introduce" -> { put("body", buildJsonObject { if (args[0].startsWith("actor:")) put("actor", args[0].drop(6)) else put("class", args[0]) }); args.getOrNull(1)?.let { put("name", it) } }
                "admit" -> put("principal", args[0])
                "channels" -> args.firstOrNull()?.let { put("parent_id", it) }
            }
        }
        return ParsedCommand.Command(definition, payload)
    }
    fun frame(channelId: String, id: String, command: ParsedCommand.Command, draft: ComposerDraft, target: ComposerActor?, online: Boolean, manifest: JsonObject?): JsonObject {
        if (draft.reply != null) throw ComposerFailure("composer_command_reply", "回复模式下不能使用斜杠命令，请先取消回复")
        if (draft.attachments.isNotEmpty()) throw ComposerFailure("composer_command_attachments", "斜杠命令不能携带附件，请先移除附件")
        if (!online) throw ComposerFailure("composer_command_offline", "连接可用后才能发送命令")
        val definition = command.definition
        if (definition.scope != "system" && target == null) throw ComposerFailure("composer_command_no_target", "请先选择目标 Agent")
        // protocol/message/describe.go: actor.describe is answered by the standard
        // actor layer before business readiness and is not a manifest word.
        if (definition.scope == "agent" && definition.type != "actor.describe" && manifest != null) {
            val word = (manifest["words"] as? JsonObject)?.get(definition.type) as? JsonObject
            if (word == null || !requestAllowed(word)) throw ComposerFailure("composer_command_unsupported", "Agent 不支持 ${definition.type}")
        }
        if (definition.name == "model" && command.payload.text("model").isEmpty()) throw ComposerFailure("composer_model_picker", "请选择模型和推理强度")
        val payload = if (definition.scope == "system-target") JsonObject(command.payload + ("member" to JsonPrimitive(target!!.id))) else command.payload
        return buildJsonObject {
            put("channel_id", channelId); put("id", id); put("kind", "request"); put("msg_type", definition.type)
            put("visibility", "public"); put("audience", JsonArray(listOf(JsonPrimitive(if (definition.scope.startsWith("system")) "system" else target!!.id))))
            put("payload", payload)
        }
    }
}

/** Web command port's deliberately bounded JSON-schema subset, not a general validator. */
object CommandSchema {
    private val keys = setOf("type", "oneOf", "anyOf", "allOf", "enum", "const", "properties", "required", "additionalProperties", "items", "minLength", "maxLength", "minimum", "maximum", "title", "description")
    private val types = setOf("object", "array", "string", "integer", "number", "boolean", "null")
    fun definitionError(schema: JsonObject, root: Boolean = true): String? {
        if (schema.keys.any { it !in keys }) return "inputSchema 包含不支持的字段"
        val type = schema.text("type")
        if (type !in types || root && type != "object") return "inputSchema 必须声明 ${if (root) "object" else "支持的"} 类型"
        for (key in listOf("oneOf", "anyOf", "allOf")) if (key in schema) {
            val branches = schema[key] as? JsonArray ?: return "$key 必须是 schema 数组"
            if (branches.isEmpty()) return "$key 必须非空"
            for (branch in branches) { val child = branch as? JsonObject ?: return "$key 必须包含对象"; definitionError(child, false)?.let { return it } }
        }
        if ("enum" in schema && (schema["enum"] !is JsonArray || schema.array("enum").isEmpty())) return "enum 必须是非空数组"
        for (key in listOf("title", "description")) if (key in schema && (schema[key] as? JsonPrimitive)?.isString != true) return "$key 必须是字符串"
        if (type == "object") {
            val required = schema["required"]
            if (required != null && (required !is JsonArray || required.any { (it as? JsonPrimitive)?.isString != true } || required.distinct().size != required.size)) return "required 必须是无重复字符串数组"
            val props = schema["properties"]
            if (props != null && props !is JsonObject) return "properties 必须是对象"
            for (child in (props as? JsonObject)?.values.orEmpty()) { if (child !is JsonObject) return "properties 必须声明 schema"; definitionError(child, false)?.let { return it } }
            if ("additionalProperties" in schema && schema["additionalProperties"] != JsonPrimitive(false)) return "additionalProperties 仅支持 false"
        } else if (listOf("properties", "required", "additionalProperties").any { it in schema }) return "object 字段与类型不匹配"
        if (type == "array") {
            val child = schema["items"] as? JsonObject ?: return "items 必须声明 schema"
            definitionError(child, false)?.let { return it }
        } else if ("items" in schema) return "items 仅支持 array"
        for ((low, high) in listOf("minLength" to "maxLength", "minimum" to "maximum")) {
            if (low !in schema && high !in schema) continue
            if (low == "minLength" && type != "string" || low == "minimum" && type !in setOf("integer", "number")) return "$low 与类型不匹配"
            val min = (schema[low] as? JsonPrimitive)?.doubleOrNull
            val max = (schema[high] as? JsonPrimitive)?.doubleOrNull
            for (key in listOf(low, high)) if (key in schema) {
                val value = schema[key] as? JsonPrimitive ?: return "$key 必须是数字"
                val number = value.doubleOrNull
                if (value.isString || number == null || !number.isFinite() || low == "minLength" && (number < 0 || number % 1 != 0.0)) return "$key 必须是有效数字"
            }
            if (min != null && max != null && min > max) return "$low 不能大于 $high"
        }
        return null
    }
    fun valueError(value: JsonElement, schema: JsonObject, path: String = "payload"): String? {
        for (key in listOf("oneOf", "anyOf")) if (key in schema && schema.array(key).none { valueError(value, it.jsonObject, path) == null }) return "$path 不匹配 $key"
        for (branch in schema.array("allOf")) valueError(value, branch.jsonObject, path)?.let { return it }
        if ("const" in schema && value != schema["const"]) return "$path 不符合 const"
        if ("enum" in schema && value !in schema.array("enum")) return "$path 不在 enum 中"
        val primitive = value as? JsonPrimitive
        when (schema.text("type")) {
            "object" -> {
                if (value !is JsonObject) return "$path 必须是对象"
                val missing = schema.array("required").map { it.jsonPrimitive.content }.firstOrNull { it !in value }
                if (missing != null) return "$path.$missing 是必填字段"
                val props = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
                for ((key, child) in props) value[key]?.let { valueError(it, child.jsonObject, "$path.$key") }?.let { return it }
                if (schema["additionalProperties"] == JsonPrimitive(false) && value.keys.any { it !in props }) return "$path 包含未声明字段"
            }
            "array" -> { if (value !is JsonArray) return "$path 必须是数组"; for ((index, item) in value.withIndex()) valueError(item, schema.getValue("items").jsonObject, "$path[$index]")?.let { return it } }
            "string" -> if (primitive?.isString != true) return "$path 必须是字符串"
            "integer" -> if (primitive == null || primitive.isString || primitive.longOrNull == null) return "$path 必须是整数"
            "number" -> if (primitive == null || primitive.isString || primitive.doubleOrNull == null) return "$path 必须是数字"
            "boolean" -> if (primitive == null || primitive.isString || primitive.booleanOrNull == null) return "$path 必须是布尔值"
            "null" -> if (value != JsonNull) return "$path 必须是 null"
        }
        if (primitive?.isString == true) {
            if ("minLength" in schema && primitive.content.length < schema.number("minLength")) return "$path 长度不足"
            if ("maxLength" in schema && primitive.content.length > schema.number("maxLength")) return "$path 长度超限"
        } else primitive?.doubleOrNull?.let { number ->
            if ("minimum" in schema && number < schema.getValue("minimum").jsonPrimitive.double) return "$path 小于 minimum"
            if ("maximum" in schema && number > schema.getValue("maximum").jsonPrimitive.double) return "$path 大于 maximum"
        }
        return null
    }
}

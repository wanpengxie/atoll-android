package dev.atoll.app.tasks

import dev.atoll.app.core.*
import dev.atoll.app.model.TimelineSource
import dev.atoll.app.model.Turn
import kotlinx.serialization.json.*

/** One property layer, matching web json-schema-form.js. Nested objects use a JSON editor. */
data class FormField(
    val name: String, val type: String, val control: String, val label: String, val description: String = "",
    val required: Boolean = false, val secret: Boolean = false, val enum: List<JsonElement>? = null,
    val default: JsonElement? = null, val examples: List<JsonElement> = emptyList(), val pattern: String = "",
    val minLength: Int? = null, val maxLength: Int? = null, val minimum: Double? = null, val maximum: Double? = null,
)
data class UiForm(val id: String, val channelId: String, val envelope: Envelope, val requester: String, val session: String, val title: String, val schema: JsonObject, val values: JsonObject, val secret: Map<String,String>, val fields: List<FormField>)
data class FormValidation(val values: JsonObject, val errors: Map<String,String>) { val valid get() = errors.isEmpty() }
data class UiRequestScan(val open: List<Envelope>, val closed: Set<String>)
class InvalidForm(detail: String) : IllegalArgumentException(detail)
fun schemaFields(schema: JsonObject, secret: Set<String> = emptySet()): List<FormField> {
    val properties = schema["properties"] as? JsonObject ?: return emptyList()
    val required = schema.array("required").map { (it as? JsonPrimitive)?.contentOrNull }.toSet()
    return properties.mapNotNull { (name,value) ->
        val p = value as? JsonObject ?: return@mapNotNull null
        if (name.isEmpty()) return@mapNotNull null
        val declared = (p["type"] as? JsonArray)?.firstOrNull { (it as? JsonPrimitive)?.contentOrNull != "null" } ?: p["type"]
        val enums = (p["enum"] as? JsonArray)?.toList()
        val first = enums?.firstOrNull { it != JsonNull } as? JsonPrimitive
        val type = (declared as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() } ?: when {
            first?.booleanOrNull != null && !first.isString -> "boolean"
            first?.doubleOrNull != null && !first.isString -> if (first.double % 1.0 == 0.0) "integer" else "number"
            else -> "string"
        }
        val isSecret = name in secret
        val control = when {
            !enums.isNullOrEmpty() && !isSecret -> "select"
            type == "boolean" -> "checkbox"
            type in setOf("integer","number") -> "number"
            type in setOf("object","array") -> "json"
            isSecret -> "password"
            else -> "text"
        }
        FormField(name,type,control,p.text("title").ifEmpty { name },p.text("description"),name in required,isSecret,enums,p["default"],p.array("examples").toList(),p.text("pattern"),
            (p["minLength"] as? JsonPrimitive)?.intOrNull,(p["maxLength"] as? JsonPrimitive)?.intOrNull,(p["minimum"] as? JsonPrimitive)?.doubleOrNull,(p["maximum"] as? JsonPrimitive)?.doubleOrNull)
    }
}
fun initialFormValues(fields: List<FormField>, prefill: JsonObject = Envelope.EMPTY): Map<String,JsonElement> = fields.associate { field ->
    val value = if (field.name in prefill) prefill[field.name] else field.default
    field.name to when {
        value == null || value == JsonNull -> if (field.control == "checkbox") JsonPrimitive(false) else JsonPrimitive("")
        field.control == "checkbox" -> JsonPrimitive(value == JsonPrimitive(true))
        field.control in setOf("select","json") -> JsonPrimitive(value.toString())
        else -> JsonPrimitive((value as? JsonPrimitive)?.content ?: value.toString())
    }
}
fun validateFormValues(fields: List<FormField>, raw: Map<String,JsonElement>): FormValidation {
    val values = linkedMapOf<String,JsonElement>();val errors = linkedMapOf<String,String>()
    for (field in fields) {
        val input = raw[field.name]; val text = (input as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (field.control != "checkbox" && (input == null || input == JsonNull || text.trim().isEmpty())) {
            if (field.required) errors[field.name] = "必填"
            continue
        }
        when (field.control) {
            "checkbox" -> values[field.name] = JsonPrimitive(input == JsonPrimitive(true))
            "select" -> {
                val selected = field.enum?.firstOrNull { it.toString() == text }
                if (selected == null) errors[field.name] = "请选择一个可选值" else values[field.name] = selected
            }
            "number" -> {
                val number = text.trim().toDoubleOrNull()
                val error = when {
                    number == null || !number.isFinite() -> "请输入数字"
                    field.type == "integer" && number % 1.0 != 0.0 -> "请输入整数"
                    field.minimum != null && number < field.minimum -> "不能小于 ${field.minimum}"
                    field.maximum != null && number > field.maximum -> "不能大于 ${field.maximum}"
                    else -> ""
                }
                if (error.isNotEmpty()) errors[field.name] = error
                else values[field.name] = if (field.type == "integer") JsonPrimitive(number!!.toLong()) else JsonPrimitive(number!!)
            }
            "json" -> {
                val json = runCatching { Json.parseToJsonElement(text) }.getOrNull()
                val error = when { json == null -> "JSON 格式无效"; field.type == "object" && json !is JsonObject -> "必须是 JSON 对象"; field.type == "array" && json !is JsonArray -> "必须是 JSON 数组"; else -> "" }
                if (error.isNotEmpty()) errors[field.name] = error else values[field.name] = json!!
            }
            else -> {
                val value = if (field.secret) text else text.trim()
                val pattern = field.pattern.takeIf { it.isNotEmpty() }?.let { runCatching { Regex(it) }.getOrNull() }
                val error = when {
                    field.minLength != null && value.length < field.minLength -> "至少 ${field.minLength} 个字符"
                    field.maxLength != null && value.length > field.maxLength -> "最多 ${field.maxLength} 个字符"
                    pattern != null && !pattern.containsMatchIn(value) -> "格式不符合要求"
                    !field.enum.isNullOrEmpty() && JsonPrimitive(value) !in field.enum -> "不是可选值之一"
                    else -> ""
                }
                if (error.isNotEmpty()) errors[field.name] = error else values[field.name] = JsonPrimitive(value)
            }
        }
    }
    return FormValidation(JsonObject(values),errors)
}
fun clientUiRequests(source: TimelineSource, selfId: String, now: Long): UiRequestScan = clientUiRequests(timelineTurns(source.timeline),selfId,now)
internal fun clientUiRequests(turns: List<Turn>, selfId: String, now: Long): UiRequestScan {
    if (selfId.isEmpty()) return UiRequestScan(emptyList(),emptySet())
    val selected = turns.filter { it.request.type == "ui.form" && it.request.kind == "request" && selfId in it.request.audience }
    val (closed,open) = selected.partition { it.terminal != null || it.request.expiresAt > 0 && it.request.expiresAt <= now }
    return UiRequestScan(open.map { it.request },closed.map { it.requestId }.toSet())
}
fun uiRequestAddressing(envelope: Envelope, sessionId: String): String {
    val named=envelope.body.text("session")
    return if (named.isEmpty()) "unaddressed" else if (sessionId.isNotEmpty() && named == sessionId) "mine" else "other"
}
fun parseUiForm(request: Envelope): UiForm {
    val body = request.body;val schema = body["schema"] as? JsonObject ?: throw InvalidForm("ui.form schema must be a JSON Schema object with properties")
    val properties = schema["properties"] as? JsonObject ?: throw InvalidForm("ui.form schema must have properties")
    if (schema["type"] != null && schema.text("type") != "object") throw InvalidForm("ui.form schema must describe an object")
    if (body["values"] != null && body["values"] !is JsonObject) throw InvalidForm("ui.form values must be an object")
    if (body["secret"] != null && body["secret"] !is JsonObject) throw InvalidForm("ui.form secret must map field names to global/<name>")
    val secret = body.objectAt("secret").mapValues { (name,value) ->
        val property = properties[name] as? JsonObject ?: throw InvalidForm("ui.form secret names $name, which is not a schema property")
        if (property["type"] != null && property.text("type") != "string") throw InvalidForm("ui.form secret field $name must be a string")
        val resource = (value as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        if (!Regex("^global/[a-z0-9_-]{1,64}$").matches(resource)) throw InvalidForm("ui.form secret $name must name global/<name>")
        resource
    }
    val fields = schemaFields(schema,secret.keys);if (fields.isEmpty()) throw InvalidForm("ui.form schema has no fields")
    return UiForm(request.id,request.channelId,request,request.senderId,body.text("session"),body.text("title").trim().ifEmpty { "填写表单" },schema,body.objectAt("values"),secret,fields)
}
fun uiFormResult(form: UiForm, values: JsonObject): JsonObject = buildJsonObject {
    put("values",JsonObject(values.filterKeys { it !in form.secret }))
    put("secret",buildJsonObject { values.forEach { (name,value) -> form.secret[name]?.let { resource ->
        val text = (value as? JsonPrimitive)?.contentOrNull.orEmpty()
        put(name,buildJsonObject { put("resource",resource);put("masked",if (text.length > 4) "****${text.takeLast(4)}" else "****") })
    } } })
}
fun uiResolveResult(envelope: Envelope, result: JsonObject): JsonObject = buildJsonObject { put("channel_id",envelope.channelId);put("req_id",envelope.id);put("result",result) }
fun uiResolveError(envelope: Envelope, code: String, message: String): JsonObject = buildJsonObject { put("channel_id",envelope.channelId);put("req_id",envelope.id);put("error",buildJsonObject { put("code",code);put("message",message) }) }
suspend fun answerUiForm(form: UiForm, values: JsonObject, writeSecret: suspend(String,String)->Unit, resolve: suspend(JsonObject)->Unit): JsonObject {
    for ((name,resource) in form.secret) {
        val value = values[name] ?: continue
        require(value is JsonPrimitive && value.isString) { "全局 key 的值必须是字符串" }
        writeSecret(resource,value.content)
    }
    return uiFormResult(form,values).also { resolve(uiResolveResult(form.envelope,it)) }
}

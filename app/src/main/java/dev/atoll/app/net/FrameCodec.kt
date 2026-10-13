package dev.atoll.app.net

import dev.atoll.app.core.*
import kotlinx.serialization.json.*

class FrameValidationException(detail: String) : IllegalArgumentException(detail) {
    val code = "bad_payload"
}
class FrameVersionException(val received: JsonElement?) : Exception("Frame version $received is incompatible with $FRAME_VERSION")

/** Pure v5 codec; field rules mirror atoll-web/src/protocol/frame.js. */
object FrameCodec {
    private val fields = mapOf(
        Up.ATTACH to "since focus history_protocol generation label",
        Up.SUBMIT to "channel_id id msg_type kind payload audience visibility parent_id expires_at_ms",
        Up.RESOLVE to "channel_id req_id text decision note result error",
        Up.CANCEL to "channel_id req_id",
        Up.AFTER to "channel_id duration_ms msg_type payload",
        Up.CANCEL_TIMER to "channel_id timer_id",
        Up.RESOURCE to "channel_id op resource_id args target ops query address with_content node_type",
        Up.OBSERVE to "channel_id", Up.UNOBSERVE to "channel_id",
        Up.CHANNEL_META to "channel_id generation",
        Up.HISTORY_BEFORE to "channel_id before_seq limit byte_limit generation purpose priority",
        Up.HISTORY_CANCEL to "channel_id target_ref generation",
    ).mapValues { it.value.split(' ').toSet() }
    private val required = mapOf(
        Up.ATTACH to "focus history_protocol generation", Up.SUBMIT to "channel_id msg_type",
        Up.RESOLVE to "channel_id req_id", Up.CANCEL to "channel_id req_id",
        Up.AFTER to "channel_id duration_ms msg_type", Up.CANCEL_TIMER to "channel_id timer_id",
        Up.RESOURCE to "channel_id op", Up.OBSERVE to "channel_id", Up.UNOBSERVE to "channel_id",
        Up.CHANNEL_META to "channel_id generation",
        Up.HISTORY_BEFORE to "channel_id generation purpose priority",
        Up.HISTORY_CANCEL to "channel_id target_ref generation",
    ).mapValues { it.value.split(' ') }

    fun encode(type: String, ref: String, payload: JsonObject): String {
        validate(type, payload)
        val value = buildJsonObject {
            put("v", FRAME_VERSION); put("frame_type", type)
            if (ref.isNotEmpty()) put("ref", ref)
            put("payload", payload)
        }.toString()
        if (value.toByteArray(Charsets.UTF_8).size > MAX_FRAME_BYTES) invalid("frame exceeds 512KB")
        return value
    }

    fun validate(type: String, payload: JsonObject) {
        val allowed = fields[type] ?: invalid("unknown upstream frame_type: $type")
        val unknown = payload.keys - allowed
        if (unknown.isNotEmpty()) invalid("$type payload has unknown field: ${unknown.sorted().joinToString()}")
        val missing = required.getValue(type).filter { payload[it] == null || payload[it] == JsonNull }
        if (missing.isNotEmpty()) invalid("$type payload is missing: ${missing.joinToString()}")
        fun integer(key: String, low: Long, high: Long = 9_007_199_254_740_991L, optional: Boolean = false) {
            if (optional && (payload[key] == null || payload[key] == JsonNull)) return
            val p = payload[key] as? JsonPrimitive
            val n = p?.takeUnless { it.isString }?.doubleOrNull
            if (n == null || !n.isFinite() || n % 1.0 != 0.0 || n < low.toDouble() || n > high.toDouble()) invalid("$type $key must be an integer between $low and $high")
        }
        when (type) {
            Up.AFTER -> integer("duration_ms", 1)
            Up.ATTACH -> {
                if (!isVersion(payload["history_protocol"])) invalid("attach history_protocol must be $FRAME_VERSION")
                integer("generation", 1)
            }
            Up.CHANNEL_META, Up.HISTORY_CANCEL -> {
                integer("generation", 1)
                if (type == Up.HISTORY_CANCEL && payload["target_ref"].string().isEmpty()) invalid("history_cancel target_ref is required")
            }
            Up.HISTORY_BEFORE -> {
                integer("before_seq", 0, optional = true); integer("limit", 1, 200, true)
                integer("byte_limit", 1, 4 * 1024 * 1024, true); integer("generation", 1)
                if (payload["purpose"].string() !in setOf("initial-tail", "user-demand", "hydrate")) invalid("history_before purpose is invalid")
                if (payload["priority"].string() !in setOf("foreground", "background")) invalid("history_before priority is invalid")
            }
            Up.RESOURCE -> {
                val op = payload["op"].string()
                if (op !in setOf("create", "read", "write", "delete", "stat", "list")) invalid("resource op is invalid")
                val id = payload["resource_id"].string().isNotEmpty()
                val address = payload["address"].string().isNotEmpty()
                if (op == "create" && !id && !address) invalid("resource create requires resource_id or address")
                if (op in setOf("read", "write", "delete", "stat") && !id) invalid("resource $op requires resource_id")
                val content = payload["with_content"]
                if (content != null && content != JsonNull && ((content as? JsonPrimitive)?.isString != false || (content as? JsonPrimitive)?.booleanOrNull == null)) invalid("resource with_content must be a boolean")
                val node = payload["node_type"]
                if (node != null && node != JsonNull) {
                    if (node.string() !in setOf("regular", "directory")) invalid("resource node_type is invalid")
                    if (op != "create" || !address) invalid("resource node_type is valid only for address create")
                    if (node.string() == "directory" && content == JsonPrimitive(true)) invalid("resource directory cannot carry content")
                }
            }
        }
    }

    fun decode(text: String): DownFrame? = decode(Json.parseToJsonElement(text).jsonObject)
    fun decode(value: JsonObject): DownFrame? {
        if (!isVersion(value["v"])) throw FrameVersionException(value["v"])
        val ref = value["ref"].string()
        val p = value["payload"] as? JsonObject ?: Envelope.EMPTY
        return when (value["frame_type"].string()) {
            "receipt" -> DownFrame.Receipt(ref, p)
            "error" -> DownFrame.Error(ref, p["frame"].string(), p["code"].string().ifEmpty { "unknown" }, p["detail"].string())
            "feed" -> DownFrame.Feed(ref, Row(p["channel_id"].string(), p.long("seq"), Envelope(p.getValue("envelope").jsonObject)), p.long("generation"))
            "checkpoint" -> DownFrame.Checkpoint(p["channel_id"].string(), p.long("scan_low_seq"), p.long("scanned_seq"), p.long("generation"))
            "page_end" -> DownFrame.PageEnd(ref, p["channel_id"].string(), p)
            "observe_ended" -> DownFrame.ObserveEnded(p["channel_id"].string(), p["reason"].string())
            "memberships" -> DownFrame.Memberships(p)
            else -> null
        }
    }
    private fun isVersion(value: JsonElement?) = (value as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull == FRAME_VERSION.toDouble()
    private fun invalid(detail: String): Nothing = throw FrameValidationException(detail)
}

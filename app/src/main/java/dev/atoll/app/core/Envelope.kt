package dev.atoll.app.core

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * One ledger message, as the node sends it. Backed by its JSON so that no
 * field the node adds is lost on the way to the cache or the screen; typed
 * accessors cover what the app reads.
 *
 * Business fields live in payload.body ([body]); a payload without a body is
 * historical flat data and exposes no business fields (atoll-web argsOf).
 */
class Envelope(val json: JsonObject) {
    val id: String get() = str("id")
    val ts: Long get() = long("ts")
    val tsReceived: Long get() = long("ts_received")
    val channelId: String get() = str("channel_id")
    val kind: String get() = str("kind")
    val type: String get() = str("type")
    val parentId: String get() = str("parent_id")
    val correlationId: String get() = str("correlation_id").ifEmpty { id }
    val visibility: String get() = str("visibility").ifEmpty { Visibility.PUBLIC }
    val expiresAt: Long get() = long("expires_at")
    val senderId: String get() = (json["sender"] as? JsonObject)?.get("id").string()
    val senderKind: String get() = (json["sender"] as? JsonObject)?.get("kind").string()
    val audience: List<String>
        get() = (json["audience"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it.string().ifEmpty { null } } ?: emptyList()

    /** payload.body, or empty when the payload is not canonical. */
    val body: JsonObject
        get() {
            val payload = json["payload"] as? JsonObject ?: return EMPTY
            return payload["body"] as? JsonObject ?: EMPTY
        }
    val hasBody: Boolean get() = (json["payload"] as? JsonObject)?.containsKey("body") == true

    /** body.status, the lifecycle word of a response. */
    val status: String get() = body["status"].string()
    val isTerminal: Boolean get() = kind == Kind.RESPONSE && status in Status.FINAL

    private fun str(key: String) = json[key].string()
    private fun long(key: String) = (json[key] as? JsonPrimitive)?.longOrNull ?: 0L

    override fun equals(other: Any?) = other is Envelope && other.json == json
    override fun hashCode() = json.hashCode()
    override fun toString() = "Envelope($kind $type $id)"

    companion object {
        val EMPTY = JsonObject(emptyMap())
    }
}

/** A ledger row: one envelope at its channel-local sequence number. */
data class Row(val channelId: String, val seq: Long, val envelope: Envelope)

fun JsonElement?.string(): String = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: ""

object Kind {
    const val REQUEST = "request"
    const val RESPONSE = "response"
    const val EVENT = "event"
}

object Visibility {
    const val PUBLIC = "public"
    const val SYSTEM = "system"
}

object Status {
    val FINAL = setOf("completed", "failed")
    val PROVISIONAL = setOf("received", "queued", "processing", "deferred", "unavailable")
}

object SenderKind {
    const val HUMAN = "human"
    const val AGENT = "agent"
    const val TOOL = "tool"
    const val SYSTEM = "system"
}

/** Where a batch of rows came from. */
enum class Origin { CACHE, HISTORY, LIVE }

/**
 * Rows of one channel handed from sync to the model, in ascending seq within
 * the batch. [olderThanWindow] marks rows paged in below what the model holds
 * (the reader scrolled up); otherwise they are at or above its window.
 */
data class RowBatch(
    val channelId: String,
    val rows: List<Row>,
    val origin: Origin,
    val olderThanWindow: Boolean = false,
)

package dev.atoll.app.compose

import dev.atoll.app.core.*
import dev.atoll.app.net.actorRequest
import kotlinx.serialization.*
import kotlinx.serialization.json.*

@Serializable data class ComposerActor(val id: String, val name: String, val kind: String, val body: String = "")
@Serializable data class ReplyTarget(val sourceId: String, val senderId: String, val senderName: String, val excerpt: String = "")
typealias ComposerAttachment = dev.atoll.app.files.FileReference
@Serializable data class ComposerDraft(
    @Transient val text: String = "",
    val recipients: List<ComposerActor> = emptyList(),
    val attachments: List<ComposerAttachment> = emptyList(),
    val reply: ReplyTarget? = null,
    val manualAgentId: String = "",
)
@Serializable enum class SendState { @SerialName("queued") QUEUED, @SerialName("transmitting") TRANSMITTING, @SerialName("accepted") ACCEPTED, @SerialName("delayed") DELAYED, @SerialName("uncertain") UNCERTAIN, @SerialName("rejected") REJECTED }
@Serializable data class LocalSubmission(
    val messageId: String, val channelId: String, val frame: JsonObject,
    val text: String, val targetLabel: String,
    val state: SendState = SendState.QUEUED, val createdAt: Long, val updatedAt: Long = createdAt,
    val error: String = "", val cancelRequested: Boolean = false,
) {
    val expiresAt: Long get() = frame.number("expires_at_ms")
}
interface OutboxRepository {
    suspend fun restore(): List<LocalSubmission>
    suspend fun putMany(rows: List<LocalSubmission>)
    suspend fun remove(messageId: String)
    suspend fun restoreDrafts(): Map<String, ComposerDraft>
    suspend fun putDraft(channelId: String, draft: ComposerDraft)
}
fun interface ActorQueries {
    suspend fun request(channelId: String, actorId: String, type: String, payload: JsonObject): JsonObject
}
class WireActorQueries(private val wire: WireClient) : ActorQueries {
    override suspend fun request(channelId: String, actorId: String, type: String, payload: JsonObject) = wire.actorRequest(channelId, actorId, type, payload)
}
fun interface AttachmentUploader {
    suspend fun upload(channelId: String, file: UploadFile): ComposerAttachment
}
/** Opening a source rather than retaining its bytes permits large file uploads. */
data class UploadFile(val name: String, val size: Long, val mediaType: String, val open: () -> java.io.InputStream)

internal fun JsonObject.text(key: String) = get(key).string()
internal fun JsonObject.number(key: String) = (get(key) as? JsonPrimitive)?.longOrNull ?: 0L
internal fun JsonObject.flag(key: String) = (get(key) as? JsonPrimitive)?.booleanOrNull == true
internal fun JsonObject.array(key: String) = get(key) as? JsonArray ?: JsonArray(emptyList())

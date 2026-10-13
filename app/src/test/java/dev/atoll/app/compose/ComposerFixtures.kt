package dev.atoll.app.compose

import dev.atoll.app.core.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

internal val AGENT = ComposerActor("agent:a:1", "agent", "agent")
internal val OTHER = ComposerActor("agent:b:1", "other", "agent")
internal val HUMAN = ComposerActor("human:x:1", "human", "human")
internal fun json(vararg fields: Pair<String, JsonElement>) = JsonObject(fields.toMap())
internal fun text(value: String) = JsonPrimitive(value)
internal fun submission(id: String = "message", state: SendState = SendState.QUEUED, expires: Long = 0): LocalSubmission {
    val frame = ComposerDelivery.messageFrames("c0", ComposerDraft(text = "hello"), Delivery(listOf(AGENT), "only"), { id }, expires).single()
    return LocalSubmission(id, "c0", frame, "hello", "agent", state, createdAt = 1)
}
internal class MemoryOutbox : OutboxRepository {
    val rows = linkedMapOf<String, LocalSubmission>()
    val drafts = linkedMapOf<String, ComposerDraft>()
    var rejectWrites = false
    override suspend fun restore() = rows.values.toList()
    override suspend fun putMany(rows: List<LocalSubmission>) { if (rejectWrites) error("disk unavailable"); rows.forEach { this.rows[it.messageId] = Json.decodeFromString(Json.encodeToString(it)) } }
    override suspend fun remove(messageId: String) { rows.remove(messageId) }
    override suspend fun restoreDrafts() = drafts.toMap()
    override suspend fun putDraft(channelId: String, draft: ComposerDraft) { drafts[channelId] = Json.decodeFromString(Json.encodeToString(draft)) }
}
internal class FakeComposerWire : WireClient {
    override val state = MutableStateFlow<WireState>(WireState.Disconnected("offline", 0))
    override val frames = MutableSharedFlow<DownFrame>()
    data class Call(val type: String, val frame: JsonObject, val result: CompletableDeferred<JsonObject> = CompletableDeferred())
    val calls = mutableListOf<Call>()
    override suspend fun request(type: String, payload: JsonObject): JsonObject = Call(type, payload).also(calls::add).result.await()
    override suspend fun history(payload: JsonObject) = error("unused")
    fun attach(generation: Long = 1, session: String = "session-1") { state.value = WireState.Attached(generation, json("session" to text(session), "label" to text("Android"))) }
    fun disconnect() { state.value = WireState.Disconnected("offline", 0) }
}

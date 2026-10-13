package dev.atoll.app.model

import dev.atoll.app.core.Envelope
import dev.atoll.app.core.Row
import dev.atoll.app.core.string
import java.util.Collections
import kotlinx.serialization.json.*

internal fun freezeJson(value: JsonElement): JsonElement = when (value) {
    is JsonObject -> JsonObject(frozenMap(value.mapValues { freezeJson(it.value) }))
    is JsonArray -> JsonArray(frozen(value.map(::freezeJson)))
    else -> value
}
internal fun <T> frozen(values: Collection<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
internal fun <K, V> frozenMap(values: Map<K, V>): Map<K, V> = Collections.unmodifiableMap(LinkedHashMap(values))
internal fun JsonObject.text(key: String) = get(key).string()
internal fun JsonObject.number(key: String) = (get(key) as? JsonPrimitive)?.longOrNull ?: 0L
internal fun JsonObject.flag(key: String) = (get(key) as? JsonPrimitive)?.booleanOrNull == true
internal fun JsonObject.array(key: String) = get(key) as? JsonArray ?: JsonArray(emptyList())

/** Immutable ledger-derived turn. Only the first terminal in sequence order wins. */
data class Turn(
    val request: Envelope,
    val requestSeq: Long,
    val provisional: List<Row> = emptyList(),
    val terminal: Envelope? = null,
    val terminalSeq: Long = 0,
    val lastSeq: Long = requestSeq,
    val terminalClosureOnly: Boolean = false,
) {
    val requestId: String get() = request.id
    val status: String get() = terminal?.status ?: "pending"
    val latestCoreStatus: String get() = provisional.lastOrNull { it.envelope.status.isNotEmpty() }?.envelope?.status ?: ""
}

/** All descendants of a root request are one flat thread, matching the web fold. */
data class TimelineEntry(
    val kind: String,
    val seq: Long,
    val turn: Turn? = null,
    val envelope: Envelope? = null,
    val thread: List<TimelineEntry> = emptyList(),
    val lastSeq: Long = seq,
    val subTasks: List<Envelope> = emptyList(),
    val subTaskRevision: String = "",
    val local: Boolean = false,
) {
    val id: String get() = turn?.requestId ?: envelope?.id?.ifEmpty { null } ?: "$kind:$seq"
    fun envelopes(): List<Envelope> = if (turn == null) listOfNotNull(envelope) else
        listOfNotNull(turn.request, turn.terminal) + turn.provisional.map { it.envelope } + thread.flatMap { it.envelopes() }
}

/** Synchronous projection inputs. A live implementation stays private to the replica. */
interface TimelineSource {
    val channelId: String
    val revision: Long
    val projectionVersion: Long
    val lastSeq: Long
    val rows: Collection<Row>
    val timeline: Collection<TimelineEntry>
    val narration: Collection<Row>
    val envelopesById: Map<String, Envelope>
}

data class ProjectionFacts(
    val relatedIds: Set<String>?,
    val restarts: Map<String, Long>,
    val taskSteps: List<Envelope>,
    val runningTasks: List<BackgroundTask>,
)

data class ReplicaSnapshot(
    override val channelId: String,
    override val revision: Long,
    override val projectionVersion: Long,
    override val lastSeq: Long,
    override val rows: List<Row>,
    override val timeline: List<TimelineEntry>,
    override val narration: List<Row>,
    /** Includes compact closure request placeholders, to suppress stale local echoes. */
    override val envelopesById: Map<String, Envelope>,
) : TimelineSource

data class ModelChange(val accepted: Int, val changedIds: Set<String>, val structureChanged: Boolean, val projectionVersion: Long)

data class PresentationItem(
    val key: String,
    val entry: TimelineEntry,
    val seqLow: Long,
    val seqHigh: Long,
    val actorId: String,
    val timestamp: Long,
    val dayKey: String,
    val boundaryAfterTimestamp: Long,
    val continuation: Boolean,
    val layoutClass: String,
    val settled: Boolean,
    val contentRevision: String,
)

data class TimelineSnapshot(
    val channelId: String,
    val revision: Long,
    val items: List<PresentationItem>,
    val waitingTurns: List<Turn>,
    val runningTasks: List<BackgroundTask>,
    val firstVisibleSeq: Long,
    val lastVisibleSeq: Long,
    val changes: PresentationChanges = PresentationChanges(),
    val currentEntryCandidate: CurrentEntryCandidate? = null,
)

data class CurrentEntryCandidate(val id: String, val seqHigh: Long, val local: Boolean)
data class PresentationChanges(
    val kind: String = "empty",
    val frontInsertedIds: List<String> = emptyList(),
    val backInsertedIds: List<String> = emptyList(),
    val inserted: List<String> = emptyList(),
    val updated: List<String> = emptyList(),
    val removed: List<String> = emptyList(),
)

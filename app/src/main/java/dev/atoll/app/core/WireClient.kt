package dev.atoll.app.core

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

/** Where the wire is. */
sealed interface WireState {
    data object Connecting : WireState
    /** Attached: the receipt of this generation's attach. */
    data class Attached(val generation: Long, val receipt: JsonObject) : WireState
    data class Disconnected(val reason: String, val retryInMs: Long) : WireState
    /** The node speaks another frame version; the app cannot talk to it. */
    data class Incompatible(val detail: String) : WireState
    data object SignedOut : WireState
}

/**
 * The one connection to the node. Implemented by net.Wire; faked in tests of
 * the layers above it.
 */
interface WireClient {
    val state: StateFlow<WireState>
    val frames: SharedFlow<DownFrame>

    /**
     * Sends one upstream frame and suspends until its receipt; throws
     * [WireException] on an error frame, a timeout, or a dropped connection.
     */
    suspend fun request(type: String, payload: JsonObject): JsonObject

    /**
     * Sends one history_before and suspends until its page_end, returning the
     * rows the node sent for it in order. Rows of a page never appear on
     * [frames]; live rows always do.
     */
    suspend fun history(payload: JsonObject): HistoryPage
}

/** One history page: its rows (ascending seq) and the page_end payload. */
data class HistoryPage(val rows: List<Row>, val end: JsonObject) {
    val scanLowSeq: Long get() = end.long("scan_low_seq")
    val scanHighSeq: Long get() = end.long("scan_high_seq")
    val headSeq: Long get() = end.long("head_seq")
    val nextBeforeSeq: Long get() = end.long("next_before_seq")
    val hasOlder: Boolean get() = (end["has_older"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
}

internal fun JsonObject.long(key: String): Long =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: 0L

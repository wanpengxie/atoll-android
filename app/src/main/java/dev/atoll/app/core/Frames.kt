package dev.atoll.app.core

import kotlinx.serialization.json.JsonObject

/** Wire frame version this app speaks (atoll-web src/protocol/frame.js FRAME_VERSION). */
const val FRAME_VERSION = 5
const val MAX_FRAME_BYTES = 512 * 1024

/** Upstream frame types. */
object Up {
    const val ATTACH = "attach"
    const val SUBMIT = "submit"
    const val RESOLVE = "resolve"
    const val CANCEL = "cancel"
    const val AFTER = "after"
    const val CANCEL_TIMER = "cancel_timer"
    const val RESOURCE = "resource"
    const val OBSERVE = "observe"
    const val UNOBSERVE = "unobserve"
    const val CHANNEL_META = "channel_meta"
    const val HISTORY_BEFORE = "history_before"
    const val HISTORY_CANCEL = "history_cancel"
}

/** A downstream frame, decoded. Unknown frame types are dropped by the wire. */
sealed interface DownFrame {
    /** Answers the upstream frame with this [ref]. */
    data class Receipt(val ref: String, val payload: JsonObject) : DownFrame

    /** A refusal; [ref] names the upstream frame when it answers one. */
    data class Error(val ref: String, val frame: String, val code: String, val detail: String) : DownFrame

    /** A ledger row: live (ref empty) or part of the history page answering [ref]. */
    data class Feed(val ref: String, val row: Row) : DownFrame

    /** Live coverage: the node scanned [scanLowSeq]..[scannedSeq] of the channel. */
    data class Checkpoint(val channelId: String, val scanLowSeq: Long, val scannedSeq: Long, val generation: Long) : DownFrame

    /** The end of the history page answering [ref]. */
    data class PageEnd(val ref: String, val channelId: String, val payload: JsonObject) : DownFrame

    data class ObserveEnded(val channelId: String, val reason: String) : DownFrame

    /** The full membership list again, after it changed on the node. */
    data class Memberships(val payload: JsonObject) : DownFrame
}

/** A refused or failed upstream frame. */
class WireException(val frame: String, val code: String, detail: String, val ref: String = "") :
    Exception(detail.ifEmpty { code })

package dev.atoll.app.app

import dev.atoll.app.tasks.taskCreationSource
import kotlinx.serialization.json.JsonObject

internal fun timelineTaskSource(channel: String, target: TimelineMessageTarget): JsonObject? {
    val turn = target.turn
    val seq = turn?.requestSeq ?: target.seq
    if (seq <= 0) return null // Local echoes/compact placeholders do not have a navigable ledger row.
    return taskCreationSource(channel, turn?.requestId ?: target.envelope.id, seq, turn?.requestId.orEmpty())
}

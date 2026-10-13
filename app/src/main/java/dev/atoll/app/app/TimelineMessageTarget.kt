package dev.atoll.app.app

import dev.atoll.app.core.Envelope
import dev.atoll.app.model.TimelineSnapshot
import dev.atoll.app.model.TimelineEntry
import dev.atoll.app.model.Turn

data class TimelineMessageTarget(val envelope: Envelope, val turn: Turn?)

/** Resolve the rendered key structurally; actor/message IDs may themselves contain colons. */
fun timelineMessageTarget(snapshot: TimelineSnapshot, key: String): TimelineMessageTarget? {
    fun visit(entry: TimelineEntry, prefix: String): TimelineMessageTarget? {
        val turn = entry.turn
        if (turn == null) return entry.envelope?.takeIf { key == "$prefix:message" }?.let { TimelineMessageTarget(it, null) }
        if (key == "$prefix:request") return TimelineMessageTarget(turn.request, turn)
        if (key == "$prefix:answer") return (turn.terminal ?: turn.provisional.lastOrNull()?.envelope)?.let { TimelineMessageTarget(it, turn) }
        return entry.thread.firstNotNullOfOrNull { visit(it, "$prefix:${it.id}") }
    }
    return snapshot.items.firstNotNullOfOrNull { visit(it.entry, it.key) }
}

fun timelineMessageTargets(snapshot: TimelineSnapshot): Map<String, TimelineMessageTarget> {
    val targets = linkedMapOf<String, TimelineMessageTarget>()
    fun visit(entry: TimelineEntry, prefix: String) {
        val turn = entry.turn
        if (turn == null) entry.envelope?.let { targets["$prefix:message"] = TimelineMessageTarget(it, null) }
        else {
            targets["$prefix:request"] = TimelineMessageTarget(turn.request, turn)
            (turn.terminal ?: turn.provisional.lastOrNull()?.envelope)?.let { targets["$prefix:answer"] = TimelineMessageTarget(it, turn) }
            entry.thread.forEach { visit(it, "$prefix:${it.id}") }
        }
    }
    snapshot.items.forEach { visit(it.entry, it.key) }
    return targets
}

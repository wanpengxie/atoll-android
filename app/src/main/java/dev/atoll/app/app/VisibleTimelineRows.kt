package dev.atoll.app.app

import dev.atoll.app.model.TimelineEntry
import dev.atoll.app.model.TimelineSnapshot

/** A visible request does not imply that its later, off-screen answer was read. */
internal fun timelineRowSequences(snapshot: TimelineSnapshot): Map<String, Long> = buildMap {
    fun visit(entry: TimelineEntry, key: String) {
        if (entry.local) return
        val turn = entry.turn
        if (turn == null) {
            put(key, entry.seq) // Narration/weak notice.
            put("$key:message", entry.seq)
        } else {
            put("$key:request", turn.requestSeq)
            val answer = if (turn.terminal != null) turn.terminalSeq else turn.provisional.lastOrNull()?.seq
            if (answer != null && answer > 0) put("$key:answer", answer)
            entry.thread.forEach { visit(it, "$key:${it.id}") }
        }
    }
    snapshot.items.forEach { visit(it.entry, it.key) }
}

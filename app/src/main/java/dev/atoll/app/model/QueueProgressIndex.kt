package dev.atoll.app.model

import java.util.TreeSet

/** Counts each started human request once. Only a tenth-latest boundary change
 * invalidates older waiting rows; later progress on that request is a no-op.
 */
internal class QueueProgressIndex {
    private data class Started(val actorId: String, val seq: Long)
    private val byRequest = mutableMapOf<String, Started>()
    private val starts = mutableMapOf<String, TreeSet<Long>>()
    private val boundaries = mutableMapOf<String, Long>()
    private var cached = emptyMap<String, Long>()
    private var dirty = false

    fun note(turn: Turn): Boolean {
        val actor = turn.request.audience.firstOrNull().orEmpty()
        val next = if (actor.isNotEmpty() && turn.request.senderKind == "human" &&
            turn.provisional.any { it.envelope.status == "processing" }) Started(actor, turn.requestSeq) else null
        val old = byRequest[turn.requestId]
        if (old == next) return false
        if (old != null) starts[old.actorId]?.let { set ->
            set.remove(old.seq)
            if (set.isEmpty()) starts.remove(old.actorId)
        }
        if (next == null) byRequest.remove(turn.requestId) else {
            byRequest[turn.requestId] = next
            starts.getOrPut(next.actorId) { TreeSet() }.add(next.seq)
        }
        var changed = false
        for (id in listOfNotNull(old?.actorId, next?.actorId).distinct()) {
            val boundary = starts[id]?.descendingSet()?.asSequence()?.drop(RequestLifecycle.QUEUE_PASSED_AFTER - 1)?.firstOrNull()
            if (boundaries[id] != boundary) {
                if (boundary == null) boundaries.remove(id) else boundaries[id] = boundary
                changed = true
            }
        }
        if (changed) dirty = true
        return changed
    }
    fun snapshot(): Map<String, Long> {
        if (dirty) { cached = frozenMap(boundaries); dirty = false }
        return cached
    }
    fun clear() { byRequest.clear(); starts.clear(); boundaries.clear(); cached = emptyMap(); dirty = false }
}

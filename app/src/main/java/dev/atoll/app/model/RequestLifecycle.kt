package dev.atoll.app.model

import dev.atoll.app.core.string

enum class Lifecycle { CLOSED, LOST, WAITING, PROCESSING, PENDING }

object RequestLifecycle {
    const val QUEUE_PASSED_AFTER = 10
    fun memberRestarts(state: ReplicaSnapshot): Map<String, Long> {
        val restarts = mutableMapOf<String, Long>()
        fun note(id: String, seq: Long) { if (id.isNotEmpty() && seq > 0) restarts[id] = maxOf(restarts[id] ?: 0, seq) }
        fun visit(entry: TimelineEntry) {
            entry.turn?.let { turn ->
                if (turn.terminal?.status == "completed") when (turn.request.type) {
                    "system.member.restart" -> note(turn.request.body.text("member").ifEmpty { turn.terminal.body.text("member") }, turn.terminalSeq)
                    "system.member.restart_all" -> turn.terminal.body.array("restarted").forEach { note(it.string(), turn.terminalSeq) }
                }
            }
            entry.thread.forEach(::visit)
        }
        state.timeline.forEach(::visit)
        return frozenMap(restarts)
    }
    private fun lastFrameSeq(turn: Turn) = maxOf(turn.requestSeq, turn.provisional.maxOfOrNull { it.seq } ?: 0)
    fun of(turn: Turn?, restarts: Map<String, Long> = emptyMap(), now: Long = System.currentTimeMillis()): Lifecycle {
        if (turn == null) return Lifecycle.PENDING
        if (turn.terminal != null) return Lifecycle.CLOSED
        if (turn.requestSeq > 0 && (restarts[turn.request.audience.firstOrNull()] ?: 0) > lastFrameSeq(turn)) return Lifecycle.LOST
        if (ConversationVisibility.requestExpired(turn.request, now)) return Lifecycle.LOST
        return when (turn.latestCoreStatus) {
            "processing" -> Lifecycle.PROCESSING
            "queued", "received", "deferred" -> Lifecycle.WAITING
            else -> Lifecycle.PENDING
        }
    }
    fun lostReason(turn: Turn, restarts: Map<String, Long> = emptyMap(), now: Long = System.currentTimeMillis()): String =
        if (of(turn, restarts, now) != Lifecycle.LOST) "" else if ((restarts[turn.request.audience.firstOrNull()] ?: 0) > lastFrameSeq(turn)) "restart" else "expired"
    fun passedInQueue(turns: List<Turn>): (Turn) -> Boolean {
        val starts = turns.filter { it.request.senderKind == "human" && it.provisional.any { frame -> frame.envelope.status == "processing" } }.groupBy { it.request.audience.firstOrNull().orEmpty() }
        return { turn -> (starts[turn.request.audience.firstOrNull().orEmpty()] ?: emptyList()).count { it.requestSeq > turn.requestSeq } >= QUEUE_PASSED_AFTER }
    }
}

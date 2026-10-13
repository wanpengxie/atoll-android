package dev.atoll.app.waiting

import dev.atoll.app.compose.*
import dev.atoll.app.model.*

data class OutboxProjection(val timelinePlaced: Set<String> = emptySet(),
    val localEchoes: List<TimelineEntry> = emptyList(), val waiting: List<LocalSubmission> = emptyList())

/** The host supplies the unfiltered source; reuse lifecycle and FIFO rather than inventing them. */
fun busyActors(source: TimelineSource, now: Long = System.currentTimeMillis()): Set<String> {
    fun turns(entry: TimelineEntry): List<Turn> = listOfNotNull(entry.turn) + entry.thread.flatMap(::turns)
    val turns = source.timeline.flatMap(::turns)
    val restarts = RequestLifecycle.memberRestarts(source)
    val passed = RequestLifecycle.passedInQueue(turns)
    return turns.filter { turn ->
        val lifecycle = RequestLifecycle.of(turn, restarts, now)
        lifecycle == Lifecycle.PROCESSING || lifecycle == Lifecycle.WAITING && !passed(turn)
    }.mapNotNull { it.request.audience.singleOrNull()?.takeIf { actor -> actor.startsWith("agent:") } }.toSet()
}

/** One writer fixes each handoff's initial placement until the receiver admits/ends it. */
class OutboxPlacement {
    private val placement = mutableMapOf<String, Boolean>() // true = timeline, false = waiting
    val empty: Boolean get() = placement.isEmpty()
    fun project(channelId: String, selfId: String, rows: Collection<LocalSubmission>,
        canonical: Map<String, Turn>, busyActors: Set<String>, now: Long): OutboxProjection {
        val busy = busyActors.toMutableSet()
        val live = hashSetOf<String>()
        val localWaiting = mutableListOf<LocalSubmission>()
        val waitingTypes = setOf("agent.ask", "agent.queue")
        val current = rows.filter { it.channelId == channelId && (it.expiresAt == 0L || it.expiresAt > now) }
        for (row in current) {
            if (row.frame.text("msg_type") !in waitingTypes || row.state == SendState.REJECTED) continue
            val turn = canonical[row.messageId]
            if (turn?.terminal != null || turn?.latestCoreStatus == "processing") continue
            live += row.messageId
            val actor = (row.frame["audience"] as? kotlinx.serialization.json.JsonArray)?.firstOrNull()
                ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty()
            val inTimeline = placement.getOrPut(row.messageId) { actor !in busy }
            if (!inTimeline) {
                busy += actor
                if (turn == null) localWaiting += row
            }
        }
        // The outbox may reconcile before the receiver sends its first status. Keep placement
        // through that handoff, so a landed pending request does not flash into the dock.
        placement.keys.retainAll(live + canonical.values.filter { turn ->
            turn.terminal == null && turn.latestCoreStatus != "processing" &&
                !ConversationVisibility.requestExpired(turn.request, now)
        }.map { it.requestId })
        val placed = placement.filterValues { it }.keys.toSet()
        val echoes = current.filter { row ->
            val type = row.frame.text("msg_type")
            type != "agent.replace" && (type !in waitingTypes || row.messageId in placed)
        }.map { it.localTimelineEntry(selfId) }.filter { entry ->
            val envelope = entry.turn?.request ?: entry.envelope!!
            !envelope.type.startsWith("ui.") && envelope.type !in setOf("agent.select", "agent.new") &&
                !ConversationVisibility.isControlOnlyBody(envelope.type, envelope.body)
        }
        return OutboxProjection(placed, echoes, localWaiting)
    }
}

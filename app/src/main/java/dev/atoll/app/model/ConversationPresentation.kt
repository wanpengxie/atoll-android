package dev.atoll.app.model

import dev.atoll.app.core.*
import java.time.Instant
import java.time.ZoneId

/** View-only inputs; the ledger remains owned by ChannelReplica. */
enum class ConversationScope { ALL, MINE }
data class TimelineOptions(
    val scope: ConversationScope = ConversationScope.MINE,
    val selfId: String = "",
    val actorFilter: Set<String> = emptySet(),
    val editingTargetId: String = "",
    val editingReplacementId: String = "",
    val showNarration: Boolean = false,
    val localEchoes: List<TimelineEntry> = emptyList(),
    val timelinePlaced: Set<String> = emptySet(),
    /** A historical window cannot prove that an apparently open turn is still running. */
    val newerUnloaded: Boolean = false,
    val now: Long = System.currentTimeMillis(),
    val zoneId: ZoneId = ZoneId.systemDefault(),
)

object ConversationPresentation {
    private val selfOperations = setOf("terminal.command", "terminal.session")
    fun relatedEnvelopeIds(state: TimelineSource, selfId: String): Set<String> {
        val seeds = mutableSetOf<String>()
        val correlations = mutableSetOf<String>()
        for (row in state.rows) {
            val envelope = row.envelope
            val mine = selfId.isNotEmpty() && (envelope.senderId == selfId || selfId in envelope.audience)
            val operation = envelope.type.startsWith("system.") || envelope.type == "actor.describe" || envelope.audience.isNotEmpty() && envelope.audience.all { it == "system" }
            val conversational = !operation && envelope.audience.any { it.startsWith("agent:") || it.startsWith("human:") || it.startsWith("peer:") && envelope.type == "agent.ask" }
            val memberRoot = envelope.senderKind in setOf("agent", "tool", "peer") && envelope.senderId.isNotEmpty() && envelope.parentId.isEmpty() && !operation && (envelope.kind != "request" || conversational)
            if (envelope.type in selfOperations || !mine && !memberRoot) continue
            if (envelope.id.isNotEmpty()) seeds.add(envelope.id)
            correlations.add(envelope.correlationId)
        }
        val related = seeds.toMutableSet()
        for (row in state.rows) {
            val envelope = row.envelope
            if (envelope.id.isNotEmpty() && envelope.type !in selfOperations && (envelope.parentId in seeds || envelope.correlationId in correlations)) related.add(envelope.id)
        }
        return java.util.Collections.unmodifiableSet(related)
    }
    fun conversationRelation(state: TimelineSource, selfId: String): (Envelope) -> Boolean {
        val related = relatedEnvelopeIds(state, selfId)
        val byEnvelope = mutableMapOf<String, Boolean>()
        for (entry in state.timeline) {
            val matches = matchesConversation(entry, related)
            entry.envelopes().forEach { byEnvelope[it.id] = matches }
        }
        return { envelope -> byEnvelope[envelope.id] ?: (envelope.id in related) }
    }
    private fun matchesConversation(entry: TimelineEntry, related: Set<String>) = entry.envelopes().any { it.type !in selfOperations } && entry.envelopes().any { it.id in related }
    private fun waiting(turn: Turn, restarts: Map<String, Long>, options: TimelineOptions) = !options.newerUnloaded && turn.request.type in ConversationVisibility.agentMessageTypes && turn.requestId !in options.timelinePlaced && RequestLifecycle.of(turn, restarts, options.now) in setOf(Lifecycle.PENDING, Lifecycle.WAITING)
    fun selectTimelineItems(state: TimelineSource, options: TimelineOptions = TimelineOptions(), facts: ProjectionFacts? = null): List<TimelineEntry> {
        val restarts = facts?.restarts ?: RequestLifecycle.memberRestarts(state)
        val related = facts?.relatedIds ?: if (options.scope == ConversationScope.MINE && options.selfId.isNotEmpty()) relatedEnvelopeIds(state, options.selfId) else null
        val filtered = state.timeline.mapNotNull { entry ->
            val envelope = entry.turn?.request ?: entry.envelope ?: return@mapNotNull null
            // Detached process rows wait until history admits their parent. Human prose and approvals survive.
            if (envelope.parentId.isNotEmpty() && envelope.senderKind != "human" && envelope.type != "agent.provider.run" && !(envelope.kind == "request" && envelope.audience.any { it.startsWith("human:") }) && !state.envelopesById.containsKey(envelope.parentId)) return@mapNotNull null
            if (entry.kind == "standalone" && envelope.type == "terminal.session") return@mapNotNull null
            val turn = entry.turn
            if (turn != null) {
                if (turn.requestId == options.editingReplacementId || waiting(turn, restarts, options)) return@mapNotNull null
                if (turn.requestId != options.editingTargetId) {
                    if (turn.terminal?.body?.text("replaced_by")?.isNotEmpty() == true) return@mapNotNull null
                    if (options.scope == ConversationScope.MINE && (envelope.type.startsWith("ui.") || ConversationVisibility.isControlOnlyTurn(turn))) return@mapNotNull null
                    if (envelope.type in setOf("agent.select", "agent.new") && turn.terminal?.status != "completed") return@mapNotNull null
                }
            } else if (options.scope == ConversationScope.MINE && envelope.type.startsWith("ui.")) return@mapNotNull null
            val scoped = if (options.scope == ConversationScope.MINE && entry.thread.isNotEmpty()) entry.copy(thread = frozen(entry.thread.filter { !it.turn!!.request.type.startsWith("ui.") && !ConversationVisibility.isControlOnlyTurn(it.turn) })) else entry
            if (related != null && !matchesConversation(scoped, related)) return@mapNotNull null
            if (options.scope == ConversationScope.MINE && options.actorFilter.isNotEmpty() && !scoped.envelopes().any { it.senderId in options.actorFilter || it.audience.any(options.actorFilter::contains) }) return@mapNotNull null
            scoped
        }
        val transientTail = mutableMapOf<String, TimelineEntry>()
        for (entry in filtered) if (isTransient(entry)) transientTail["${entry.envelope?.senderId}:${entry.envelope?.type}"] = entry
        val selected = filtered.filter { !isTransient(it) || transientTail["${it.envelope?.senderId}:${it.envelope?.type}"] === it }
        val items = attachSubTasks(selected, state, facts?.taskSteps).toMutableList()
        if (options.showNarration && state.narration.isNotEmpty()) {
            val first = state.narration.first().seq
            val index = items.indexOfFirst { it.seq > first }.takeIf { it >= 0 } ?: items.size
            items.add(index, TimelineEntry("narration", first, lastSeq = state.narration.last().seq))
        }
        items.addAll(options.localEchoes.filter { echo ->
            val envelope = echo.turn?.request ?: echo.envelope
            envelope != null && !state.envelopesById.containsKey(envelope.id) && !envelope.type.startsWith("ui.") && envelope.type !in ConversationVisibility.hiddenTurnTypes && !ConversationVisibility.isControlOnlyBody(envelope.type, envelope.body) && envelope.type !in setOf("agent.select", "agent.new") && (envelope.type !in ConversationVisibility.agentMessageTypes || envelope.id in options.timelinePlaced)
        })
        return frozen(items)
    }
    private fun isTransient(entry: TimelineEntry) = entry.kind == "standalone" && (entry.envelope?.body?.flag("transient") == true || entry.envelope?.type == "mock.channel.pulse")

    private fun attachSubTasks(items: List<TimelineEntry>, state: TimelineSource, taskSteps: List<Envelope>? = null): List<TimelineEntry> {
        val steps = taskSteps ?: state.rows.map { it.envelope }.filter { it.type == "agent.task" }
        if (steps.isEmpty()) return items
        val byRequest = steps.filter { it.parentId.isNotEmpty() }.groupBy { it.parentId }
        val holders = mutableMapOf<String, Int>()
        items.forEachIndexed { index, entry ->
            if (entry.turn != null) (listOf(entry) + entry.thread).forEach { child -> child.turn?.requestId?.takeIf(byRequest::containsKey)?.let { holders[it] = index } }
        }
        val attached = mutableMapOf<Int, MutableList<Envelope>>()
        for ((request, index) in holders) attached.getOrPut(index) { mutableListOf() }.addAll(byRequest.getValue(request))
        val identity = BackgroundTasks.subTaskIdentity(steps)
        val ends = steps.filter { it.body.text("phase") in setOf("completed", "failed") }.associateBy(identity)
        for (list in attached.values) for (key in list.map(identity).distinct()) ends[key]?.let { end -> if (end !in list) list.add(end) }
        fun orphan(entry: TimelineEntry) = entry.kind == "standalone" && entry.envelope?.type == "agent.task" && entry.envelope.parentId !in holders
        fun key(envelope: Envelope) = if (envelope.parentId.isNotEmpty()) "request:${envelope.parentId}" else "call:${envelope.body.text("call_id").ifEmpty { envelope.id }}"
        val groups = mutableMapOf<String, MutableList<Envelope>>()
        val anchors = mutableMapOf<String, Int>()
        items.forEachIndexed { index, entry -> if (orphan(entry)) {
            val taskKey = key(entry.envelope!!)
            groups.getOrPut(taskKey) { mutableListOf() }.add(entry.envelope)
            anchors.putIfAbsent(taskKey, index)
        } }
        val blockOf = mutableMapOf<String, String>()
        val blocks = mutableMapOf<String, MutableList<String>>()
        val open = mutableMapOf<String, String>()
        items.forEachIndexed { index, entry ->
            if (!orphan(entry)) { open.clear(); return@forEachIndexed }
            val envelope = entry.envelope!!
            val taskKey = key(envelope)
            if (anchors[taskKey] != index) return@forEachIndexed
            val block = if (taskKey.startsWith("call:")) open[envelope.senderId] ?: taskKey else taskKey
            blockOf[taskKey] = block
            blocks.getOrPut(block) { mutableListOf() }.add(taskKey)
            if (taskKey.startsWith("call:")) open[envelope.senderId] = block
        }
        return items.mapIndexedNotNull { index, entry ->
            if (entry.kind == "standalone" && entry.envelope?.type == "agent.task") {
                if (entry.envelope.parentId in holders) return@mapIndexedNotNull null
                val taskKey = key(entry.envelope)
                if (anchors[taskKey] != index || blockOf[taskKey] != taskKey) return@mapIndexedNotNull null
                val list = blocks.getValue(taskKey).flatMap { groups.getValue(it) }
                entry.copy(subTasks = frozen(list), subTaskRevision = subTaskRevision(list))
            } else attached[index]?.let { list ->
                val ordered = list.sortedBy { it.ts }
                entry.copy(subTasks = frozen(ordered), subTaskRevision = subTaskRevision(ordered))
            } ?: entry
        }
    }
    private fun subTaskRevision(steps: List<Envelope>) = "${steps.count { it.body.text("phase") != "progress" }}.${(steps.filter { it.body.text("phase") == "progress" }.maxOfOrNull { it.ts } ?: 0) / 15000}"

    fun project(state: TimelineSource, options: TimelineOptions = TimelineOptions(), facts: ProjectionFacts? = null): TimelineSnapshot {
        val entries = selectTimelineItems(state, options, facts)
        val items = entries.mapIndexed { index, entry -> presentationItem(entry, entries.getOrNull(index - 1), entries.getOrNull(index + 1), options) }
        val restarts = facts?.restarts ?: RequestLifecycle.memberRestarts(state)
        val waitingTurns = state.timeline.flatMap { listOfNotNull(it.turn) + it.thread.mapNotNull { child -> child.turn } }.filter { waiting(it, restarts, options) }.sortedBy { it.requestSeq }
        return TimelineSnapshot(state.channelId, state.projectionVersion, frozen(items), frozen(waitingTurns), if (options.newerUnloaded) emptyList() else (facts?.runningTasks ?: BackgroundTasks.runningBackgroundTasks(state)), entries.firstOrNull()?.seq ?: 0, entries.lastOrNull()?.seq ?: 0)
    }
    internal fun presentationItem(entry: TimelineEntry, previous: TimelineEntry?, next: TimelineEntry?, options: TimelineOptions): PresentationItem {
        val envelope = entry.turn?.request ?: entry.envelope
        val timestamp = envelope?.ts ?: 0
        val nextTimestamp = next?.let { it.turn?.request?.ts ?: it.envelope?.ts } ?: 0
        val day = dayOf(timestamp, options.zoneId)
        val previousTimestamp = previous?.turn?.request?.ts ?: previous?.envelope?.ts ?: 0
        val previousAuthor = previous?.turn?.terminal?.senderId ?: previous?.turn?.request?.senderId ?: previous?.envelope?.senderId.orEmpty()
        val continuation = previous != null && conversational(previous) && conversational(entry) && envelope?.senderId?.isNotEmpty() == true && envelope.senderId == previousAuthor && timestamp - previousTimestamp in 0..300000
        val bounds = bounds(entry)
        val text = envelope?.body?.text("text")?.ifEmpty { envelope.body.text("body") }.orEmpty()
        val layout = when {
            entry.kind == "narration" || entry.envelope?.let(ConversationVisibility::weakNoticeEvent) == true -> "compact"
            envelope != null && (ConversationVisibility.attachments(envelope.body).isNotEmpty() || "```" in text || text.length > 1200) -> "rich"
            entry.turn != null -> if (entry.turn.terminal == null && !options.newerUnloaded) "reserved" else "normal"
            text.length < 120 -> "compact"
            else -> "normal"
        }
        return PresentationItem(entry.id, entry, bounds.first, bounds.second,
            entry.turn?.request?.audience?.firstOrNull() ?: entry.turn?.terminal?.senderId ?: envelope?.senderId.orEmpty(),
            timestamp, day, if (timestamp > 0 && nextTimestamp > 0 && day != dayOf(nextTimestamp, options.zoneId)) nextTimestamp else 0,
            continuation, layout, options.newerUnloaded || entry.turn?.terminal != null || entry.kind == "narration" || entry.turn == null && envelope?.body?.flag("transient") != true,
            "${bounds.second}:${entry.copy(subTasks = emptyList()).hashCode()}${entry.subTaskRevision.takeIf { it.isNotEmpty() }?.let { ":$it" }.orEmpty()}")
    }
    private fun dayOf(timestamp: Long, zone: ZoneId) = if (timestamp <= 0) "" else Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate().toString()
    private fun conversational(entry: TimelineEntry) = entry.kind == "standalone" && entry.envelope?.let { !ConversationVisibility.weakNoticeEvent(it) } == true || entry.kind == "turn" && entry.turn?.request?.type !in setOf("human.ask", "human.approve")
    private fun bounds(entry: TimelineEntry): Pair<Long, Long> {
        if (entry.kind == "narration") return entry.seq to maxOf(entry.seq, entry.lastSeq)
        if (entry.turn == null) return entry.seq to entry.seq
        val seqs = (listOf(entry) + entry.thread).flatMap { node -> listOf(node.seq, node.turn?.requestSeq ?: 0, node.turn?.lastSeq ?: 0, node.turn?.terminalSeq ?: 0) + node.turn?.provisional.orEmpty().map { it.seq } }.filter { it > 0 }
        return (seqs.minOrNull() ?: entry.seq) to (seqs.maxOrNull() ?: entry.seq)
    }
}

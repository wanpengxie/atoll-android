package dev.atoll.app.model

/**
 * Committed presentation owner. Keeps unchanged row identities and continuation
 * decisions across history prepends; no viewport coordinates or DOM admission.
 */
class TimelinePresentation {
    private var previous: TimelineSnapshot? = null
    private var view: String = ""
    private var indexes: Map<String, Int> = emptyMap()
    private val excludedCurrent = setOf("human.ask", "human.approve", "agent.select", "agent.new", "agent.hold", "agent.hold_expired", "agent.unhold", "agent.interrupt", "agent.context", "agent.options", "agent.fork", "actor.describe", "storage.get_url")

    fun project(state: TimelineSource, options: TimelineOptions = TimelineOptions(), epoch: String = "", facts: ProjectionFacts? = null, changedEntries: List<TimelineEntry>? = null): TimelineSnapshot {
        val nextView = listOf(state.channelId, options.scope, options.selfId, options.actorFilter.sorted(), options.showNarration, options.newerUnloaded, epoch).joinToString("\u0000")
        val old = previous.takeIf { view == nextView }
        if (old != null && changedEntries != null && facts?.queueBoundaries != null) {
            projectContent(state, options, facts, changedEntries, old)?.let { previous = it; return it }
        }
        val raw = ConversationPresentation.project(state, options, facts)
        val previousItems = old?.items.orEmpty()
        val previousById = previousItems.associateBy { it.entry.id }
        val previousIds = previousItems.map { it.entry.id }
        val nextIds = raw.items.map { it.entry.id }
        val oldIndex = previousIds.withIndex().associate { it.value to it.index }
        val oldSet = previousIds.toSet()
        val nextSet = nextIds.toSet()
        val currentById = state.timeline.associateBy { it.id }
        fun neighbour(ids: List<String>, index: Int, admitted: Set<String>, direction: Int): String {
            var cursor = index + direction
            while (cursor in ids.indices) {
                if (ids[cursor] in admitted) return ids[cursor]
                cursor += direction
            }
            return ""
        }
        val items = raw.items.mapIndexed { index, item ->
            val id = item.entry.id
            val existing = previousById[id]
            var key = existing?.key ?: item.key
            val successor = item.entry.turn
            if (existing == null && successor?.request?.type == "agent.replace") {
                val target = successor.request.body.text("target")
                val targetIndex = oldIndex[target]
                val oldItem = previousById[target]
                val proof = currentById[target]?.turn?.terminal ?: oldItem?.entry?.turn?.terminal
                if (targetIndex != null && oldItem != null && target !in nextSet && id !in oldSet && proof?.body?.text("replaced_by") == id &&
                    neighbour(previousIds, targetIndex, nextSet, -1) == neighbour(nextIds, index, oldSet, -1) &&
                    neighbour(previousIds, targetIndex, nextSet, 1) == neighbour(nextIds, index, oldSet, 1)) key = oldItem.key
            }
            val candidate = item.copy(key = key, continuation = existing?.continuation ?: item.continuation)
            // A progress-only subtask bucket does not replace the rendered card.
            if (existing != null && candidate.copy(entry = existing.entry) == existing) existing else candidate
        }
        val inserted = nextIds.filter { it !in oldSet }
        val removed = previousIds.filter { it !in nextSet }
        val updated = items.filter { previousById[it.entry.id]?.let { before -> before !== it } == true }.map { it.entry.id }
        val start = nextIds.indexOf(previousIds.firstOrNull())
        val continuous = old != null && previousIds.isNotEmpty() && start >= 0 && removed.isEmpty() && previousIds.withIndex().all { (index, id) -> nextIds.getOrNull(start + index) == id }
        val front = if (continuous) nextIds.take(start) else emptyList()
        val back = if (continuous) nextIds.drop(start + previousIds.size) else emptyList()
        val kind = when {
            old == null -> "rebase"
            front.isNotEmpty() && back.isNotEmpty() -> "mixed"
            front.isNotEmpty() -> "prepend"
            back.isNotEmpty() -> "append"
            previousIds != nextIds -> "mixed"
            updated.isNotEmpty() -> "revise"
            else -> "unchanged"
        }
        val current = items.lastOrNull { item ->
            val envelope = item.entry.turn?.request ?: item.entry.envelope
            envelope != null && item.entry.kind in setOf("turn", "standalone") && !envelope.type.startsWith("ui.") && envelope.type !in excludedCurrent
        }?.let { CurrentEntryCandidate(it.entry.id, it.seqHigh, it.entry.local) }
        val result = raw.copy(items = frozen(items), changes = PresentationChanges(kind, frozen(front), frozen(back), frozen(inserted), frozen(updated), frozen(removed)), currentEntryCandidate = current)
        previous = result
        indexes = result.items.withIndex().associate { it.value.entry.id to it.index }
        view = nextView
        return result
    }
    private fun projectContent(state: TimelineSource, options: TimelineOptions, facts: ProjectionFacts, changed: List<TimelineEntry>, old: TimelineSnapshot): TimelineSnapshot? {
        if (options.newerUnloaded) return null
        var items: MutableList<PresentationItem>? = null
        val updated = mutableListOf<String>()
        val changedTurns = mutableMapOf<String, Turn>()
        for (entry in changed) {
            val subset = object : TimelineSource by state { override val timeline = listOf(entry) }
            val selected = ConversationPresentation.selectTimelineItems(subset, options.copy(localEchoes = emptyList()), facts)
            val index = indexes[entry.id]
            if (index == null && selected.isNotEmpty() || index != null && (selected.size != 1 || selected.first().id != entry.id)) return null
            (listOf(entry) + entry.thread).mapNotNull { it.turn }.forEach { changedTurns[it.requestId] = it }
            if (index == null) continue
            val before = old.items[index]
            val candidate = ConversationPresentation.presentationItem(selected.single(), old.items.getOrNull(index - 1)?.entry, old.items.getOrNull(index + 1)?.entry, options).copy(key = before.key, continuation = before.continuation)
            if (candidate.copy(entry = before.entry) != before) {
                if (items == null) items = old.items.toMutableList()
                items[index] = candidate
                updated.add(entry.id)
            }
        }
        val passed = RequestLifecycle.passedInQueue(facts.queueBoundaries.orEmpty())
        val waiting = old.waitingTurns.filter { it.requestId !in changedTurns && ConversationPresentation.waiting(it, facts.restarts, options, passed) }.toMutableList()
        waiting.addAll(changedTurns.values.filter { ConversationPresentation.waiting(it, facts.restarts, options, passed) })
        waiting.sortBy { it.requestSeq }
        val rendered = items?.let(::frozen) ?: old.items
        val current = old.currentEntryCandidate?.let { candidate -> indexes[candidate.id]?.let { index -> candidate.copy(seqHigh = rendered[index].seqHigh) } ?: candidate }
        return old.copy(revision = state.projectionVersion, items = rendered, waitingTurns = frozen(waiting), runningTasks = facts.runningTasks,
            changes = PresentationChanges(if (updated.isEmpty()) "unchanged" else "revise", updated = frozen(updated)), currentEntryCandidate = current)
    }
    fun reset() { previous = null; view = ""; indexes = emptyMap() }
}

package dev.atoll.app.model

/**
 * Committed presentation owner. Keeps unchanged row identities and continuation
 * decisions across history prepends; no viewport coordinates or DOM admission.
 */
class TimelinePresentation {
    private var previous: TimelineSnapshot? = null
    private var view: String = ""
    private val excludedCurrent = setOf("human.ask", "human.approve", "agent.select", "agent.new", "agent.hold", "agent.hold_expired", "agent.unhold", "agent.interrupt", "agent.context", "agent.options", "agent.fork", "actor.describe", "storage.get_url")

    fun project(state: ReplicaSnapshot, options: TimelineOptions = TimelineOptions(), epoch: String = ""): TimelineSnapshot {
        val nextView = listOf(state.channelId, options.scope, options.selfId, options.actorFilter.sorted(), options.showNarration, epoch).joinToString("\u0000")
        val old = previous.takeIf { view == nextView }
        val raw = ConversationPresentation.project(state, options)
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
        view = nextView
        return result
    }
    fun reset() { previous = null; view = "" }
}

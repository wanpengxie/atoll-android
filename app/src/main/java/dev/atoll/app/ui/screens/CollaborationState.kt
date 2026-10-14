package dev.atoll.app.ui.screens

import androidx.compose.runtime.Immutable

/** One root's existing flat thread; identity and navigation use request IDs, never labels. */
@Immutable
data class CollaborationViewState(val root: RelatedCallViewState, val calls: List<RelatedCallViewState>,
    val owner: String = root.sender)

/** Only conversation nodes are navigation destinations; tools remain in their parent's call count. */
class CollaborationIndex(val state: CollaborationViewState) {
    private val byId = (listOf(state.root) + state.calls).associateBy { it.id }
    private val visible = state.calls.filter { it.conversation }.mapTo(hashSetOf()) { it.id }.apply { add(state.root.id) }
    private val parents = state.calls.filter { it.conversation }.associate { call ->
        var parent = call.parentId
        val visited = hashSetOf(call.id)
        while (parent.isNotEmpty() && parent !in visible && visited.add(parent)) parent = byId[parent]?.parentId.orEmpty()
        call.id to parent.takeIf { it in visible && it != call.id }.orEmpty().ifEmpty { state.root.id }
    }
    private val children = state.calls.filter { it.conversation }.groupBy { parents[it.id] }

    fun selected(id: String?) = byId[id]?.takeIf { it.id in visible } ?: state.root
    fun children(id: String): List<RelatedCallViewState> = children[id].orEmpty()
    fun ordinaryCount(id: String): Int = state.calls.count { !it.conversation && it.parentId == id }
    fun path(id: String): List<RelatedCallViewState> {
        val result = mutableListOf<RelatedCallViewState>()
        var current: RelatedCallViewState? = selected(id)
        val seen = hashSetOf<String>()
        while (current != null && seen.add(current.id)) {
            result += current
            current = if (current.id == state.root.id) null else byId[parents[current.id]]
        }
        return result.asReversed()
    }
    fun label(call: RelatedCallViewState): String = if (call.id == state.root.id) state.owner else
        call.recipients.joinToString("、").ifEmpty { call.sender }
}

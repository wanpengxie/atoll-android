package dev.atoll.app.app

import dev.atoll.app.model.TimelineSource
import dev.atoll.app.ui.screens.*

/** A filtered empty window alone proves neither an empty channel nor history exhaustion. */
internal fun timelineFeedback(view: ConversationViewState, source: TimelineSource?, atStart: Boolean,
    current: Boolean, newerUnloaded: Boolean): ConversationViewState {
    val settled = source != null && atStart && current && !view.loadingOlder && !newerUnloaded
    val actorFiltered = view.mineOnly && view.actorFilters.isNotEmpty()
    val kind = if (view.entries.isNotEmpty()) null else when {
        source == null || source.timeline.isEmpty() && source.narration.isEmpty() -> TimelineEmptyKind.Channel
        actorFiltered -> TimelineEmptyKind.ActorFilter
        view.mineOnly -> TimelineEmptyKind.Mine
        else -> null
    }
    return view.copy(emptyKind = kind, emptySettled = settled,
        historyStartLabel = if (atStart && !view.loadingOlder && view.entries.isNotEmpty()) {
            if (actorFiltered) "已到频道开头，没有更早的符合筛选的往来" else "已到频道最早一条动态"
        } else null)
}

/** Include the confirmed-start row in every scroll target, just as the older button. */
internal fun timelineHeaderCount(view: ConversationViewState): Int = if (view.hasOlder ||
    !view.loadingOlder && view.entries.isNotEmpty() && !view.historyStartLabel.isNullOrBlank()) 1 else 0

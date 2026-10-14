package dev.atoll.app.app

import dev.atoll.app.model.TimelineSource
import dev.atoll.app.ui.screens.CollaborationViewState

/** A child process and its root use one collaboration thread, including this reader's asks. */
internal fun collaborationView(target: TimelineMessageTarget, source: TimelineSource?,
    names: Map<String, String>, newerUnloaded: Boolean = false, selfId: String = ""): CollaborationViewState? {
    val selected = target.turn ?: return null
    val entry = source?.timeline?.firstOrNull { it.turn?.requestId == selected.requestId ||
        it.thread.any { child -> child.turn?.requestId == selected.requestId } }
    val root = entry?.turn ?: selected
    val labels = if (selfId.isEmpty()) names else names + (selfId to "我")
    val tasks = subTaskViews(source?.rows.orEmpty().map { it.envelope }.filter { it.type == "agent.task" }, labels)
        .groupBy { it.requestId.orEmpty() }
    val rootView = relatedCallView(root, labels, tasks, newerUnloaded, ::panelTime)
    val calls = relatedCallViews(root, entry?.thread.orEmpty(), labels, tasks, newerUnloaded, false, ::panelTime)
    val owner = if (root.request.senderKind == "human") rootView.recipients.firstOrNull() ?: rootView.sender else rootView.sender
    return CollaborationViewState(rootView, calls, owner)
}

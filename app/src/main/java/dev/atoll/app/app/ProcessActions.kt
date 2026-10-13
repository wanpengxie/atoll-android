package dev.atoll.app.app

import dev.atoll.app.ui.screens.MessageViewState

/** Controls address the canonical request even when the panel was opened from its response. */
internal fun processActionTarget(target: TimelineMessageTarget): TimelineMessageTarget {
    val turn = target.turn ?: return target
    return TimelineMessageTarget(turn.request, turn, turn.requestSeq)
}

internal fun processActionMessage(target: TimelineMessageTarget, selfId: String, roster: List<ActorRecord>): MessageViewState {
    val request = processActionTarget(target).envelope
    return MessageViewState(request.id, roster.firstOrNull { it.id == request.senderId }?.name ?: request.senderId,
        messageText(request), panelTime(request.ts), agent = request.senderKind == "agent", self = request.senderId == selfId)
}

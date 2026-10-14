package dev.atoll.app.app

import dev.atoll.app.compose.ComposerViewModel
import dev.atoll.app.compose.TurnStatePolicy
import dev.atoll.app.model.TimelineSource

internal fun openTurnProcess(graph: AppGraph, channelId: String, key: String,
    targets: Map<String, TimelineMessageTarget>, source: TimelineSource?) {
    val target = processTarget(key, targets, source)
    val id = target?.turn?.requestId ?: target?.envelope?.id ?: key
    graph.route(focusedChannelLink(channelId, FocusKind.Turn, id))
}

/** Host only transfers the selected, owned source request to the existing composer owner. */
internal fun resendAction(composer: ComposerViewModel, target: TimelineMessageTarget, selfId: String,
    lostReason: String = "", newerUnloaded: Boolean = false): (() -> Unit)? {
    val turn = target.turn ?: return null
    if (!TurnStatePolicy.canResend(turn, selfId, lostReason, newerUnloaded)) return null
    return { composer.resend(turn, lostReason, newerUnloaded) }
}

package dev.atoll.app.compose

import dev.atoll.app.core.Envelope
import dev.atoll.app.model.Turn
import dev.atoll.app.ui.screens.MessageViewState
import dev.atoll.app.ui.screens.TurnKind

/** Compact closures open their surviving turn; answer rows open the owning request. */
internal fun messageProcessId(message: MessageViewState, envelope: Envelope, turn: Turn?): String {
    val state = message.turnState
    if (state != null && state.kind in setOf(TurnKind.Merged, TurnKind.Preempted) && state.targetId.isNotEmpty()) return state.targetId
    return state?.requestId?.takeIf(String::isNotEmpty) ?: turn?.requestId ?: envelope.id
}

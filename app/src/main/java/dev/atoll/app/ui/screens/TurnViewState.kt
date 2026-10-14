package dev.atoll.app.ui.screens

import androidx.compose.runtime.Immutable

enum class TurnKind { Normal, Merged, Preempted, Stopped, Failed, Lost, Cancelled }

/** Facts from the ledger, independent of the editor's local session. */
@Immutable
data class TurnViewState(val kind: TurnKind = TurnKind.Normal, val requestId: String = "",
    val targetId: String = "", val lostReason: String = "", val edited: Boolean = false, val resendable: Boolean = false,
    val receiverId: String = "") {
    val compact: Boolean get() = kind in setOf(TurnKind.Merged, TurnKind.Preempted, TurnKind.Lost, TurnKind.Cancelled)
}

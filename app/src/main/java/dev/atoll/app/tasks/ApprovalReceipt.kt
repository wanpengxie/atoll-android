package dev.atoll.app.tasks

import dev.atoll.app.core.string
import dev.atoll.app.model.Turn
import dev.atoll.app.ui.components.ResultViewState
import dev.atoll.app.ui.components.terminalResult

/** Presentation-only projection; ledger task facts remain independent of UI types. */
internal data class ApprovalReceipt(val resolverId: String = "", val decision: String = "", val status: String = "",
    val text: String = "", val result: ResultViewState? = null, val unavailable: Boolean = false)

internal fun approvalReceipt(turn: Turn?): ApprovalReceipt? {
    val terminal = turn?.terminal ?: return null
    if (turn.terminalClosureOnly) return ApprovalReceipt(status = terminal.status, unavailable = true)
    val answer = terminalResult(terminal, turn.request.type)
    return ApprovalReceipt(terminal.senderId, terminal.body["decision"].string(), terminal.status, answer.text, answer.result)
}

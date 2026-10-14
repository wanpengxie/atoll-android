package dev.atoll.app.tasks

import dev.atoll.app.core.WireClient
import dev.atoll.app.core.WireException
import dev.atoll.app.core.WireState
import dev.atoll.app.core.Up
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ApprovalAuthority(val selfId: String, val writable: Boolean)

/** The task page and inbox use the same human-request permissions, including expiry. */
fun approvalActions(item: TaskFact, authority: ApprovalAuthority, connected: Boolean, now: Long): List<String> {
    val expires = item.turn?.request?.expiresAt ?: item.dueAt.toLongOrNull() ?: 0
    if (!connected || !authority.writable || authority.selfId.isEmpty() || authority.selfId !in item.assigneeActorIds ||
        item.kind != "approval" || item.state != "waiting" || item.turn?.terminal != null || expires > 0 && expires <= now) return emptyList()
    return when (item.turn?.request?.type ?: item.diagnostic.text("requestType")) {
        "human.approve" -> listOf(TaskAction.APPROVE, TaskAction.REJECT)
        "human.ask" -> listOf(TaskAction.APPROVE)
        else -> emptyList()
    }
}

/** Session-owned: one claim per request, even when two surfaces answer it concurrently. */
class ApprovalResolver(private val wire: WireClient, private val now: () -> Long = System::currentTimeMillis) {
    private val claims = Mutex()
    private val mutable = MutableStateFlow<Map<String, TaskCommandState>>(emptyMap())
    val state = mutable.asStateFlow()

    suspend fun resolve(item: TaskFact, action: String, authority: ApprovalAuthority, note: String = "", text: String = "") {
        val claimed = claims.withLock {
            if (mutable.value[item.key]?.state in setOf("submitting", "disabled")) false
            else if (item.kind == "approval" && item.state in setOf("completed", "failed", "cancelled", "uncertain")) {
                mutable.value = mutable.value + (item.key to TaskCommandState("disabled", "已处理")); false
            } else {
                require(action in approvalActions(item, authority, wire.state.value is WireState.Attached, now())) { "此请求当前不可回答" }
                mutable.value = mutable.value + (item.key to TaskCommandState("submitting")); true
            }
        }
        if (!claimed) return
        try {
            wire.request(Up.RESOLVE, approvalResolvePayload(item, if (action == TaskAction.APPROVE) "approve" else "reject", note, text))
            finish(item, TaskCommandState("disabled", "已处理"))
        } catch (error: CancellationException) {
            withContext(NonCancellable) { finish(item, TaskCommandState()) }; throw error
        } catch (error: Exception) {
            if (error is WireException && error.code == "already_closed") finish(item, TaskCommandState("disabled", "已处理"))
            else { finish(item, TaskCommandState("failed", error = error.message.orEmpty())); throw error }
        }
    }

    private suspend fun finish(item: TaskFact, result: TaskCommandState) = claims.withLock {
        mutable.value = mutable.value + (item.key to result)
    }
}

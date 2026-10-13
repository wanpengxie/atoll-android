package dev.atoll.app.compose

import dev.atoll.app.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

/** One state writer; per-message wire calls run concurrently and never hold that writer. */
class OutboxEngine(
    private val wire: WireClient,
    private val repository: OutboxRepository,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
    private val delayedAfterMs: Long = 5000,
    private val mayTransmit: (String) -> Boolean = { true },
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val rows = linkedMapOf<String, LocalSubmission>()
    private val inFlight = hashSetOf<String>()
    private val automaticallyRetried = hashMapOf<String, Long>()
    private val landed = hashSetOf<String>()
    private val mutable = MutableStateFlow<List<LocalSubmission>>(emptyList())
    val submissions: StateFlow<List<LocalSubmission>> = mutable.asStateFlow()
    private val ready = CompletableDeferred<Unit>()
    private var generation = 0L
    private var expiryJob: Job? = null
    private var online = false
    init {
        scope.launch {
            try {
                for (saved in repository.restore()) {
                    val row = if (saved.state == SendState.TRANSMITTING) saved.copy(state = SendState.UNCERTAIN) else saved
                    if (row.expiresAt > 0 && row.expiresAt <= now()) repository.remove(row.messageId) else rows[row.messageId] = row
                }
                publish(); ready.complete(Unit)
                for (command in commands) command()
            } catch (error: Throwable) { ready.completeExceptionally(error); scope.cancel("outbox stopped", error) }
            finally { commands.close() }
        }
        scope.launch { wire.state.collect { state -> enqueue {
            online = state is WireState.Attached
            if (state is WireState.Attached) { generation = state.generation; pump() }
        } } }
    }
    suspend fun awaitReady() = ready.await()
    suspend fun refresh() = action { pump() }
    suspend fun accept(batch: List<LocalSubmission>) = action {
        require(batch.all { row -> row.frame["payload"]?.let { it as? JsonObject }?.array("attachments")?.all { attachment ->
            val id = (attachment as? JsonObject)?.text("resource_id").orEmpty()
            id.isNotEmpty() && !id.startsWith("blob:")
        } != false }) { "附件尚未成为可恢复的频道资源" }
        repository.putMany(batch)
        for (row in batch) if (row.messageId !in landed && row.messageId !in rows) rows[row.messageId] = row
        publish(); pump()
    }
    suspend fun retry(id: String) = action {
        val row = rows[id] ?: return@action
        if (row.state !in setOf(SendState.REJECTED, SendState.UNCERTAIN)) return@action
        automaticallyRetried.remove(id)
        update(row.copy(state = SendState.QUEUED, error = "", updatedAt = now())); pump()
    }
    suspend fun reconcileLanded(channelId: String, ids: Set<String>) = action {
        // Remember only our outstanding submissions, never the entire live feed.
        landed.addAll(ids.filter { it in rows || it in inFlight })
        for (id in ids) if (rows[id]?.channelId == channelId) remove(id)
        publish()
    }
    suspend fun cancel(id: String) = action {
        val row = rows[id] ?: return@action
        if (row.state in setOf(SendState.QUEUED, SendState.REJECTED)) { remove(id); publish(); return@action }
        // A submitted intent may already be on the ledger. Persist its cancellation;
        // reconnect sends cancel rather than replaying that intent.
        update(row.copy(cancelRequested = true, updatedAt = now(), error = "取消待确认")); pump()
    }
    private suspend fun pump() {
        for (row in rows.values.toList()) {
            if (row.expiresAt > 0 && row.expiresAt <= now()) { remove(row.messageId); publish(); continue }
            if (!online || !mayTransmit(row.channelId) || row.messageId in inFlight) continue
            if (row.cancelRequested) { transmitCancel(row); continue }
            if (row.state == SendState.QUEUED || row.state == SendState.UNCERTAIN && automaticallyRetried[row.messageId] != generation) {
                if (row.state == SendState.UNCERTAIN) automaticallyRetried[row.messageId] = generation
                transmit(row)
            }
        }
    }
    private suspend fun transmit(row: LocalSubmission) {
        inFlight.add(row.messageId)
        val frame = prepareFrame(row.frame)
        update(row.copy(frame = frame, state = SendState.TRANSMITTING, updatedAt = now(), error = ""))
        scope.launch {
            val result = runCatching { wire.request(Up.SUBMIT, frame) }
            enqueue {
                val current = rows[row.messageId]
                if (current != null) {
                    val receipt = result.getOrNull()
                    val mismatch = receipt?.text("message_id")?.takeIf { it.isNotEmpty() && it != row.messageId }
                    if (result.isSuccess && mismatch == null) {
                        update(current.copy(state = SendState.ACCEPTED, updatedAt = now()))
                        scope.launch { delay(delayedAfterMs); enqueue { rows[row.messageId]?.takeIf { it.state == SendState.ACCEPTED }?.let { update(it.copy(state = SendState.DELAYED, updatedAt = now())) } } }
                    } else {
                        val failure = result.exceptionOrNull() ?: ComposerFailure("message_id_mismatch", "服务端返回了不同的消息编号")
                        update(current.copy(state = failureState(failure), updatedAt = now(), error = failure.message.orEmpty()))
                    }
                }
                inFlight.remove(row.messageId)
                landed.remove(row.messageId)
                // Only a queued cancellation wakes here. Failed sends wait for reconnect
                // or explicit retry, preventing a fast loop on a refused transport.
                if (rows[row.messageId]?.cancelRequested == true && online) pump()
            }
        }
    }
    private fun transmitCancel(row: LocalSubmission) {
        inFlight.add(row.messageId)
        scope.launch {
            val result = runCatching { wire.request(Up.CANCEL, buildJsonObject { put("channel_id", row.channelId); put("req_id", row.messageId) }) }
            enqueue {
                val error = result.exceptionOrNull()
                if (result.isSuccess || error is WireException && error.code == "already_closed") { remove(row.messageId); publish() }
                else rows[row.messageId]?.let { update(it.copy(state = SendState.UNCERTAIN, error = "取消待确认：${error?.message.orEmpty()}", updatedAt = now())) }
                inFlight.remove(row.messageId)
            }
        }
    }
    private fun prepareFrame(frame: JsonObject): JsonObject {
        if (frame.text("msg_type") != "agent.ask") return frame
        val body = frame["payload"] as? JsonObject ?: return frame
        if ("origin" in body) return frame
        val receipt = (wire.state.value as? WireState.Attached)?.receipt ?: return frame
        val session = receipt.text("session")
        if (session.isEmpty()) return frame
        return JsonObject(frame + ("payload" to JsonObject(body + ("origin" to buildJsonObject { put("session", session); receipt.text("label").takeIf { it.isNotEmpty() }?.let { put("label", it) } }))))
    }
    private suspend fun update(row: LocalSubmission) { repository.putMany(listOf(row)); rows[row.messageId] = row; publish() }
    private suspend fun remove(id: String) { repository.remove(id); rows.remove(id); automaticallyRetried.remove(id); if (id !in inFlight) landed.remove(id) }
    private fun publish() {
        mutable.value = rows.values.toList()
        expiryJob?.cancel()
        val earliest = rows.values.map { it.expiresAt }.filter { it > 0 }.minOrNull()
        expiryJob = earliest?.let { expiry -> scope.launch { delay((expiry - now()).coerceAtLeast(1)); enqueue { pump() } } }
    }
    private fun enqueue(command: suspend () -> Unit) { commands.trySend(command) }
    private suspend fun action(command: suspend () -> Unit) {
        ready.await()
        val result = CompletableDeferred<Unit>(scope.coroutineContext[Job])
        check(commands.trySend { try { command(); result.complete(Unit) } catch (e: Throwable) { result.completeExceptionally(e) } }.isSuccess) { "本机发送队列已关闭" }
        result.await()
    }
    override fun close() { commands.close(); scope.cancel() }
    companion object {
        fun failureState(error: Throwable): SendState = when ((error as? WireException)?.code) {
            "timeout", "closed" -> SendState.UNCERTAIN
            "unavailable", "channel_unavailable" -> SendState.QUEUED
            else -> if (error is java.io.IOException) SendState.UNCERTAIN else SendState.REJECTED
        }
    }
}

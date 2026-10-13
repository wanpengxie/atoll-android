package dev.atoll.app.waiting

import androidx.lifecycle.ViewModel
import dev.atoll.app.app.WorkspaceServices
import dev.atoll.app.compose.*
import dev.atoll.app.core.*
import dev.atoll.app.model.*
import dev.atoll.app.net.actorRequest
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.util.UUID

interface WaitingPort {
    suspend fun actor(actorId: String, type: String, payload: JsonObject, messageId: String): JsonObject
    suspend fun cancel(targetId: String)
    /** Must journal and use the frame's existing ID, just like normal composer sends. */
    suspend fun submit(frame: JsonObject, text: String, targetLabel: String): String
}

data class WaitingRow(val turn: Turn, val actions: WaitingActions, val labels: List<String>)
data class WaitingGroup(val actorId: String, val name: String, val rows: List<WaitingRow>, val paused: Boolean = false, val busy: Boolean = false)
data class WaitingUiState(val groups: List<WaitingGroup> = emptyList(), val collapsed: Boolean = false,
    val edit: EditState = EditState(), val warningTarget: Turn? = null, val warning: String = "", val error: String = "", val online: Boolean = false)

/** One writer, independent actor I/O. No queue reconstruction or network work in composition. */
class WaitingViewModel(
    val channelId: String,
    private val wire: WireClient,
    private val port: WaitingPort,
    snapshot: TimelineSnapshot,
    selfId: String,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel(), AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val events = Channel<() -> Unit>(Channel.UNLIMITED)
    private val mutable = MutableStateFlow(WaitingUiState())
    val state: StateFlow<WaitingUiState> = mutable.asStateFlow()
    private var latest = snapshot
    private var self = selfId
    private var writable = false
    private var detached = false
    private var active = true
    private var roster: Map<String, String> = emptyMap()
    private var actorIds: Set<String>? = null
    private val manifests = mutableMapOf<String, JsonObject>()
    private val probing = hashSetOf<String>()
    private val contextPending = hashSetOf<String>()
    private val contexts = mutableMapOf<String, JsonObject>()
    private val contextVersions = mutableMapOf<String, Long>()
    private val busy = hashSetOf<String>()
    private val local = mutableMapOf<String, LocalSubmission>()
    private val releases = mutableMapOf<String, EditSession>()
    private val releasing = hashSetOf<String>()
    private val holdsPending = hashSetOf<String>()
    private var leaseTimer: Job? = null
    init {
        require(snapshot.channelId == channelId)
        scope.launch { for (event in events) event() }
        scope.launch { wire.state.collect { connection -> enqueue {
            val attached = connection is WireState.Attached
            mutable.value = mutable.value.copy(online = attached)
            if (!attached) {
                transition(EditMachine.disconnected(mutable.value.edit))
                contexts.clear()
            } else {
                probeActors()
                releases.values.toList().forEach(::release)
            }
            rebuild()
        } } }
        scope.launch { wire.frames.filterIsInstance<DownFrame.Feed>().collect { feed -> if (feed.row.channelId == channelId) enqueue { observe(feed.row.envelope) } } }
        enqueue { rebuild() }
    }
    private fun enqueue(action: () -> Unit) { events.trySend(action) }
    fun updateSnapshot(snapshot: TimelineSnapshot, newerUnloaded: Boolean = false) = enqueue {
        require(snapshot.channelId == channelId)
        latest = snapshot; detached = newerUnloaded
        val warning = mutable.value.warningTarget
        if (detached || warning != null && turn(warning.requestId)?.terminal != null) mutable.value = mutable.value.copy(warningTarget = null, warning = "")
        if (detached) transition(EditMachine.exit(mutable.value.edit, "已退出编辑：正在浏览较早的历史"))
        val session = mutable.value.edit.session
        if (session != null) {
            transition(EditMachine.target(mutable.value.edit, turn(session.targetId)))
            turn(session.replacementId)?.let { replacement ->
                if (replacement.latestCoreStatus in setOf("queued", "processing") || replacement.terminal?.status == "completed") transition(EditMachine.accepted(mutable.value.edit, session.replacementId))
                else if (replacement.terminal?.status == "failed") transition(EditMachine.failed(mutable.value.edit, session.id, replacement.terminal.body.text("detail").ifEmpty { "修改请求被拒绝" }, definite = true))
            }
        }
        probeActors(); rebuild()
    }
    /** Call from the host's checked membership/roster facts, including while offline. */
    fun updateAccess(writable: Boolean, selfId: String, names: Map<String, String>, rosterCurrent: Boolean = true) = enqueue {
        this.writable = writable; self = selfId; roster = names.toMap(); actorIds = if (rosterCurrent) names.keys.toSet() else null
        val session = mutable.value.edit.session
        if (!writable) mutable.value = mutable.value.copy(warningTarget = null, warning = "")
        if (!writable || session != null && actorIds?.let { session.actorId !in it } == true) transition(EditMachine.exit(mutable.value.edit, "已退出编辑：当前目标不可写"))
        probeActors(); rebuild()
    }
    fun updateSubmissions(rows: List<LocalSubmission>) = enqueue {
        local.clear(); rows.filter { it.channelId == channelId }.forEach { local[it.messageId] = it }
        val session = mutable.value.edit.session
        if (session != null && session.replacementId.isNotEmpty()) local[session.replacementId]?.let {
            if (it.state == SendState.REJECTED) transition(EditMachine.failed(mutable.value.edit, session.id, it.error.ifEmpty { "修改请求发送失败" }, definite = true))
        }
        rebuild()
    }
    fun setCollapsed(value: Boolean) = enqueue { mutable.value = mutable.value.copy(collapsed = value) }
    fun clearError() = enqueue { mutable.value = mutable.value.copy(error = "", edit = mutable.value.edit.copy(notice = "", error = "")) }
    fun requestEdit(targetId: String) = enqueue {
        if (mutable.value.edit.phase != EditPhase.IDLE || mutable.value.warningTarget != null) return@enqueue
        val target = turn(targetId) ?: return@enqueue
        if (!mutable.value.online || !active || detached || target.actor() in busy || !actions(target).canEdit) {
            mutable.value = mutable.value.copy(error = if (!WaitingRules.leaseSupported(manifests[target.actor()])) "当前 Agent 不支持安全编辑" else "这条消息当前不能编辑")
            return@enqueue
        }
        val tasks = latest.runningTasks.filter { it.sender == target.actor() }
        if (tasks.isNotEmpty()) {
            val title = tasks.take(3).joinToString("、") { it.title.ifEmpty { it.kind } }
            mutable.value = mutable.value.copy(warningTarget = target, warning = "这个 Agent 还有 ${tasks.size} 个后台任务正在运行（$title）。编辑会打断当前回合，子 Agent 或后台命令可能随之停止。确定要编辑吗？")
        } else begin(target)
    }
    fun confirmEdit(confirmed: Boolean) = enqueue {
        val target = mutable.value.warningTarget
        mutable.value = mutable.value.copy(warningTarget = null, warning = "")
        val current = target?.let { turn(it.requestId) }
        if (confirmed && current != null && active && !detached && mutable.value.online && current.actor() !in busy && actions(current).canEdit) begin(current)
    }
    private fun begin(target: Turn) {
        val session = EditSession(UUID.randomUUID().toString(), target.actor(), target.requestId, UUID.randomUUID().toString(), target.exactText(),
            attachments = target.request.body["attachments"] as? JsonArray ?: JsonArray(emptyList()), originallyProcessing = target.controlFrame().text("status") == "processing",
            admitted = target.controlFrame().text("status") != "processing")
        transition(EditMachine.begin(mutable.value.edit, session)); rebuild()
    }
    fun editText(text: String, external: Boolean = false, selectionStart: Int = text.length, selectionEnd: Int = selectionStart) = enqueue {
        transition(EditMachine.input(mutable.value.edit, text, external, selectionStart, selectionEnd))
    }
    fun submitEdit(text: String? = null) = enqueue {
        if (text != null) transition(EditMachine.input(mutable.value.edit, text))
        if (!mutable.value.online || !writable) { transition(EditMachine.disconnected(mutable.value.edit)); return@enqueue }
        transition(EditMachine.submit(mutable.value.edit, UUID.randomUUID().toString())); rebuild()
    }
    fun cancelEdit() = enqueue { transition(EditMachine.exit(mutable.value.edit)); rebuild() }
    /** Leaving the surface releases its editor but keeps this channel's projection cached. */
    fun setActive(value: Boolean) = enqueue {
        active = value
        if (!value) transition(EditMachine.exit(mutable.value.edit))
        rebuild()
    }
    fun insert(targetId: String) = enqueue { turn(targetId)?.let { target -> actions(target).insert?.let { control(target, it) } } }
    fun extra(targetId: String, word: String) = enqueue { turn(targetId)?.let { target -> actions(target).extras.find { it.word == word }?.let { control(target, it) } } }
    fun cancel(targetId: String) = enqueue {
        val target = turn(targetId) ?: return@enqueue
        if (!actions(target).canCancel) return@enqueue
        action(target.actor()) { cancelOne(target) }
    }
    fun insertAll(actorId: String) = enqueue {
        if (latest.waitingTurns.none { it.actor() == actorId && actions(it).insert != null }) return@enqueue
        action(actorId) { port.actor(actorId, "agent.steer", buildJsonObject { put("all", true) }, UUID.randomUUID().toString()) }
    }
    fun cancelAll(actorId: String) = enqueue {
        val targets = latest.waitingTurns.filter { it.actor() == actorId && actions(it).canCancel }
        if (targets.isEmpty() || mutable.value.edit.session?.actorId == actorId) return@enqueue
        action(actorId) {
            val hold = UUID.randomUUID().toString()
            val failures = mutableListOf<String>()
            try {
                port.actor(actorId, "agent.hold", Envelope.EMPTY, hold)
                // The actor queue is FIFO; keep this sequence in order. Other actors remain parallel.
                for (target in targets) try { cancelOne(target) } catch (error: Exception) { failures += error.message.orEmpty() }
            } catch (error: Exception) { failures += error.message.orEmpty() }
            finally {
                // Exact CAS is safe even when the hold receipt/terminal was lost.
                try { port.actor(actorId, "agent.unhold", payload("expected_hold_id" to hold), UUID.randomUUID().toString()) }
                catch (error: Exception) { enqueue { releases[hold] = EditSession(hold, actorId, "", hold, ""); rebuild() }; failures += error.message.orEmpty() }
            }
            if (failures.isNotEmpty()) error(failures.joinToString("；"))
        }
    }
    private suspend fun cancelOne(target: Turn) {
        // Availability was captured before suspending. Each action still names one exact request.
        if (self.isNotEmpty() && target.request.senderId == self) port.cancel(target.requestId)
        else {
            val control = WaitingRules.actions(target, self, true, null, manifests[target.actor()]).dismiss ?: return
            port.actor(target.actor(), "agent.dismiss", control.payload, UUID.randomUUID().toString())
        }
    }
    private fun control(target: Turn, control: WaitingControl) = action(target.actor()) { port.actor(target.actor(), control.word, control.payload, UUID.randomUUID().toString()) }
    private fun action(actorId: String, operation: suspend () -> Unit) {
        if (!mutable.value.online || !writable || detached || !active || actorId in busy || mutable.value.edit.session?.actorId == actorId) return
        busy += actorId; rebuild()
        scope.launch {
            val result = runCatching { operation() }
            enqueue { busy -= actorId; result.exceptionOrNull()?.let { mutable.value = mutable.value.copy(error = it.message ?: "控制失败") }; refreshContext(actorId); rebuild() }
        }
    }
    private fun transition(next: EditTransition) {
        mutable.value = mutable.value.copy(edit = next.state)
        next.effects.forEach(::effect)
    }
    private fun effect(effect: EditEffect) {
        when (effect) {
            is EditEffect.Release -> release(effect.session)
            is EditEffect.Hold -> {
                holdsPending += effect.session.id
                scope.launch {
                    val result = runCatching { port.actor(effect.session.actorId, "agent.hold", payload("target" to effect.session.targetId), effect.session.holdId) }
                    enqueue {
                        holdsPending -= effect.session.id
                        if (result.isSuccess) transition(EditMachine.held(mutable.value.edit, effect.session))
                        else transition(EditMachine.failed(mutable.value.edit, effect.session.id, result.exceptionOrNull()?.message ?: "无法锁定这条任务", false))
                        rebuild()
                    }
                }
            }
            is EditEffect.Probe -> {
                val version = (contextVersions[effect.session.actorId] ?: 0L) + 1
                contextVersions[effect.session.actorId] = version
                scope.launch {
                    val result = runCatching { port.actor(effect.session.actorId, "agent.context", Envelope.EMPTY, UUID.randomUUID().toString()) }
                    enqueue {
                        if (result.isSuccess) {
                            if (contextVersions[effect.session.actorId] == version) contexts[effect.session.actorId] = result.getOrThrow()
                            transition(EditMachine.probed(mutable.value.edit, effect.session.id, result.getOrThrow()))
                            transition(EditMachine.target(mutable.value.edit, turn(effect.session.targetId)))
                            scheduleLeaseExpiry()
                        } else transition(EditMachine.failed(mutable.value.edit, effect.session.id, result.exceptionOrNull()?.message ?: "无法确认编辑暂停", false))
                        rebuild()
                    }
                }
            }
            is EditEffect.Replace -> scope.launch {
                val session = effect.session
                val frame = buildJsonObject {
                    put("channel_id", channelId); put("id", session.replacementId); put("kind", "request"); put("msg_type", "agent.replace")
                    put("visibility", "public"); put("audience", buildJsonArray { add(session.actorId) })
                    put("payload", buildJsonObject {
                        put("target", session.targetId); put("old_text", session.oldText); put("new_text", session.text); put("expected_hold_id", session.holdId)
                        if (session.attachments.isNotEmpty()) put("attachments", session.attachments)
                    })
                    put("expires_at_ms", minOf(now() + 30 * 60_000, session.leaseUntil.takeIf { it > 0 } ?: Long.MAX_VALUE))
                }
                val result = runCatching { port.submit(frame, session.text, roster[session.actorId] ?: session.actorId) }
                enqueue {
                    // A queued/processing signal may already have closed this session. Never reopen it.
                    if (result.isFailure) transition(EditMachine.failed(mutable.value.edit, session.id, result.exceptionOrNull()?.message ?: "无法保存修改", definite = true))
                    rebuild()
                }
            }
        }
    }
    private fun scheduleLeaseExpiry() {
        leaseTimer?.cancel()
        val session = mutable.value.edit.session ?: return
        if (session.leaseUntil <= 0) return
        leaseTimer = scope.launch {
            delay((session.leaseUntil - now()).coerceAtLeast(0))
            enqueue {
                if (mutable.value.edit.session?.id == session.id) {
                    transition(EditMachine.exit(mutable.value.edit, "已退出编辑：暂停已到期"))
                    rebuild()
                }
            }
        }
    }
    private fun release(session: EditSession) {
        releases[session.holdId] = session
        if (!mutable.value.online || !releasing.add(session.holdId)) return
        scope.launch {
            val result = runCatching { port.actor(session.actorId, "agent.unhold", payload("expected_hold_id" to session.holdId), UUID.randomUUID().toString()) }
            enqueue {
                releasing -= session.holdId
                val error = result.exceptionOrNull()
                if (result.isSuccess || (error as? WireException)?.code == "cas_mismatch") releases.remove(session.holdId)
                else mutable.value = mutable.value.copy(error = "已退出编辑，暂停解除待确认：${error?.message.orEmpty()}")
                refreshContext(session.actorId); rebuild()
            }
        }
    }
    private fun observe(envelope: Envelope) {
        val session = mutable.value.edit.session
        if (session != null) {
            val correlated = envelope.parentId.ifEmpty { envelope.correlationId }
            if (session.replacementId.isNotEmpty() && correlated == session.replacementId && envelope.senderId == session.actorId && envelope.kind == "response") {
                if (envelope.status in setOf("queued", "processing", "completed")) transition(EditMachine.accepted(mutable.value.edit, session.replacementId))
                else if (envelope.status == "failed") transition(EditMachine.failed(mutable.value.edit, session.id, envelope.body.text("detail").ifEmpty { "修改请求被拒绝" }, definite = true))
            } else if (correlated == session.targetId && envelope.isTerminal) {
                if (envelope.body.text("replaced_by") == session.replacementId && session.replacementId.isNotEmpty()) transition(EditMachine.accepted(mutable.value.edit, session.replacementId))
                else transition(EditMachine.exit(mutable.value.edit, "已退出编辑：这条请求已结束"))
            } else if (envelope.senderId == session.actorId && envelope.isTerminal && envelope.type == "agent.interrupt" && envelope.status == "completed") {
                val outcome = if (session.replacementId.isNotEmpty()) "修改结果待确认" else "改动没有发出，可以重新编辑"
                transition(EditMachine.exit(mutable.value.edit, "已停止这个 Agent，编辑一并退出；$outcome"))
            }
        }
        if (envelope.senderId.isNotEmpty() && (envelope.type == "agent.hold_expired" || envelope.isTerminal && envelope.type in setOf("agent.hold", "agent.unhold", "agent.interrupt"))) refreshContext(envelope.senderId)
        rebuild()
    }
    private fun turn(id: String): Turn? {
        if (id.isEmpty()) return null
        latest.waitingTurns.find { it.requestId == id }?.let { return it }
        fun find(entry: TimelineEntry): Turn? = entry.turn?.takeIf { it.requestId == id } ?: entry.thread.firstNotNullOfOrNull(::find)
        return latest.items.firstNotNullOfOrNull { find(it.entry) }
    }
    private fun actions(turn: Turn) = WaitingRules.actions(turn, self, writable, actorIds, manifests[turn.actor()])
    private fun probeActors() {
        if (!mutable.value.online || !writable || !active || detached) return
        val actors = (latest.waitingTurns.map { it.actor() } + latest.items.map { it.actorId }).filter { it.startsWith("agent:") }.distinct()
        actors.forEach { actor ->
            if (actor !in manifests && probing.add(actor)) scope.launch {
                val result = runCatching { port.actor(actor, "actor.describe", Envelope.EMPTY, UUID.randomUUID().toString()) }
                enqueue { probing -= actor; result.getOrNull()?.let { manifests[actor] = it }; rebuild() }
            }
            if (actor !in contexts) refreshContext(actor)
        }
    }
    private fun refreshContext(actor: String) {
        if (!mutable.value.online || !writable || !active || actor.isEmpty() || !contextPending.add(actor)) return
        val version = (contextVersions[actor] ?: 0L) + 1
        contextVersions[actor] = version
        val checkedSessionId = mutable.value.edit.session?.takeIf { it.held && it.probed }?.id
        scope.launch {
            val result = runCatching { port.actor(actor, "agent.context", Envelope.EMPTY, UUID.randomUUID().toString()) }
            enqueue {
                contextPending -= actor
                result.getOrNull()?.takeIf { contextVersions[actor] == version }?.let { body ->
                    contexts[actor] = body
                    val session = mutable.value.edit.session
                    if (session != null && session.id == checkedSessionId && session.actorId == actor && (body["frozen"] as? JsonObject)?.text("held_by") != session.holdId) transition(EditMachine.exit(mutable.value.edit, "已退出编辑：暂停已解除或被另一项控制接管"))
                }
                rebuild()
            }
        }
    }
    private fun rebuild() {
        val editing = mutable.value.edit.session
        val groups = if (detached || !active) emptyList() else latest.waitingTurns.groupBy { it.actor() }.map { (actor, turns) ->
            val frozen = contexts[actor]?.get("frozen") as? JsonObject
            val paused = frozen != null && (frozen.number("until") == 0L || frozen.number("until") > now())
            WaitingGroup(actor, roster[actor] ?: actor, turns.map { turn ->
                val actions = actions(turn)
                val submission = local[turn.requestId]
                val labels = buildList {
                    if (submission?.state == SendState.QUEUED && !mutable.value.online) add("等待连接后发出")
                    else if (submission != null && submission.state != SendState.REJECTED) add("确认中")
                    if (paused) add("已暂停")
                    if (actions.steering) add("正在并入…")
                    if (actions.departed) add("收件人已离席，等待账本关闭")
                    if (editing?.targetId == turn.requestId) add("正在编辑")
                    else if (!WaitingRules.leaseSupported(manifests[actor]) && (turn.controlFrame()["controls"] as? JsonArray).orEmpty().any { (it as? JsonObject)?.text("word") == "agent.replace" } && actor in manifests) add("Agent 不支持安全编辑")
                }
                WaitingRow(turn, actions, labels)
            }, paused, actor in busy || editing?.actorId == actor)
        }
        mutable.value = mutable.value.copy(groups = groups)
    }
    override fun close() {
        enqueue {
            active = false
            val session = mutable.value.edit.session
            transition(EditMachine.exit(mutable.value.edit)); leaseTimer?.cancel()
            // Wait for any pending hold ACK before final exact cleanup; it cannot reopen the editor.
            scope.launch {
                withTimeoutOrNull(30_000) { while (releasing.isNotEmpty() || holdsPending.isNotEmpty()) delay(20) }
                if (session != null && wire.state.value is WireState.Attached) runCatching { port.actor(session.actorId, "agent.unhold", payload("expected_hold_id" to session.holdId), UUID.randomUUID().toString()) }
                events.close(); scope.cancel()
            }
        }
    }
    override fun onCleared() = close()
}

/** Host passes its single composer so replace uses exactly the same durable outbox. */
fun createWaitingViewModel(services: WorkspaceServices, channelId: String, snapshot: TimelineSnapshot, composer: ComposerViewModel): WaitingViewModel =
    WaitingViewModel(channelId, services.wire, object : WaitingPort {
        override suspend fun actor(actorId: String, type: String, payload: JsonObject, messageId: String) = services.wire.actorRequest(channelId, actorId, type, payload, messageId = messageId)
        override suspend fun cancel(targetId: String) { services.wire.request(Up.CANCEL, payload("channel_id" to channelId, "req_id" to targetId)) }
        override suspend fun submit(frame: JsonObject, text: String, targetLabel: String) = composer.submitFrame(frame, text, targetLabel)
    }, snapshot, services.selfIds[channelId].orEmpty())

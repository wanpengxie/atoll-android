package dev.atoll.app.roster

import androidx.lifecycle.ViewModel
import dev.atoll.app.app.WorkspaceServices
import dev.atoll.app.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

/** No Android dependency in the configuration editor's values. */
data class MemberEditor(val kind: String, val info: JsonObject, val sourceMode: String = "class", val reference: String = "",
    val text: String = "{}", val selectionStart: Int = text.length, val selectionEnd: Int = selectionStart,
    val requires: String = "", val host: String = "")
data class RosterChoices(val descriptions: List<JsonObject> = emptyList(), val devices: List<JsonObject> = emptyList(),
    val agentClasses: List<String> = emptyList(), val globalNames: List<String>? = null, val loading: Boolean = false, val error: String = "")
data class RosterUiState(val rows: List<RosterActor> = emptyList(), val current: Boolean = false, val busy: Boolean = false,
    val selfId: String = "", val writable: Boolean = false, val online: Boolean = false, val error: String = "",
    val selected: RosterActor? = null, val capabilities: List<ActorCapability> = emptyList(), val describeBusy: Boolean = false,
    val member: JsonObject? = null, val memberBusy: Boolean = false, val detailError: String = "", val notice: String = "",
    val writeBusy: Boolean = false, val editor: MemberEditor? = null, val choices: RosterChoices = RosterChoices(), val invocationResult: JsonObject? = null) {
    val identityPending get() = selfId.isEmpty()
    val canWrite get() = online && writable && !writeBusy && selected != null && (!current || rows.any { it.id == selected.id })
    val editableMember get() = selected?.let { MemberConfig.editable(it.kind) || it.kind.isEmpty() && member != null && member.bool("generated") != true && member["body"] is JsonObject } == true
}

/** Per-channel feature owner. Refreshes coalesce; actor/detail I/O never blocks roster updates. */
class RosterViewModel(val channelId: String, private val wire: WireClient, private val port: RosterPort,
    selfId: String = "", dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val debounceMs: Long = 300) : ViewModel(), AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val events = Channel<() -> Unit>(Channel.UNLIMITED)
    private val mutable = MutableStateFlow(RosterUiState(selfId = selfId))
    val state: StateFlow<RosterUiState> = mutable.asStateFlow()
    private var generation = 0L
    private var selectionEpoch = 0L
    private var refreshPending = false
    private var refreshJob: Job? = null
    private var debounce: Job? = null
    private var closed = false
    private val writing = hashSetOf<String>()
    private val pretty = Json { prettyPrint = true }
    init {
        scope.launch { for (event in events) event() }
        scope.launch { wire.state.collect { connection -> enqueue {
            generation++
            refreshJob?.cancel(); refreshJob = null; refreshPending = false
            mutable.value = mutable.value.copy(online = connection is WireState.Attached, current = false, busy = false,
                selfId = if (connection is WireState.Attached) mutable.value.selfId else "", describeBusy = false, memberBusy = false)
            if (connection is WireState.Attached) {
                val self = connection.receipt.array("memberships").filterIsInstance<JsonObject>().find { it.text("channel_id") == channelId }?.text("actor_id")
                if (!self.isNullOrEmpty()) mutable.value = mutable.value.copy(selfId = self)
                refreshNow()
            }
        } } }
        scope.launch { wire.frames.filterIsInstance<DownFrame.Feed>().collect { feed -> if (feed.row.channelId == channelId && RosterModel.refreshesRoster(feed.row.envelope)) enqueue { scheduleRefresh() } } }
    }
    private fun enqueue(action: () -> Unit) { if (!closed) events.trySend(action) }
    fun updateAccess(writable: Boolean, selfId: String) = enqueue { mutable.value = mutable.value.copy(writable = writable, selfId = if (mutable.value.online) selfId else "") }
    fun refresh() = enqueue { refreshNow() }
    private fun scheduleRefresh() {
        debounce?.cancel()
        debounce = scope.launch { delay(debounceMs); enqueue { refreshNow() } }
    }
    private fun refreshNow() {
        if (!mutable.value.online) return
        if (refreshJob?.isActive == true) { refreshPending = true; return }
        val owner = generation
        mutable.value = mutable.value.copy(busy = true, error = "")
        refreshJob = scope.launch {
            val result = runCatching { port.roster() }
            enqueue {
                if (owner != generation) return@enqueue
                refreshJob = null
                val observation = result.getOrNull()
                if (observation != null) {
                    val rows = RosterModel.rows(observation)
                    val selected = mutable.value.selected
                    mutable.value = mutable.value.copy(rows = rows, current = observation.bool("complete") != false, busy = false,
                        selected = rows.find { it.id == selected?.id } ?: selected)
                } else mutable.value = mutable.value.copy(busy = false, current = false, error = failureText(result.exceptionOrNull()))
                if (refreshPending) { refreshPending = false; refreshNow() }
            }
        }
    }
    /** Governance's “查看” can pass an ID or a roster record from its own directory. */
    fun select(actorId: String) = enqueue {
        val actor = mutable.value.rows.find { it.id == actorId } ?: RosterActor(actorId, actorId)
        selectNow(actor)
    }
    fun select(actor: RosterActor) = enqueue { selectNow(actor) }
    private fun selectNow(actor: RosterActor) {
        selectionEpoch++
        mutable.value = mutable.value.copy(selected = actor, capabilities = emptyList(), member = null, editor = null, choices = RosterChoices(),
            describeBusy = false, memberBusy = false, writeBusy = actor.id in writing, detailError = "", notice = "", invocationResult = null)
    }
    fun clearSelection() = enqueue { selectionEpoch++; mutable.value = mutable.value.copy(selected = null, editor = null, member = null, detailError = "", notice = "") }
    fun describe() = enqueue {
        val actor = mutable.value.selected ?: return@enqueue
        if (!mutable.value.online || mutable.value.describeBusy) return@enqueue
        val epoch = selectionEpoch
        val owner = generation
        mutable.value = mutable.value.copy(describeBusy = true, detailError = "")
        scope.launch {
            val result = runCatching { port.actor(actor.id, "actor.describe", MemberConfig.empty) }
            enqueue { if (epoch == selectionEpoch && owner == generation) mutable.value = mutable.value.copy(describeBusy = false, capabilities = result.getOrNull()?.let(RosterModel::capabilities) ?: mutable.value.capabilities, detailError = result.exceptionOrNull()?.let(::failureText).orEmpty()) }
        }
    }
    fun readMember() = enqueue { readNow() }
    private fun readNow() {
        val actor = mutable.value.selected ?: return
        if (!mutable.value.online || mutable.value.memberBusy || mutable.value.editor != null) return
        val epoch = selectionEpoch
        val owner = generation
        mutable.value = mutable.value.copy(memberBusy = true, detailError = "")
        scope.launch {
            val result = runCatching { port.actor("system", "system.member.get", memberPayload(actor)) }
            enqueue { if (epoch == selectionEpoch && owner == generation) mutable.value = mutable.value.copy(memberBusy = false, member = result.getOrNull() ?: mutable.value.member, detailError = result.exceptionOrNull()?.let(::failureText).orEmpty()) }
        }
    }
    fun openEditor(kind: String) = enqueue {
        val state = mutable.value; val info = state.member ?: return@enqueue
        if (!state.canWrite || !state.editableMember || info.bool("generated") == true || info["body"] !is JsonObject || state.memberBusy) return@enqueue
        require(kind in setOf("entry", "config"))
        val body = info.obj("body"); val own = info.obj("own_config")
        val text = pretty.encodeToString(JsonObject.serializer(), if (kind == "entry") info.obj("params") else own.obj("values"))
        mutable.value = state.copy(editor = MemberEditor(kind, info, if (body.text("actor").isNotEmpty()) "actor" else "class", body.text("actor").ifEmpty { body.text("class") },
            text, requires = info.array("requires").map { (it as? JsonPrimitive)?.content.orEmpty() }.joinToString(", "), host = own.text("desired_host")), choices = RosterChoices(loading = true), detailError = "", notice = "")
        loadChoices(kind, info.text("class"))
    }
    private fun loadChoices(kind: String, klass: String) {
        val epoch = selectionEpoch
        scope.launch {
            val descriptions = async { if (kind == "entry") runCatching { port.descriptions() } else Result.success(emptyList()) }
            val devices = async { if (kind == "config") runCatching { port.devices() } else Result.success(emptyList()) }
            val globals = async { if (kind == "config") runCatching { port.globalNames() } else Result.success(emptyList()) }
            val classes = async { if (kind == "config" && klass == "agent") runCatching { port.actor("system", "system.class.list", MemberConfig.empty) } else Result.success(MemberConfig.empty) }
            val d = descriptions.await(); val v = devices.await(); val g = globals.await(); val c = classes.await()
            enqueue { if (epoch == selectionEpoch && mutable.value.editor?.kind == kind) mutable.value = mutable.value.copy(choices = RosterChoices(d.getOrDefault(emptyList()), v.getOrDefault(emptyList()),
                c.getOrNull()?.array("value")?.filterIsInstance<JsonObject>()?.filter { it.text("kind") == "agent" && it.text("class") != "agent" }?.map { it.text("class") }.orEmpty(),
                g.getOrNull(), false, listOf(d.exceptionOrNull(), v.exceptionOrNull(), g.exceptionOrNull(), c.exceptionOrNull()).filterNotNull().joinToString("；", transform = ::failureText))) }
        }
    }
    fun updateEditor(editor: MemberEditor) = enqueue { if (mutable.value.editor != null && !mutable.value.writeBusy) mutable.value = mutable.value.copy(editor = editor, detailError = "") }
    fun closeEditor() = enqueue { if (!mutable.value.writeBusy) mutable.value = mutable.value.copy(editor = null, choices = RosterChoices(), detailError = "") }
    fun chooseAgentClass(klass: String) = enqueue {
        val editor = mutable.value.editor ?: return@enqueue
        if (klass !in mutable.value.choices.agentClasses || mutable.value.writeBusy) return@enqueue
        try {
            val value = JsonObject(MemberConfig.parse(editor.text) + ("agent" to JsonPrimitive(klass)))
            val text = pretty.encodeToString(JsonObject.serializer(), value)
            mutable.value = mutable.value.copy(editor = editor.copy(text = text, selectionStart = text.length, selectionEnd = text.length))
        } catch (error: Exception) { mutable.value = mutable.value.copy(detailError = failureText(error)) }
    }
    fun insertReference(name: String) = enqueue {
        val editor = mutable.value.editor ?: return@enqueue
        if (name !in mutable.value.choices.globalNames.orEmpty() || mutable.value.writeBusy) return@enqueue
        val next = MemberConfig.insertGlobal(editor.text, editor.selectionStart, editor.selectionEnd, name)
        mutable.value = mutable.value.copy(editor = editor.copy(text = next.text, selectionStart = next.cursor, selectionEnd = next.cursor))
    }
    fun saveEditor() = enqueue {
        val state = mutable.value; val actor = state.selected ?: return@enqueue; val editor = state.editor ?: return@enqueue
        if (!state.canWrite || !state.editableMember) return@enqueue
        try {
            val change = if (editor.kind == "entry") MemberConfig.entryChange(actor.memberId, editor.info, editor.sourceMode, editor.reference, editor.text, editor.requires)
                else MemberConfig.configChange(actor.memberId, editor.info, editor.host, editor.text)
            if (!change.changed) return@enqueue
            write(actor, change.word, change.payload) { value ->
                val revision = value[if (editor.kind == "entry") "description_revision" else "revision"]?.let { (it as? JsonPrimitive)?.content }.orEmpty().ifEmpty { "?" }
                mutable.value = mutable.value.copy(editor = null, notice = "${if (editor.kind == "entry") "成员条目已写进频道描述" else "这一台的配置已保存"}（第 $revision 版）；成员会按新配置重建，构建结果点「刷新」查看。")
                readNow()
            }
        } catch (error: Exception) { mutable.value = mutable.value.copy(detailError = failureText(error)) }
    }
    fun fillPlaceholder(key: String, value: String) = enqueue {
        val actor = mutable.value.selected ?: return@enqueue
        if (!mutable.value.canWrite || !mutable.value.editableMember || value.isEmpty() || MemberConfig.missing(mutable.value.member).none { it.key == key }) return@enqueue
        val body = JsonObject(memberPayload(actor) + ("values" to MemberConfig.patchAtPath(key, JsonPrimitive(value))))
        write(actor, "system.member.config.set", body) { reply -> mutable.value = mutable.value.copy(notice = "已填 $key（配置第 ${reply.number("revision")} 版）；成员会重建，构建结果点「刷新」查看。"); readNow() }
    }
    fun invoke(word: String, arguments: String) = enqueue {
        val actor = mutable.value.selected ?: return@enqueue
        if (!mutable.value.canWrite || mutable.value.capabilities.none { it.type == word }) return@enqueue
        try { write(actor, word, MemberConfig.parse(arguments), target = actor.id) { reply -> mutable.value = mutable.value.copy(invocationResult = reply, notice = "调用已完成") } }
        catch (error: Exception) { mutable.value = mutable.value.copy(detailError = failureText(error)) }
    }
    private fun write(actor: RosterActor, word: String, body: JsonObject, target: String = "system", succeeded: (JsonObject) -> Unit) {
        if (!writing.add(actor.id)) return
        val epoch = selectionEpoch
        mutable.value = mutable.value.copy(writeBusy = true, detailError = "", notice = "")
        scope.launch {
            val result = runCatching { port.actor(target, word, body) }
            enqueue {
                writing -= actor.id
                if (epoch != selectionEpoch) {
                    if (mutable.value.selected?.id == actor.id) mutable.value = mutable.value.copy(writeBusy = false)
                    return@enqueue
                }
                mutable.value = mutable.value.copy(writeBusy = false)
                result.getOrNull()?.let(succeeded) ?: run { mutable.value = mutable.value.copy(detailError = failureText(result.exceptionOrNull())) }
            }
        }
    }
    private fun memberPayload(actor: RosterActor) = buildJsonObject { put("member", actor.memberId) }
    override fun close() { if (closed) return; closed = true; debounce?.cancel(); events.close(); scope.cancel() }
    override fun onCleared() = close()
}
private fun failureText(error: Throwable?): String = if (error is WireException) "${error.code}：${error.message}" else error?.message ?: "读取失败"
fun createRosterViewModel(services: WorkspaceServices, channelId: String) = RosterViewModel(channelId, services.wire, RosterRepository(services, channelId), services.selfIds[channelId].orEmpty())

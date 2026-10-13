package dev.atoll.app.governance

import androidx.lifecycle.ViewModel
import dev.atoll.app.core.*
import dev.atoll.app.app.WorkspaceServices
import dev.atoll.app.tasks.ActivityOperation
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import java.util.UUID

data class GovernanceState(val channel: String = "", val selfId: String = "", val writable: Boolean = false,
    val memberChannels: Set<String> = emptySet(),
    val directory: GovernanceDirectory = GovernanceDirectory(), val loading: Boolean = false, val busy: Boolean = false,
    val message: String = "", val error: String = "", val channelView: JsonObject? = null,
    val entries: List<JsonObject>? = null, val memberView: JsonObject? = null, val keyNames: List<String>? = null,
    val introducedPrincipal: String = "", val introducedEntry: String = "") {
    val memberReady: Boolean get() = directory.actors.any { row ->
        val d = row.declared()
        (introducedPrincipal.isNotEmpty() && d.text("principal") == introducedPrincipal) ||
            (introducedEntry.isNotEmpty() && d.text("entry_id") == introducedEntry)
    }
}

/** One world-scoped owner. I/O suspends off main; callbacks update state on its scope. */
class GovernanceViewModel(private val port: GovernancePort, val principalId: String,
    frames: SharedFlow<DownFrame>, private val now: () -> Long = System::currentTimeMillis,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate) : ViewModel(), AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutableState = MutableStateFlow(GovernanceState())
    val state = mutableState.asStateFlow()
    private val mutableCreations = MutableStateFlow<List<ChannelCreation>>(emptyList())
    val creations = mutableCreations.asStateFlow()
    val activityOperations: StateFlow<List<ActivityOperation>> = creations.map(::governanceActivityOperations).stateIn(scope,SharingStarted.Eagerly,emptyList())
    private var memberships: Set<String> = emptySet()
    private var knownChannels: List<JsonObject> = emptyList()
    private var directoryJob: Job? = null
    private var operationJob: Job? = null
    private var contextRevision = 0L
    private val pollers = mutableMapOf<String, Job>()
    init { scope.launch(start = CoroutineStart.UNDISPATCHED) { frames.collect { frame ->
        if (frame is DownFrame.Feed) {
            val relevant = creations.value.filter { operation ->
                frame.row.channelId == operation.parentId && (frame.row.envelope.id == operation.requestId || frame.row.envelope.parentId == operation.requestId || frame.row.envelope.correlationId == operation.requestId)
            }.map { it.requestId }.toSet()
            if (relevant.isEmpty()) return@collect
            mutableCreations.value = mutableCreations.value.map { operation ->
                if (frame.row.channelId != operation.parentId) operation else {
                    val envelope = frame.row.envelope
                    if (envelope.kind == "request" && envelope.id == operation.requestId) operation.copy(ledger = true)
                    else operation.terminal(envelope).converge(knownChannels, memberships)
                }
            }
            creations.value.filter { it.requestId in relevant && it.targetId.isNotEmpty() && !it.failed && !it.ready }.forEach { poll(it.requestId) }
        }
    } } }
    fun context(channel: String, selfId: String, writable: Boolean, memberChannels: Set<String>) {
        memberships = memberChannels
        mutableCreations.value = creations.value.map { it.converge(knownChannels, memberships) }
        if (state.value.channel != channel) {
            contextRevision++; directoryJob?.cancel(); operationJob?.cancel()
            mutableState.value = GovernanceState(channel, selfId, writable, memberChannels)
        } else mutableState.value = state.value.copy(selfId = selfId, writable = writable, memberChannels = memberChannels)
    }
    fun refresh() {
        val channel = state.value.channel; if (channel.isEmpty()) return
        val revision = contextRevision
        directoryJob?.cancel()
        directoryJob = scope.launch {
            mutableState.value = state.value.copy(loading = true, error = "")
            try {
                val directory = port.directory(channel)
                if (revision != contextRevision) return@launch
                knownChannels = directory.channels
                mutableState.value = state.value.copy(directory = directory)
                mutableCreations.value = creations.value.map { it.converge(knownChannels, memberships) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (revision == contextRevision) mutableState.value = state.value.copy(error = errorText(failure)) }
            finally { if (revision == contextRevision) mutableState.value = state.value.copy(loading = false) }
        }
    }
    fun execute(command: GovernanceCommand, success: String = "操作终态已确认") = runOperation(write = true) { channel ->
        val value = port.execute(channel, command)
        mutableState.value = state.value.copy(message = success,
            introducedPrincipal = when (command.word) { "system.member.admit" -> command.payload.text("principal"); "system.member.create" -> ""; else -> state.value.introducedPrincipal },
            introducedEntry = when (command.word) { "system.member.create" -> value.obj("entry").text("id"); "system.member.admit" -> ""; else -> state.value.introducedEntry })
        refresh()
        if (command.word in setOf("system.channel.set","system.device.attach","system.device.detach")) {
            mutableState.value = state.value.copy(channelView = port.execute(channel,GovernanceCommands.channelGet(channel)))
        }
    }
    fun readChannel() = runOperation(write = false) { channel ->
        mutableState.value = state.value.copy(channelView = port.execute(channel, GovernanceCommands.channelGet(channel)))
    }
    fun readEntries() = runOperation(write = false) { channel ->
        val value = port.execute(channel, GovernanceCommands.descriptionGet(channel))
        val entries = (value.obj("body")["members"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.filter { !it.obj("body").bool("human") }.orEmpty()
        mutableState.value = state.value.copy(entries = entries)
    }
    fun readMember(id: String) = runOperation(write = false) { channel ->
        mutableState.value = state.value.copy(memberView = port.execute(channel,
            GovernanceCommand("system.member.get", buildJsonObject { put("member", id) })))
    }
    fun dismissMember() { mutableState.value = state.value.copy(memberView = null) }
    fun keyNames() = runOperation(write = false) { channel -> mutableState.value = state.value.copy(keyNames = GlobalKeys(port, channel).names()) }
    fun saveKey(name: String, value: String, overwrite: Boolean) = runOperation(write = true) { channel ->
        val keys = GlobalKeys(port, channel); keys.save(name, value, overwrite)
        mutableState.value = state.value.copy(message = "全局 key 已保存", keyNames = keys.names())
    }
    fun deleteKey(name: String) = runOperation(write = true) { channel ->
        val keys = GlobalKeys(port, channel); keys.delete(name)
        mutableState.value = state.value.copy(message = "全局 key 已删除", keyNames = keys.names())
    }
    private fun runOperation(write: Boolean, operation: suspend (String) -> Unit) {
        if (state.value.busy || state.value.channel.isEmpty()) return
        if (write && !state.value.writable) { mutableState.value = state.value.copy(error = "当前频道未确认可写"); return }
        val revision = contextRevision
        val channel = state.value.channel
        mutableState.value = state.value.copy(busy = true, error = "", message = "")
        operationJob = scope.launch {
            try { operation(channel) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (revision == contextRevision) mutableState.value = state.value.copy(error = errorText(failure)) }
            finally { if (revision == contextRevision) mutableState.value = state.value.copy(busy = false) }
        }
    }
    fun create(form: ChannelCreateForm): String? {
        if (!state.value.writable || form.parent != state.value.channel || form.selfPrincipal != principalId) { mutableState.value = state.value.copy(error = "当前频道或账户未确认可写"); return null }
        if (creations.value.any { it.parentId == form.parent && !it.ready && !it.failed }) return null
        val command = try { GovernanceCommands.channelCreate(form) } catch (failure: Exception) {
            mutableState.value = state.value.copy(error = errorText(failure)); return null
        }
        mutableState.value = state.value.copy(error = "",message = "")
        val id = UUID.randomUUID().toString()
        mutableCreations.value = (creations.value + ChannelCreation(id, form.parent, form.name.trim(), now())).takeLast(50)
        scope.launch {
            try {
                val reply = port.execute(form.parent, command, id) { updateCreation(id) { it.copy(accepted = true) } }
                updateCreation(id) { it.result(reply).converge(knownChannels, memberships) }
                poll(id)
            } catch (timeout: TimeoutCancellationException) {
                updateCreation(id) { if (it.targetId.isNotEmpty() || it.failed) it else it.copy(uncertain = true, failed = false, error = "等待终态超时；可刷新，勿重复创建。") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                updateCreation(id) {
                    if (it.targetId.isNotEmpty() || it.failed) it else {
                        val definite = failure is WireException && failure.code !in setOf("closed", "unavailable", "version_incompatible")
                        it.copy(failed = definite, uncertain = !definite, error = errorText(failure))
                    }
                }
            }
        }
        return id
    }
    fun refreshCreation(id: String) { pollers.remove(id)?.cancel(); poll(id) }
    private fun poll(id: String) {
        if (pollers[id]?.isActive == true) return
        pollers[id] = scope.launch {
            repeat(30) {
                try {
                    val parent = creations.value.firstOrNull { it.requestId==id }?.parentId ?: return@launch
                    val children = port.channels(parent)
                    val ids = children.map { it.directoryId() }.toSet()
                    knownChannels = knownChannels.filterNot { it.declared().text("parent_id")==parent || it.directoryId() in ids } + children
                    updateCreation(id) { it.converge(knownChannels, memberships) }
                    if (creations.value.firstOrNull { it.requestId == id }?.let { it.ready || it.failed } != false) return@launch
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { updateCreation(id) { it.copy(error = "目录刷新失败：${errorText(failure)}") } }
                delay(2_000)
            }
        }
    }
    private fun updateCreation(id: String, transform: (ChannelCreation) -> ChannelCreation) {
        mutableCreations.value = creations.value.map { if (it.requestId == id) transform(it) else it }
    }
    override fun close() { scope.cancel() }
    override fun onCleared() { close() }
    private fun errorText(failure: Exception): String = if (failure is WireException) "${failure.code}：${failure.message.orEmpty()}" else failure.message ?: "操作失败"
}

/** Workspace mounts this once and publishes its current access/membership facts through context(). */
fun createGovernanceViewModel(services: WorkspaceServices,channelId: String):GovernanceViewModel =
    GovernanceViewModel(GovernanceRepository(services.wire,services.obs),services.principalId,services.wire.frames).also {
        it.context(channelId,services.selfIds[channelId].orEmpty(),false,services.selfIds.keys)
    }
fun governanceActivityOperations(creations: List<ChannelCreation>):List<ActivityOperation> = creations.map { item ->
    ActivityOperation("channel-create:${item.requestId}",item.parentId,"创建频道 ${item.name}",when { item.failed -> "failed";item.ready -> "completed";item.uncertain -> "uncertain";else -> "active" },
        item.error.ifEmpty { "${listOf(item.ledger,item.observable,item.membership,item.serving).count { it }}/4 步已确认" },item.startedAt,
        buildJsonObject { put("channelId",item.parentId);put("view","conversation");put("objectType","turn");put("objectId",item.requestId);put("requestId",item.requestId) })
}

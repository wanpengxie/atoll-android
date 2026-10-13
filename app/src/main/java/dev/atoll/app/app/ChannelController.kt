package dev.atoll.app.app

import dev.atoll.app.core.*
import dev.atoll.app.model.*
import dev.atoll.app.store.RowStore
import dev.atoll.app.sync.ChannelSync
import dev.atoll.app.sync.SyncState
import dev.atoll.app.ui.screens.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import dev.atoll.app.tasks.TaskFact
import dev.atoll.app.tasks.ActivityAgentRun

data class ChannelRuntimeState(
    val sync: SyncState = SyncState(),
    val unread: UnreadCount = UnreadCount(pending = true),
    val activity: List<AgentActivityViewState> = emptyList(),
    val conversation: ConversationViewState? = null,
    val newerUnloaded: Boolean = false,
    val firstOrigin: Origin? = null,
    val error: String? = null,
    val timeline: TimelineSnapshot? = null,
    val timelineSource: TimelineSource? = null,
    val taskFacts: List<TaskFact> = emptyList(),
    val agentRuns: List<ActivityAgentRun> = emptyList(),
    val browsePosition: BrowsePosition? = null,
    val restoreNotice: String? = null,
    val rowSequences: Map<String, Long> = emptyMap(),
)

/** A light serial owner per membership. Only the selected owner's replica is retained. */
class ChannelController(
    val id: String,
    val selfId: String,
    private val sync: ChannelSync,
    private val store: RowStore,
    private val preferences: ViewPreferences,
    private val namespace: String,
    private val baselineHead: Long,
    parentScope: CoroutineScope,
) {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job + Dispatchers.Default)
    private val commands = Channel<Command>(128)
    private val mutableState = MutableStateFlow(ChannelRuntimeState())
    val state: StateFlow<ChannelRuntimeState> = mutableState.asStateFlow()
    private var syncState = sync.state.value
    private var record = ChannelRecord(id, id, null, id)
    private var actors = emptyList<ActorRecord>()
    private val activity = linkedMapOf<String, AgentActivityViewState>()
    private var browsePosition: BrowsePosition? = null
    private var restoreNotice: String? = null
    private var readPosition = 0L
    private var unread = UnreadCount(pending = true)
    private var options = ChannelViewOptions()
    private var replica: ChannelReplica? = null
    private var mapper = TimelineMapper()
    private var conversation: ConversationViewState? = null
    private var timeline: TimelineSnapshot? = null
    private var rowSequences = emptyMap<String, Long>()
    private var localEchoes = emptyList<dev.atoll.app.model.TimelineEntry>()
    private var timelinePlaced = emptySet<String>()
    private var timelineSource: TimelineSource? = null
    private var taskFacts: List<TaskFact> = emptyList()
    private var taskFactsDirty = true
    private var activityExpiry: Job? = null
    private var following = true
    private var foreground = true
    private var newerUnloaded = false
    private var windowHigh = Long.MAX_VALUE
    private var reader: Job? = null
    private var readerEpoch = 0L
    private var refresh: Job? = null
    private var older: Job? = null
    private var firstOrigin: Origin? = null
    private var error: String? = null
    private var notificationDirty = false
    private val newRoots = mutableSetOf<String>()

    init {
        scope.launch {
            try {
                readPosition = store.channel(id).meta.readPosition
                if (preferences.get(namespace, "$id.baselined") == null) {
                    readPosition = maxOf(readPosition, baselineHead)
                    sync.markRead(readPosition)
                    preferences.put(namespace, "$id.baselined", "true")
                }
                options = preferences.options(namespace, id)
                browsePosition = BrowsePosition.decode(preferences.get(namespace, "$id.browse"))
                queueUnread()
                for (command in commands) {
                    try { handle(command) }
                    catch (failure: Exception) {
                        if (failure is CancellationException) throw failure
                        error = failure.message ?: "频道加载失败"
                        publish()
                    }
                }
            } finally { reader?.cancel(); refresh?.cancel(); older?.cancel(); activityExpiry?.cancel(); commands.close() }
        }
        scope.launch { sync.state.collect { commands.send(Sync(it)) } }
    }

    suspend fun open(record: ChannelRecord) { commands.send(Open(record)) }
    suspend fun closeView() { commands.send(CloseView) }
    suspend fun receive(row: Row) { commands.send(Live(row)) }
    suspend fun roster(actors: List<ActorRecord>) { commands.send(Roster(actors)) }
    suspend fun follow(value: Boolean) { commands.send(Follow(value)) }
    suspend fun foreground(value: Boolean) { commands.send(Foreground(value)) }
    suspend fun toggleScope() { commands.send(ChangeScope) }
    suspend fun filter(actor: String?) { commands.send(Filter(actor)) }
    suspend fun loadOlder() { commands.send(LoadOlder) }
    suspend fun retry() { commands.send(Retry) }
    suspend fun browse(key: String, offset: Int, following: Boolean) { commands.send(Browse(key, offset, following)) }
    suspend fun jumpToBottom() { commands.send(Jump) }
    suspend fun visibleRows(keys: Set<String>) { commands.send(VisibleRows(keys)) }
    suspend fun localPlacement(echoes: List<dev.atoll.app.model.TimelineEntry>, placed: Set<String>) { commands.send(LocalPlacement(echoes, placed)) }
    suspend fun stop() { job.cancelAndJoin() }

    private suspend fun handle(command: Command) {
        when (command) {
            is Open -> { record = command.record; loadTail() }
            CloseView -> { readerEpoch++; reader?.cancelAndJoin(); reader = null; replica = null; mapper = TimelineMapper(); conversation = null; timeline = null; timelineSource = null; firstOrigin = null; rowSequences = emptyMap(); localEchoes = emptyList(); timelinePlaced = emptySet() }
            is Batch -> if (command.epoch == readerEpoch) {
                if (command.restored) {
                    newerUnloaded = command.batch.rows.lastOrNull()?.seq?.let { it < command.head } == true
                    windowHigh = if (newerUnloaded) command.batch.rows.last().seq else Long.MAX_VALUE
                }
                restoreNotice = command.notice ?: restoreNotice
                apply(command.batch)
            }
            is ReadFailed -> if (command.epoch == readerEpoch) error = command.message
            Retry -> { error = null; sync.retry(); loadTail() }
            is Sync -> {
                if (syncState.generation != command.value.generation) activity.clear()
                val recovered = !syncState.current && command.value.current
                val advanced = syncState.headSeq != command.value.headSeq
                if (advanced) taskFactsDirty = true
                syncState = command.value
                if (readPosition >= syncState.headSeq) unread = UnreadCount()
                else if (!syncState.current) unread = unread.copy(pending = true)
                if (recovered || advanced && (notificationDirty || taskFactsDirty)) queueUnread()
                conversation = conversation?.copy(connection = if (syncState.connected) "已连接" else "离线缓存")
            }
            is Live -> {
                val envelope = command.row.envelope
                taskFactsDirty = true
                queueUnread()
                if (envelope.kind == "response" && envelope.senderKind == "agent" && envelope.parentId.isNotEmpty()) {
                    if (envelope.isTerminal) activity.remove(envelope.parentId)
                    else if (envelope.status == "processing") activity.putIfAbsent(envelope.parentId,
                        AgentActivityViewState(envelope.senderId, name(envelope.senderId), envelope.tsReceived.takeIf { it > 0 } ?: envelope.ts.takeIf { it > 0 } ?: System.currentTimeMillis()))
                }
                if (candidate(envelope)) {
                    notificationDirty = true
                    queueUnread()
                    if (replica != null && (!following || newerUnloaded || !foreground) && envelope.senderId != selfId) {
                        newRoots += envelope.parentId.ifEmpty { envelope.id }
                    }
                }
                updateBadges()
            }
            is Roster -> { actors = command.actors; activity.replaceAll { _, value -> value.copy(name = name(value.id)) }; project() }
            is Follow -> { following = command.value }
            is Foreground -> { foreground = command.value }
            is VisibleRows -> markSeen(command.keys)
            is Browse -> {
                val seq = rowSequences[command.key] ?: 0
                if (seq > 0 && replica != null && foreground) {
                    val value = BrowsePosition(command.key, seq, command.offset.coerceAtLeast(0), command.following)
                    if (value != browsePosition) {
                        browsePosition = value
                        preferences.put(namespace, "$id.browse", value.encode())
                    }
                }
            }
            is LocalPlacement -> if (command.echoes != localEchoes || command.placed != timelinePlaced) {
                localEchoes = command.echoes; timelinePlaced = command.placed
                project()
            }
            ChangeScope -> { options = options.copy(mineOnly = !options.mineOnly); preferences.saveOptions(namespace, id, options); project() }
            is Filter -> { options = options.copy(actorFilters = if (command.actor == null) emptySet() else
                if (command.actor in options.actorFilters) options.actorFilters - command.actor else options.actorFilters + command.actor)
                preferences.saveOptions(namespace, id, options); project() }
            LoadOlder -> {
                if (older == null && replica != null) {
                    val before = replica!!.snapshot().rows.firstOrNull()?.seq ?: 0
                    if (before > 1) {
                        following = false
                        conversation = conversation?.copy(loadingOlder = true)
                        val epoch = readerEpoch
                        older = scope.launch {
                            try { commands.send(OlderFinished(epoch, sync.loadOlder(before).hasOlder, null)) }
                            catch (failure: Exception) { if (failure !is CancellationException) commands.send(OlderFinished(epoch, true, failure.message)) }
                        }
                    } else conversation = conversation?.copy(hasOlder = false)
                }
            }
            is OlderFinished -> if (command.epoch == readerEpoch) {
                older = null
                conversation = conversation?.copy(loadingOlder = false, hasOlder = command.hasOlder)
                error = command.error
            }
            Jump -> { following = true; if (newerUnloaded) loadTail(restoreBrowse = false) else updateBadges() }
            RefreshUnread -> { refresh = null; calculateUnread() }
            ActivityExpiry -> { activityExpiry = null; taskFactsDirty = true; calculateUnread() }
        }
        publish()
    }

    private suspend fun loadTail(restoreBrowse: Boolean = true) {
        readerEpoch++
        reader?.cancelAndJoin()
        older?.cancelAndJoin(); older = null
        replica = ChannelReplica(id, selfId)
        mapper = TimelineMapper()
        conversation = null
        timeline = null
        rowSequences = emptyMap()
        timelineSource = null
        newerUnloaded = false
        windowHigh = Long.MAX_VALUE
        newRoots.clear()
        firstOrigin = null
        error = null
        restoreNotice = null
        val position = browsePosition?.takeIf { restoreBrowse && !it.following }
        following = position == null
        val epoch = readerEpoch
        reader = scope.launch {
            try {
                var first = true
                sync.open().collect { batch ->
                    if (first && position != null && batch.rows.isNotEmpty() && position.seq < batch.rows.first().seq) {
                        val metadata = store.channel(id)
                        val before = position.seq + minOf(201L, Long.MAX_VALUE - position.seq)
                        val rows = store.rows(id, beforeSeq = before, limit = 4500)
                        if (restorableBrowseWindow(position, rows, metadata.coverage)) {
                            commands.send(Batch(epoch, RowBatch(id, rows, Origin.CACHE), restored = true, head = metadata.meta.headSeq))
                        } else commands.send(Batch(epoch, batch, notice = "上次浏览位置已不在本地缓存，已打开最新动态"))
                    } else commands.send(Batch(epoch, batch))
                    first = false
                }
            }
            catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                commands.send(ReadFailed(epoch, failure.message ?: "频道读取失败"))
            }
        }
    }

    private suspend fun apply(batch: RowBatch) {
        var model = replica ?: return
        if (firstOrigin == null && batch.rows.isNotEmpty()) firstOrigin = batch.origin
        val relevant = if (newerUnloaded) batch.rows.filter { it.seq <= windowHigh } else batch.rows
        val change = model.apply(batch.copy(rows = relevant))
        if (model.rowCount > 5000) {
            if (batch.olderThanWindow) {
                val kept = model.snapshot().rows.take(4500)
                model = ChannelReplica(id, selfId).also { it.apply(RowBatch(id, kept, Origin.CACHE)) }
                replica = model
                newerUnloaded = true
                windowHigh = kept.lastOrNull()?.seq ?: 0
                mapper = TimelineMapper()
            } else model.trim(4500, keepOpenTurns = false)
        }
        if ((change.accepted > 0 || conversation == null) && (model.rowCount > 0 || syncState.headSeq == 0L)) project()
        queueUnread()
    }

    private fun project() {
        val model = replica ?: return
        timelineSource = model.snapshot()
        if (!newerUnloaded) updateActivityFacts(timelineSource!!)
        val snapshot = model.timeline(TimelineOptions(scope = if (options.mineOnly) ConversationScope.MINE else ConversationScope.ALL,
            selfId = selfId, actorFilter = options.actorFilters, newerUnloaded = newerUnloaded,
            localEchoes = if (newerUnloaded) emptyList() else localEchoes, timelinePlaced = timelinePlaced))
        timeline = snapshot
        rowSequences = timelineRowSequences(snapshot)
        val previous = conversation
        conversation = ConversationViewState(channel = ChannelViewState(id, record.qualifiedName.ifEmpty { record.name }),
            entries = mapper.map(snapshot, selfId, actors, newerUnloaded), mineOnly = options.mineOnly, actorFilters = options.actorFilters,
            loadingOlder = previous?.loadingOlder ?: false, hasOlder = previous?.hasOlder ?: ((model.snapshot().rows.firstOrNull()?.seq ?: 1) > 1),
            connection = if (syncState.connected) "已连接" else "离线缓存", waitingCount = 0,
            runningCount = if (newerUnloaded) 0 else snapshot.runningTasks.size)
        updateBadges()
    }

    private fun updateBadges() {
        val active = activity.values.map { it.id }.toSet().takeIf { syncState.connected }.orEmpty()
        conversation = conversation?.copy(agents = actors.filter { it.kind == "agent" }.map { AgentViewState(it.id, it.name, !newerUnloaded && it.id in active) },
            newMessages = newRoots.size)
    }

    private suspend fun markSeen(keys: Set<String>) {
        if (!foreground || replica == null) return
        val high = keys.maxOfOrNull { rowSequences[it] ?: 0 } ?: 0
        if (high > readPosition) { readPosition = high; sync.markRead(high); queueUnread() }
        if (following && !newerUnloaded && keys.isNotEmpty()) { newRoots.clear(); updateBadges() }
    }

    private fun queueUnread() {
        if (refresh == null) refresh = scope.launch { delay(100); commands.send(RefreshUnread) }
    }

    private suspend fun calculateUnread() {
        notificationDirty = false
        if (readPosition >= syncState.headSeq && !taskFactsDirty) { unread = UnreadCount(); return }
        val metadata = store.channel(id)
        val model = if (replica != null && !newerUnloaded) replica!! else ChannelReplica(id, selfId).also { it.apply(RowBatch(id, store.rows(id, limit = 4500), Origin.CACHE)) }
        val source = model.snapshot()
        updateActivityFacts(source)
        val rows = source.rows
        if (readPosition >= syncState.headSeq) { unread = UnreadCount(); return }
        unread = NotificationPolicy.unread(source, selfId, readPosition, syncState.current)
        val covered = metadata.coverage.any { it.low <= readPosition + 1 && it.high >= syncState.headSeq }
        if (!covered || (rows.firstOrNull()?.seq ?: 0) > readPosition + 1) unread = unread.copy(unknown = true)
    }

    private fun publish() {
        mutableState.value = ChannelRuntimeState(syncState, unread, if (syncState.connected) activity.values.toList() else emptyList(),
            conversation, newerUnloaded, firstOrigin, error ?: syncState.error, timeline, timelineSource, taskFacts,
            if (syncState.connected) activity.map { (request, value) -> ActivityAgentRun(id, request, value.id, updatedAt = value.startedAtMillis) } else emptyList(), browsePosition, restoreNotice, rowSequences)
    }
    private fun updateActivityFacts(source: TimelineSource) {
        val now = System.currentTimeMillis()
        taskFacts = activityFacts(source, selfId, now); taskFactsDirty = false
        activityExpiry?.cancel(); activityExpiry = null
        val next = taskFacts.filter { it.kind == "approval" && it.state == "waiting" }.mapNotNull { it.dueAt.toLongOrNull() }
            .filter { it > now }.minOrNull() ?: return
        activityExpiry = scope.launch { delay((next - now).coerceAtLeast(1)); commands.send(ActivityExpiry) }
    }
    private fun name(id: String) = actors.firstOrNull { it.id == id }?.name ?: id
    private fun candidate(envelope: Envelope) = envelope.kind == "request" || envelope.isTerminal || envelope.kind == "event" && envelope.type == "agent.provider.run"

    private sealed interface Command
    private data class Open(val record: ChannelRecord) : Command
    private data object CloseView : Command
    private data class Batch(val epoch: Long, val batch: RowBatch, val restored: Boolean = false, val head: Long = 0, val notice: String? = null) : Command
    private data class ReadFailed(val epoch: Long, val message: String) : Command
    private data object Retry : Command
    private data class Sync(val value: SyncState) : Command
    private data class Live(val row: Row) : Command
    private data class Roster(val actors: List<ActorRecord>) : Command
    private data class Follow(val value: Boolean) : Command
    private data class Foreground(val value: Boolean) : Command
    private data object ChangeScope : Command
    private data class Filter(val actor: String?) : Command
    private data object LoadOlder : Command
    private data class OlderFinished(val epoch: Long, val hasOlder: Boolean, val error: String?) : Command
    private data object Jump : Command
    private data class VisibleRows(val keys: Set<String>) : Command
    private data class Browse(val key: String, val offset: Int, val following: Boolean) : Command
    private data class LocalPlacement(val echoes: List<dev.atoll.app.model.TimelineEntry>, val placed: Set<String>) : Command
    private data object RefreshUnread : Command
    private data object ActivityExpiry : Command
}

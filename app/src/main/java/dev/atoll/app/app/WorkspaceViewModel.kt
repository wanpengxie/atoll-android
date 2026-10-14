package dev.atoll.app.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import dev.atoll.app.compose.ComposerActor
import dev.atoll.app.compose.recentAgentId
import dev.atoll.app.AtollApp
import dev.atoll.app.stars.StarredViewModel
import dev.atoll.app.stars.createStarredViewModel
import dev.atoll.app.tasks.*
import dev.atoll.app.ui.screens.ConnectionPhase
import dev.atoll.app.waiting.busyActors as receiverBusyActors

class WorkspaceViewModel(application: Application) : AndroidViewModel(application) {
    val graph: AppGraph = (application as AtollApp).graph
    val state = graph.state
    private val mutableMessageTargets = MutableStateFlow(MessageTargetIndex())
    val messageTargets = mutableMessageTargets.asStateFlow()
    private val mutableActivity = MutableStateFlow(ActivitySnapshot(emptyList(), emptyList(), true))
    val activity = mutableActivity.asStateFlow()
    private var features: SessionFeatures? = null
    private var sessionStars: StarredViewModel? = null
    val starred: StarredViewModel? get() = sessionStars
    init {
        viewModelScope.launch {
            graph.services.collectLatest { services ->
                val current = session(services) ?: return@collectLatest
                current.waiting.collectLatest { waiting ->
                    if (waiting == null) return@collectLatest
                    waiting.state.map { it.localEchoes to it.timelinePlaced }.distinctUntilChanged().collect { (echoes, placed) ->
                        graph.localPlacement(waiting.channelId, current.services, echoes, placed)
                    }
                }
            }
        }
        viewModelScope.launch {
            graph.services.collectLatest { services ->
                val current = session(services) ?: return@collectLatest
                val reported = mutableSetOf<String>()
                current.governance.creations.collect { operations ->
                    operations.filter { it.ledger && !it.failed && it.targetId.isNotEmpty() && reported.add(it.targetId) }.forEach {
                        graph.channelCreated(it.targetId, it.parentId, it.name, current.services)
                    }
                }
            }
        }
        viewModelScope.launch {
            graph.services.collectLatest { services ->
                val current = session(services)
                if (current == null) { mutableActivity.value = ActivitySnapshot(emptyList(), emptyList(), true); return@collectLatest }
                combine(graph.state, current.governance.creations,current.composer.submissions,current.automation.records) { state, operations,submissions,timers ->
                    val authorities = state.navigation.channels.associate { channel -> channel.id to ApprovalAuthority(
                        services?.selfIds?.get(channel.id).orEmpty(), composerWritable(channel.access, services?.selfIds?.get(channel.id).orEmpty())) }
                    ActivityBinding(state.activityInputs, state.activityRuns, operations.map(::creationActivity), state.navigation.connection == ConnectionPhase.OPEN,submissions,timers,services?.selfIds.orEmpty(),authorities)
                }.distinctUntilChanged().mapLatest { binding -> withContext(Dispatchers.Default) {
                    selectActivity(mergeActivityInputs(binding.inputs,binding.submissions,binding.timers,binding.selfIds,System.currentTimeMillis()), binding.runs, binding.operations, binding.connected, binding.authorities)
                } }.collect { mutableActivity.value = it }
            }
        }
        viewModelScope.launch { graph.services.collect { session(it) } }
        viewModelScope.launch {
            combine(graph.services, graph.state) { services, state -> services to state }.collectLatest { (services, state) ->
                val features = session(services) ?: return@collectLatest
                if (state.boot != services!!.boot) return@collectLatest
                updateComposerAccess(features, services, state)
                val route = state.route
                val governanceChannel = route?.channel ?: state.navigation.channels.firstOrNull { it.id in services.selfIds }?.id.orEmpty()
                val governanceSelf = services.selfIds[governanceChannel].orEmpty()
                features.governance.context(governanceChannel, governanceSelf,
                    state.navigation.channels.firstOrNull { it.id == governanceChannel }?.let { composerWritable(it.access, governanceSelf) } == true,
                    services.selfIds.keys)
                features.waiting.value?.setActive(route?.channel == features.waiting.value?.channelId && route?.tab == "conversation")
                if (route == null) return@collectLatest
                val roster = state.roster.map { ComposerActor(it.id, it.name, it.kind, it.body) }
                val self = services.selfIds[route.channel].orEmpty()
                val source = state.timelineSource?.takeIf { it.channelId == route.channel }
                val recent = if (source == null) "" else {
                    val key = RecentProjection(source.channelId, source.revision, source.projectionVersion, self, roster)
                    if (key != recentProjection) {
                        recentAgent = withContext(Dispatchers.Default) { recentAgentId(source, self, roster) }
                        recentProjection = key
                    }
                    recentAgent
                }
                val signature = ComposerChannel(route.channel, services.selfIds[route.channel].orEmpty(), roster,
                    state.navigation.channels.firstOrNull { it.id == route.channel }?.let { composerWritable(it.access, services.selfIds[route.channel].orEmpty()) } == true,
                    state.conversation?.actorFilters.orEmpty(), recent)
                features.roster(route.channel).updateAccess(signature.writable, signature.self)
                if (signature != composerChannel) {
                    composerChannel = signature
                    features.composer.setChannel(signature.id, signature.self, signature.roster, signature.writable, signature.filters, recentAgentId = signature.recent)
                }
                state.timelineSource?.takeIf { it.channelId == route.channel }?.let { source ->
                    val busyKey = BusyProjection(source.channelId, source.revision, source.projectionVersion)
                    if (busyKey != busyProjection) {
                        val actors = withContext(Dispatchers.Default) { receiverBusyActors(source) }
                        receiverBusy = actors; busyProjection = busyKey
                    }
                    val binding = TaskBinding(source, signature, state.newerUnloaded)
                    if (binding != foldedTasks) {
                        features.tasks(route.channel).update(source, signature.self,
                            state.roster.map { dev.atoll.app.tasks.TaskActor(it.id, it.name, it.kind) }, signature.writable && !state.newerUnloaded)
                        if (foldedTasks?.source !== source) features.forms.scan(source)
                        foldedTasks = binding
                    }
                }
                val timeline = state.timeline ?: return@collectLatest
                if (timeline.channelId != route.channel) return@collectLatest
                val waiting = features.waiting(timeline)
                waiting.updateAccess(signature.writable, signature.self, state.roster.associate { it.id to it.name }, state.rosterCurrent)
                waiting.updateSnapshot(timeline, state.newerUnloaded, receiverBusy)
                waiting.setActive(route.tab == "conversation")
                if (timeline === foldedComposer) return@collectLatest
                val facts = withContext(Dispatchers.Default) { composerTimelineFacts(timeline) }
                mutableMessageTargets.value = withContext(Dispatchers.Default) { MessageTargetIndex(timeline.channelId, timelineMessageTargets(timeline)) }
                foldedComposer = timeline
                features.composer.reconcileLanded(timeline.channelId, facts.landed)
                facts.usage.forEach { features.offerUsage(timeline.channelId, it) }
            }
        }
    }
    private data class ComposerChannel(val id: String, val self: String, val roster: List<ComposerActor>, val writable: Boolean, val filters: Set<String>, val recent: String)
    private var composerChannel: ComposerChannel? = null
    private var foldedComposer: dev.atoll.app.model.TimelineSnapshot? = null
    private data class TaskBinding(val source: dev.atoll.app.model.TimelineSource, val composer: ComposerChannel, val detached: Boolean)
    private var foldedTasks: TaskBinding? = null
    private data class BusyProjection(val channel: String, val revision: Long, val projection: Long)
    private var busyProjection: BusyProjection? = null
    private var receiverBusy = emptySet<String>()
    private data class RecentProjection(val channel: String, val revision: Long, val projection: Long, val self: String, val roster: List<ComposerActor>)
    private var recentProjection: RecentProjection? = null
    private var recentAgent = ""
    private data class ActivityBinding(val inputs: List<ChannelActivityInput>, val runs: List<ActivityAgentRun>, val operations: List<ActivityOperation>, val connected: Boolean,
        val submissions: List<dev.atoll.app.compose.LocalSubmission>,val timers: List<AutomationRecord>,val selfIds: Map<String,String>,val authorities: Map<String,ApprovalAuthority>)
    /** Re-read the current inbox and membership at click time; never switch the visible tasks owner. */
    fun resolveActivity(key: String, action: String) {
        viewModelScope.launch {
            val services = graph.services.value ?: return@launch
            val features = session(services) ?: return@launch
            val item = mutableActivity.value.activities.firstOrNull { it.key == key }?.approval ?: return@launch
            val channel = graph.state.value.navigation.channels.firstOrNull { it.id == item.channelId } ?: return@launch
            val self = services.selfIds[item.channelId].orEmpty()
            val authority = ApprovalAuthority(self, composerWritable(channel.access, self))
            if (item.state == "waiting" && action !in approvalActions(item, authority, graph.state.value.navigation.connection == ConnectionPhase.OPEN, System.currentTimeMillis())) return@launch
            try { features.approvals.resolve(item, action, authority) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { /* The shared command state presents the failure and enables retry. */ }
        }
    }
    private var writable = emptyMap<String, Boolean>()
    private fun updateComposerAccess(features: SessionFeatures, services: WorkspaceServices, state: WorkspaceState) {
        val next = state.navigation.channels.associate { it.id to composerWritable(it.access, services.selfIds[it.id].orEmpty()) }
        (writable.keys + next.keys).forEach { id -> if (writable[id] != next[id]) features.composer.setChannelWritable(id, next[id] == true) }
        writable = next
    }
    fun session(services: WorkspaceServices?): SessionFeatures? {
        if (features?.services?.wire !== services?.wire || features?.services?.boot != services?.boot) {
            sessionStars?.close(); sessionStars = null
            features?.clear(); features = null; composerChannel = null; foldedComposer = null; foldedTasks = null; busyProjection = null; receiverBusy = emptySet(); recentProjection = null; recentAgent = ""; writable = emptyMap(); mutableMessageTargets.value = MessageTargetIndex()
        }
        if (features == null && services != null && services.boot.isNotEmpty()) {
            features = SessionFeatures(getApplication(), services)
            sessionStars = createStarredViewModel(getApplication(), services)
        }
        if (services != null) features?.updateServices(services)
        return features
    }
    override fun onCleared() { sessionStars?.close(); features?.clear() }
}

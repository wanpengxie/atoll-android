package dev.atoll.app.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import dev.atoll.app.compose.ComposerActor
import dev.atoll.app.AtollApp
import dev.atoll.app.tasks.*
import dev.atoll.app.ui.screens.ConnectionPhase

class WorkspaceViewModel(application: Application) : AndroidViewModel(application) {
    val graph: AppGraph = (application as AtollApp).graph
    val state = graph.state
    private val mutableMessageTargets = MutableStateFlow(MessageTargetIndex())
    val messageTargets = mutableMessageTargets.asStateFlow()
    private val mutableActivity = MutableStateFlow(ActivitySnapshot(emptyList(), emptyList(), true))
    val activity = mutableActivity.asStateFlow()
    private var features: SessionFeatures? = null
    init {
        viewModelScope.launch {
            graph.services.collectLatest { services ->
                val current = session(services)
                if (current == null) { mutableActivity.value = ActivitySnapshot(emptyList(), emptyList(), true); return@collectLatest }
                combine(graph.state, current.governance.creations) { state, operations ->
                    ActivityBinding(state.activityInputs, state.activityRuns, operations.map(::creationActivity), state.navigation.connection == ConnectionPhase.OPEN)
                }.distinctUntilChanged().mapLatest { binding -> withContext(Dispatchers.Default) {
                    selectActivity(binding.inputs, binding.runs, binding.operations, binding.connected)
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
                features.waiting.value?.setActive(route?.channel == features.waiting.value?.channelId && route?.tab == "conversation")
                if (route == null) return@collectLatest
                val roster = state.roster.map { ComposerActor(it.id, it.name, it.kind, it.body) }
                val signature = ComposerChannel(route.channel, services.selfIds[route.channel].orEmpty(), roster,
                    state.navigation.channels.firstOrNull { it.id == route.channel }?.let { composerWritable(it.access, services.selfIds[route.channel].orEmpty()) } == true,
                    state.conversation?.actorFilters.orEmpty())
                if (signature != composerChannel) {
                    composerChannel = signature
                    features.composer.setChannel(signature.id, signature.self, signature.roster, signature.writable, signature.filters)
                }
                state.timelineSource?.takeIf { it.channelId == route.channel }?.let { source ->
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
                waiting.updateSnapshot(timeline, state.newerUnloaded)
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
    private data class ComposerChannel(val id: String, val self: String, val roster: List<ComposerActor>, val writable: Boolean, val filters: Set<String>)
    private var composerChannel: ComposerChannel? = null
    private var foldedComposer: dev.atoll.app.model.TimelineSnapshot? = null
    private data class TaskBinding(val source: dev.atoll.app.model.TimelineSource, val composer: ComposerChannel, val detached: Boolean)
    private var foldedTasks: TaskBinding? = null
    private data class ActivityBinding(val inputs: List<ChannelActivityInput>, val runs: List<ActivityAgentRun>, val operations: List<ActivityOperation>, val connected: Boolean)
    private var writable = emptyMap<String, Boolean>()
    private fun updateComposerAccess(features: SessionFeatures, services: WorkspaceServices, state: WorkspaceState) {
        val next = state.navigation.channels.associate { it.id to composerWritable(it.access, services.selfIds[it.id].orEmpty()) }
        (writable.keys + next.keys).forEach { id -> if (writable[id] != next[id]) features.composer.setChannelWritable(id, next[id] == true) }
        writable = next
    }
    fun session(services: WorkspaceServices?): SessionFeatures? {
        if (features?.services?.wire !== services?.wire || features?.services?.boot != services?.boot) {
            features?.clear(); features = null; composerChannel = null; foldedComposer = null; foldedTasks = null; writable = emptyMap(); mutableMessageTargets.value = MessageTargetIndex()
        }
        if (features == null && services != null && services.boot.isNotEmpty()) features = SessionFeatures(getApplication(), services)
        if (services != null) features?.updateServices(services)
        return features
    }
    override fun onCleared() { features?.clear() }
}

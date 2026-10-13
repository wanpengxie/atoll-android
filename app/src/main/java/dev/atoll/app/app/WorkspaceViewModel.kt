package dev.atoll.app.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import dev.atoll.app.compose.ComposerActor
import dev.atoll.app.AtollApp

class WorkspaceViewModel(application: Application) : AndroidViewModel(application) {
    val graph: AppGraph = (application as AtollApp).graph
    val state = graph.state
    private var features: SessionFeatures? = null
    init {
        viewModelScope.launch { graph.services.collect { session(it) } }
        viewModelScope.launch {
            combine(graph.services, graph.state) { services, state -> services to state }.collectLatest { (services, state) ->
                val features = session(services) ?: return@collectLatest
                if (state.boot != services!!.boot) return@collectLatest
                updateComposerAccess(features, services, state)
                val route = state.route ?: return@collectLatest
                val roster = state.roster.map { ComposerActor(it.id, it.name, it.kind, it.body) }
                val signature = ComposerChannel(route.channel, services.selfIds[route.channel].orEmpty(), roster,
                    state.navigation.channels.firstOrNull { it.id == route.channel }?.let { composerWritable(it.access, services.selfIds[route.channel].orEmpty()) } == true,
                    state.conversation?.actorFilters.orEmpty())
                if (signature != composerChannel) {
                    composerChannel = signature
                    features.composer.setChannel(signature.id, signature.self, signature.roster, signature.writable, signature.filters)
                }
                val timeline = state.timeline ?: return@collectLatest
                if (timeline.channelId != route.channel || timeline === foldedComposer) return@collectLatest
                val facts = withContext(Dispatchers.Default) { composerTimelineFacts(timeline) }
                foldedComposer = timeline
                features.composer.reconcileLanded(timeline.channelId, facts.landed)
                facts.usage.forEach { features.offerUsage(timeline.channelId, it) }
            }
        }
    }
    private data class ComposerChannel(val id: String, val self: String, val roster: List<ComposerActor>, val writable: Boolean, val filters: Set<String>)
    private var composerChannel: ComposerChannel? = null
    private var foldedComposer: dev.atoll.app.model.TimelineSnapshot? = null
    private var writable = emptyMap<String, Boolean>()
    private fun updateComposerAccess(features: SessionFeatures, services: WorkspaceServices, state: WorkspaceState) {
        val next = state.navigation.channels.associate { it.id to composerWritable(it.access, services.selfIds[it.id].orEmpty()) }
        (writable.keys + next.keys).forEach { id -> if (writable[id] != next[id]) features.composer.setChannelWritable(id, next[id] == true) }
        writable = next
    }
    fun session(services: WorkspaceServices?): SessionFeatures? {
        if (features?.services?.wire !== services?.wire || features?.services?.boot != services?.boot) {
            features?.clear(); features = null; composerChannel = null; foldedComposer = null; writable = emptyMap()
        }
        if (features == null && services != null && services.boot.isNotEmpty()) features = SessionFeatures(getApplication(), services)
        return features
    }
    override fun onCleared() { features?.clear() }
}

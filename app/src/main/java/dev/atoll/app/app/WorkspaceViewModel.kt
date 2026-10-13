package dev.atoll.app.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import dev.atoll.app.AtollApp

class WorkspaceViewModel(application: Application) : AndroidViewModel(application) {
    val graph: AppGraph = (application as AtollApp).graph
    val state = graph.state
    private var features: SessionFeatures? = null
    init { viewModelScope.launch { graph.services.collect { session(it) } } }
    fun session(services: WorkspaceServices?): SessionFeatures? {
        if (features?.services?.wire !== services?.wire || features?.services?.boot != services?.boot) {
            features?.clear(); features = null
        }
        if (features == null && services != null && services.boot.isNotEmpty()) features = SessionFeatures(getApplication(), services)
        return features
    }
    override fun onCleared() { features?.clear() }
}

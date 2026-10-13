package dev.atoll.app.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import dev.atoll.app.AtollApp

class WorkspaceViewModel(application: Application) : AndroidViewModel(application) {
    val graph: AppGraph = (application as AtollApp).graph
    val state = graph.state
}

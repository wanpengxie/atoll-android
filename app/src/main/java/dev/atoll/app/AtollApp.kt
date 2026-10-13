package dev.atoll.app

import android.app.Application
import dev.atoll.app.app.AppGraph

class AtollApp : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }
}

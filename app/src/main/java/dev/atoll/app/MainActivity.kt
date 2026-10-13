package dev.atoll.app

import android.os.Bundle
import android.content.Intent
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import dev.atoll.app.app.WorkspaceRoot
import dev.atoll.app.app.WorkspaceViewModel

class MainActivity : ComponentActivity() {
    private val viewModel by lazy { ViewModelProvider(this)[WorkspaceViewModel::class.java] }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val startedAt = SystemClock.elapsedRealtime()
        intent.dataString?.let(viewModel.graph::route)
        setContent { WorkspaceRoot(viewModel, startedAt) }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); viewModel.graph.route(intent.dataString) }
    override fun onStart() { super.onStart(); viewModel.graph.foreground(true) }
    override fun onStop() { viewModel.graph.foreground(false); super.onStop() }
}

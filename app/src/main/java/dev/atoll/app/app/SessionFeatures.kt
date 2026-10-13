package dev.atoll.app.app

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import dev.atoll.app.compose.ComposerViewModel
import dev.atoll.app.compose.createComposerViewModel
import dev.atoll.app.core.DownFrame
import kotlinx.coroutines.*
import dev.atoll.app.files.FileHistory
import dev.atoll.app.files.FilesViewModel
import dev.atoll.app.net.AndroidKeyValueStore
import dev.atoll.app.terminal.TerminalPreferences

/** Feature models survive channel switches and rotation, and end with the checked server world. */
class SessionFeatures(private val context: Context, val services: WorkspaceServices) : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
    val terminalPreferences = TerminalPreferences(AndroidKeyValueStore(context.getSharedPreferences("terminal_${accountNamespace(services.server, services.principalId)}", Context.MODE_PRIVATE)))
    val fileHistory = FileHistory(AndroidKeyValueStore(context.getSharedPreferences("files", Context.MODE_PRIVATE)),
        services.server, services.principalId, services.boot)
    val composer = ViewModelProvider(this, object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = createComposerViewModel(context, services, "", boot = services.boot) as T
    })[ComposerViewModel::class.java]
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val usageSeq = mutableMapOf<Pair<String, String>, Long>()
    init { scope.launch(start = CoroutineStart.UNDISPATCHED) { services.wire.frames.collect { frame ->
        if (frame is DownFrame.Feed) {
            val row = frame.row
            if (row.envelope.kind == "request") composer.reconcileLanded(row.channelId, setOf(row.envelope.id))
            composerUsage(row.envelope, row.seq)?.let { offerUsage(row.channelId, it) }
        }
    } } }
    fun offerUsage(channel: String, value: ComposerUsage) {
        val key = channel to value.actor
        if (value.seq > (usageSeq[key] ?: 0)) { usageSeq[key] = value.seq; composer.updateUsage(channel, value.actor, value.value) }
    }
    fun files(channel: String): FilesViewModel = ViewModelProvider(this, object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = FilesViewModel(services.wire, services.httpClient,
            services.server, channel, context.cacheDir, fileHistory) as T
    })["files:$channel", FilesViewModel::class.java]
    fun clear() { scope.cancel(); viewModelStore.clear() }
}

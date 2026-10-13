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
import dev.atoll.app.model.TimelineSnapshot
import dev.atoll.app.waiting.WaitingViewModel
import dev.atoll.app.waiting.createWaitingViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import dev.atoll.app.tasks.TasksViewModel
import dev.atoll.app.tasks.TaskSubmissionOwner
import dev.atoll.app.tasks.UiFormController
import dev.atoll.app.tasks.createTasksViewModel
import dev.atoll.app.governance.GovernanceViewModel
import dev.atoll.app.governance.GovernanceRepository

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
    private var selfIds = services.selfIds
    fun updateServices(value: WorkspaceServices) { selfIds = value.selfIds }
    val forms = UiFormController(services.wire, { selfIds[it].orEmpty() })
    val governance = GovernanceViewModel(GovernanceRepository(services.wire, services.obs), services.principalId, services.wire.frames)
    private val taskOwner = object : TaskSubmissionOwner {
        override val submissions = composer.submissions
        override suspend fun submitFrame(frame: kotlinx.serialization.json.JsonObject, text: String, targetLabel: String) = composer.submitFrame(frame, text, targetLabel)
        override fun retry(id: String) = composer.retry(id)
        override fun cancel(id: String) = composer.cancel(id)
    }
    private val mutableTasks = MutableStateFlow<TasksViewModel?>(null)
    val tasks = mutableTasks.asStateFlow()
    fun tasks(channel: String): TasksViewModel {
        val current = mutableTasks.value
        if (current?.channelId == channel) return current
        current?.close()
        return createTasksViewModel(context, services, channel, owner = taskOwner).also { mutableTasks.value = it }
    }
    private val usageSeq = mutableMapOf<Pair<String, String>, Long>()
    private val mutableWaiting = MutableStateFlow<WaitingViewModel?>(null)
    val waiting = mutableWaiting.asStateFlow()
    fun waiting(snapshot: TimelineSnapshot): WaitingViewModel {
        val current = mutableWaiting.value
        if (current?.channelId == snapshot.channelId) return current
        current?.setActive(false); current?.close()
        return createWaitingViewModel(services, snapshot.channelId, snapshot, composer).also { mutableWaiting.value = it }
    }
    init { scope.launch { combine(waiting, composer.submissions) { waiting, submissions -> waiting to submissions }.collect { (waiting, submissions) -> waiting?.updateSubmissions(submissions) } } }
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
    fun clear() { governance.close(); forms.close(); mutableTasks.value?.close(); mutableTasks.value = null; mutableWaiting.value?.close(); mutableWaiting.value = null; scope.cancel(); viewModelStore.clear() }
}

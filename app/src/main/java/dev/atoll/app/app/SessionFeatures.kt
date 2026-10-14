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
import dev.atoll.app.tasks.AutomationSession
import dev.atoll.app.tasks.TasksViewModel
import dev.atoll.app.tasks.ApprovalResolver
import dev.atoll.app.tasks.TaskSubmissionOwner
import dev.atoll.app.tasks.UiFormController
import dev.atoll.app.tasks.createTasksViewModel
import dev.atoll.app.governance.GovernanceViewModel
import dev.atoll.app.governance.GovernanceRepository
import dev.atoll.app.roster.RosterViewModel
import dev.atoll.app.roster.createRosterViewModel

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
    private val mutableRoster = MutableStateFlow<RosterViewModel?>(null)
    val roster = mutableRoster.asStateFlow()
    fun roster(channel: String): RosterViewModel {
        val current = mutableRoster.value
        if (current?.channelId == channel) return current
        current?.close()
        return createRosterViewModel(services, channel).also { mutableRoster.value = it }
    }
    private val taskOwner = object : TaskSubmissionOwner {
        override val submissions = composer.submissions
        override suspend fun submitFrame(frame: kotlinx.serialization.json.JsonObject, text: String, targetLabel: String) = composer.submitFrame(frame, text, targetLabel)
        override fun retry(id: String) = composer.retry(id)
        override fun cancel(id: String) = composer.cancel(id)
    }
    val automation = AutomationSession(services.boot)
    val approvals = ApprovalResolver(services.wire)
    private val mutableTasks = MutableStateFlow<TasksViewModel?>(null)
    val tasks = mutableTasks.asStateFlow()
    fun tasks(channel: String): TasksViewModel {
        val current = mutableTasks.value
        if (current?.channelId == channel) return current
        current?.close()
        return createTasksViewModel(services, channel, owner = taskOwner, automation = automation, approvals = approvals).also { mutableTasks.value = it }
    }
    init { scope.launch(start = CoroutineStart.UNDISPATCHED) { services.wire.state.collect { automation.observe(it) } } }
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
            automation.observeFiring(row, selfIds[row.channelId].orEmpty())
            if (row.envelope.kind == "request") composer.reconcileLanded(row.channelId, setOf(row.envelope.id))
            composerUsage(row.envelope, row.seq)?.let { offerUsage(row.channelId, it) }
        }
    } } }
    fun offerUsage(channel: String, value: ComposerUsage) {
        val key = channel to value.actor
        if (value.seq > (usageSeq[key] ?: 0)) { usageSeq[key] = value.seq; composer.updateUsage(channel, value.actor, value.value) }
    }
    private val fileModels=ChannelModelCache(6,{ ViewModelStore() },{ it.clear() })
    fun files(channel: String): FilesViewModel {
        val store=fileModels.get(channel)
        val owner=object:ViewModelStoreOwner { override val viewModelStore=store }
        return ViewModelProvider(owner,object:ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T:ViewModel> create(modelClass: Class<T>):T = FilesViewModel(services.wire,services.httpClient,
                services.server,channel,context.cacheDir,fileHistory) as T
        })[FilesViewModel::class.java]
    }
    fun clear() { mutableRoster.value?.close(); mutableRoster.value = null; governance.close(); forms.close(); automation.clear(); mutableTasks.value?.close(); mutableTasks.value = null; mutableWaiting.value?.close(); mutableWaiting.value = null; scope.cancel(); fileModels.clear(); viewModelStore.clear() }
}

/** Access order tracks actual file-surface use; eviction releases each channel's ViewModelStore. */
internal class ChannelModelCache<T>(private val capacity: Int,private val create: (String)->T,private val release: (T)->Unit) {
    private val values=LinkedHashMap<String,T>(capacity,.75f,true)
    fun get(channel: String):T = values[channel] ?: create(channel).also { value ->
        values[channel]=value
        if(values.size>capacity) values.remove(values.keys.first())?.let(release)
    }
    fun clear() { values.values.forEach(release);values.clear() }
}

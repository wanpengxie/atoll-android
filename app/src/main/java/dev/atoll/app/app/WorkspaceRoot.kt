package dev.atoll.app.app

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.atoll.app.compose.AtollComposer
import dev.atoll.app.compose.ComposerActor
import dev.atoll.app.compose.TimelineMessageActions
import dev.atoll.app.compose.TimelineSubmissionStatus
import dev.atoll.app.waiting.WaitingDock
import dev.atoll.app.waiting.editorBinding
import dev.atoll.app.tasks.ApprovalCard
import dev.atoll.app.tasks.TasksScreen as NativeTasksScreen
import dev.atoll.app.tasks.CreateTaskDialog
import dev.atoll.app.tasks.UiFormsHost
import dev.atoll.app.tasks.ActivityCenterScreen
import dev.atoll.app.roster.RosterPanel
import dev.atoll.app.roster.ActorDetail
import dev.atoll.app.governance.GovernanceScreen
import dev.atoll.app.governance.SpaceAdministrationScreen
import dev.atoll.app.governance.ChannelCreateDialog
import dev.atoll.app.core.string
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.serialization.json.*
import dev.atoll.app.terminal.TerminalScreen
import dev.atoll.app.terminal.TerminalDevice
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import dev.atoll.app.files.FilePicker
import dev.atoll.app.files.FilesScreen
import dev.atoll.app.files.FilePaths
import dev.atoll.app.core.Origin
import dev.atoll.app.ui.screens.*
import dev.atoll.app.ui.screens.ChannelMenuAction.Item
import dev.atoll.app.ui.theme.AtollTheme
import dev.atoll.app.ui.components.LocalMarkdownCache
import dev.atoll.app.ui.components.MarkdownCache
import dev.atoll.app.ui.components.RichContentHost
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.MutableStateFlow
import dev.atoll.app.compose.LocalSubmission
import dev.atoll.app.waiting.WaitingViewModel
import dev.atoll.app.waiting.WaitingUiState
import dev.atoll.app.tasks.TasksViewModel
import dev.atoll.app.tasks.TasksState
import dev.atoll.app.roster.RosterViewModel
import androidx.compose.foundation.gestures.scrollBy

@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
fun WorkspaceRoot(viewModel: WorkspaceViewModel, startedAt: Long) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val graph = viewModel.graph
    val services by graph.services.collectAsStateWithLifecycle()
    val features = viewModel.session(services)
    // Keep subscription call sites present while the session/channel owners change.
    // Safe-call composable invocations would shift later slots on login/logout.
    val emptyWaiting = remember { MutableStateFlow<WaitingViewModel?>(null) }
    val emptyWaitingState = remember { MutableStateFlow(WaitingUiState()) }
    val emptySubmissions = remember { MutableStateFlow<List<LocalSubmission>>(emptyList()) }
    val waitingOwner by (features?.waiting ?: emptyWaiting).collectAsStateWithLifecycle()
    val waiting = waitingOwner?.takeIf { it.channelId == state.route?.channel }
    val observedWaitingState by (waiting?.state ?: emptyWaitingState).collectAsStateWithLifecycle()
    val waitingState = observedWaitingState.takeIf { waiting != null }
    val localSubmissions by (features?.composer?.submissions ?: emptySubmissions).collectAsStateWithLifecycle()
    val localById = remember(localSubmissions) { localSubmissions.associateBy { it.messageId } }
    val emptyTasks = remember { MutableStateFlow<TasksViewModel?>(null) }
    val emptyTaskState = remember { MutableStateFlow(TasksState()) }
    val taskOwner by (features?.tasks ?: emptyTasks).collectAsStateWithLifecycle()
    val tasks = taskOwner?.takeIf { it.channelId == state.route?.channel }
    val observedTaskState by (tasks?.state ?: emptyTaskState).collectAsStateWithLifecycle()
    val taskState = observedTaskState.takeIf { tasks != null }
    val actionRoster = remember(state.roster) { state.roster.map { ComposerActor(it.id, it.name, it.kind, it.body) } }
    val emptyRoster = remember { MutableStateFlow<RosterViewModel?>(null) }
    val rosterOwner by (features?.roster ?: emptyRoster).collectAsStateWithLifecycle()
    val roster = rosterOwner?.takeIf { it.channelId == state.route?.channel }
    var rosterOpen by remember(state.route?.channel, services?.wire, services?.boot) { mutableStateOf(false) }
    var actorOpen by remember(state.route?.channel, services?.wire, services?.boot) { mutableStateOf(false) }
    var governanceOpen by remember(state.route?.channel, services?.wire, services?.boot) { mutableStateOf(false) }
    var spaceOpen by remember(services?.wire, services?.boot) { mutableStateOf(false) }
    var createChannelOpen by remember(state.route?.channel, services?.wire, services?.boot) { mutableStateOf(false) }
    val messageIndex by viewModel.messageTargets.collectAsStateWithLifecycle()
    val messageTargets = messageIndex.targets.takeIf { messageIndex.channelId == state.route?.channel }.orEmpty()
    val activity by viewModel.activity.collectAsStateWithLifecycle()
    fun enterCreatedChannel(id: String) {
        val operation = features?.governance?.creations?.value?.lastOrNull { it.targetId == id }
        if (operation != null) graph.channelCreated(id, operation.parentId, operation.name, features!!.services, enter = true) else graph.openChannel(id)
    }
    var activityOpen by rememberSaveable { mutableStateOf(false) }
    var selectedMessage by remember { mutableStateOf<String?>(null) }
    var recentOpen by remember { mutableStateOf(false) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var drawer by rememberSaveable { mutableStateOf(false) }
    var loggedList by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    LaunchedEffect(state.phase, state.route, settingsOpen) { graph.telemetry.screen.set(if (settingsOpen) "settings" else state.route?.let { "channel:${it.channel}/${it.tab}" } ?: state.phase.name) }
    LaunchedEffect(state.phase) { if (state.phase != WorkspacePhase.Workspace) { settingsOpen = false; drawer = false; recentOpen = false; activityOpen = false; selectedMessage = null } }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val markdownCache = remember(services?.wire, services?.boot) { MarkdownCache() }
    AtollTheme {
        CompositionLocalProvider(LocalMarkdownCache provides markdownCache) {
        RichContentHost {
        when (state.phase) {
            WorkspacePhase.Booting -> StatusPage("正在启动工作区…")
            WorkspacePhase.Login -> LoginScreen(state.login, graph::login)
            WorkspacePhase.Incompatible -> StatusPage("客户端版本与节点不兼容", state.incompatible, "刷新页面", graph::reconnect)
            WorkspacePhase.Workspace -> {
                BackHandler(state.route != null || drawer || settingsOpen) { if (settingsOpen) settingsOpen = false else if (drawer) drawer = false else graph.back() }
                Column(Modifier.fillMaxSize()) {
                    state.notice?.let { text -> Surface(color = MaterialTheme.colorScheme.errorContainer) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton(graph::dismissNotice) { Text("关闭") }
                        }
                    } }
                    if (state.route == null) {
                        ChannelListScreen(state.navigation, graph::openChannel, onLogout = graph::logout, onUpgrade = graph::upgrade, onSettings = { drawer = false; settingsOpen = true }, onActivity = { activityOpen = true },
                            onCreate = { createChannelOpen = true }, onSpaceAdmin = { if (state.navigation.account.root) spaceOpen = true })
                        LaunchedEffect(state.navigation.channels.isNotEmpty()) {
                            if (!loggedList && state.navigation.channels.isNotEmpty()) {
                                withFrameNanos { }
                                graph.performanceMark("channel-list-visible", SystemClock.elapsedRealtime() - startedAt)
                                loggedList = true
                            }
                        }
                    } else key(state.route!!.channel) {
                        val conversation = state.conversation
                        if (conversation == null) {
                            val access = state.navigation.channels.firstOrNull { it.id == state.route!!.channel }?.access
                            if (state.channelError != null) StatusPage("频道读取失败", state.channelError!!, "重试", { graph.retryChannel(state.route!!.channel) })
                            else StatusPage(if (access == ChannelAccess.Denied) "无权访问此频道" else if (!state.identityConfirmed) "正在确认身份 · ${state.navigation.channels.firstOrNull { it.id == state.route!!.channel }?.name.orEmpty()}" else "正在读取频道…", button = "频道列表", action = { drawer = true })
                        } else {
                            val listState = rememberLazyListState(initialFirstVisibleItemIndex = (conversation.entries.lastIndex + if (conversation.hasOlder) 1 else 0).coerceAtLeast(0))
                            var following by remember { mutableStateOf(state.browsePosition?.following != false) }
                            var initialized by remember { mutableStateOf(false) }
                            var autoScrolling by remember { mutableStateOf(false) }
                            var visibleLogged by remember { mutableStateOf(false) }
                            var pickingFile by remember { mutableStateOf(false) }
                            var focusApplied by remember(state.route?.tab, state.route?.focus) { mutableStateOf(false) }
                            val bottomIndex = (conversation.entries.lastIndex + if (conversation.hasOlder) 1 else 0).coerceAtLeast(0)
                            val latestBottomIndex by rememberUpdatedState(bottomIndex)
                            DisposableEffect(listState) { onDispose { graph.telemetry.scrolling.set(false) } }
                            suspend fun scrollEnd() {
                                autoScrolling = true
                                try {
                                    listState.scrollToItem(latestBottomIndex)
                                    withFrameNanos { }
                                    val last = listState.layoutInfo.visibleItemsInfo.lastOrNull { it.index == latestBottomIndex }
                                    if (last != null) listState.scrollBy((last.offset + last.size - listState.layoutInfo.viewportEndOffset).coerceAtLeast(0).toFloat())
                                } finally { autoScrolling = false }
                            }
                            LaunchedEffect(state.route?.tab) { graph.follow(state.route?.tab == "conversation" && following) }
                            val latestRevision by rememberUpdatedState(state.timeline?.revision)
                            val latestDetached by rememberUpdatedState(state.newerUnloaded)
                            LaunchedEffect(listState, state.route?.tab) {
                                if (state.route?.tab != "conversation") return@LaunchedEffect
                                val channel = state.route!!.channel
                                snapshotFlow {
                                    latestRevision to if (!initialized) emptySet() else listState.layoutInfo.let { layout ->
                                        layout.visibleItemsInfo.filter { it.offset < layout.viewportEndOffset && it.offset + it.size > layout.viewportStartOffset }
                                            .mapNotNull { it.key as? String }.toSet()
                                    }
                                }.distinctUntilChanged().collect { (_, keys) -> graph.visibleRows(channel, keys) }
                            }
                            val latestRowSequences by rememberUpdatedState(state.rowSequences)
                            LaunchedEffect(listState, state.route?.tab) {
                                if (state.route?.tab != "conversation") return@LaunchedEffect
                                val channel = state.route!!.channel
                                snapshotFlow {
                                    if (!initialized || autoScrolling || listState.isScrollInProgress) null else {
                                        val layout = listState.layoutInfo
                                        layout.visibleItemsInfo.firstOrNull { (it.key as? String) in latestRowSequences }?.let {
                                            Triple(it.key as String, (layout.viewportStartOffset - it.offset).coerceAtLeast(0), following)
                                        }
                                    }
                                }.distinctUntilChanged().debounce(250).collect { position ->
                                    position?.let { (key, offset, follow) -> graph.browse(channel, key, offset, follow) }
                                }
                            }
                            val latestConversation by rememberUpdatedState(conversation)
                            LaunchedEffect(listState, state.route?.tab) {
                                if (state.route?.tab != "conversation") return@LaunchedEffect
                                var requestedBoundary: String? = null
                                snapshotFlow {
                                    val current = latestConversation
                                    val nearStart = initialized && !autoScrolling && listState.isScrollInProgress &&
                                        listState.firstVisibleItemIndex <= 12 && current.hasOlder && !current.loadingOlder
                                    current.entries.firstOrNull()?.key.takeIf { nearStart }
                                }.distinctUntilChanged().collect { boundary ->
                                    if (boundary != null && boundary != requestedBoundary) {
                                        requestedBoundary = boundary
                                        graph.older()
                                    }
                                }
                            }
                            LaunchedEffect(state.route?.tab, conversation.entries.lastOrNull()?.key, conversation.entries.size) {
                                if (state.route?.tab != "conversation") return@LaunchedEffect
                                if (!initialized) {
                                    val position = state.browsePosition?.takeIf { !it.following && state.route?.focus == null }
                                    val index = conversation.entries.indexOfFirst { it.key == position?.key }
                                    if (index >= 0) {
                                        autoScrolling = true
                                        try { listState.scrollToItem(index + if (conversation.hasOlder) 1 else 0, position!!.offset) }
                                        finally { autoScrolling = false }
                                        following = false; graph.follow(false)
                                    } else { scrollEnd(); following = true; graph.follow(true) }
                                    initialized = true
                                } else if (following && !latestDetached) scrollEnd()
                                if (!visibleLogged && conversation.entries.isNotEmpty()) {
                                    withFrameNanos { }
                                    graph.performanceMark("timeline-visible", SystemClock.elapsedRealtime() - state.channelOpenedAt, state.route!!.channel, if (state.firstOrigin == Origin.CACHE) "cache" else "network")
                                    visibleLogged = true
                                }
                            }
                            LaunchedEffect(listState) {
                                snapshotFlow { Triple(listState.isScrollInProgress, listState.canScrollForward, listState.layoutInfo.visibleItemsInfo.lastOrNull()?.size ?: 0) }
                                    .distinctUntilChanged().collect { (scrolling, canForward, _) ->
                                    graph.telemetry.scrolling.set(scrolling)
                                    if (initialized && !autoScrolling) {
                                        if (scrolling) { following = !canForward && !latestDetached; graph.follow(following) }
                                        else if (!canForward && !latestDetached) { following = true; graph.follow(true) }
                                        else if (following && !latestDetached) scrollEnd()
                                    }
                                }
                            }
                            var jump by remember { mutableStateOf(0) }
                            LaunchedEffect(jump) { if (jump > 0) { scrollEnd(); following = true; graph.follow(true) } }
                            val files = if (state.route?.tab in setOf("files", "terminal")) features?.files(state.route!!.channel) else null
                            LaunchedEffect(files, state.route?.focus) {
                                if (files != null && state.route?.tab == "files") state.route?.focus?.let(files::openLink)
                            }
                            val terminal = state.route?.tab == "terminal"
                            val content: (@Composable () -> Unit)? = when {
                                state.route?.tab == "tasks" && tasks != null -> ({ NativeTasksScreen(tasks, onSource = { taskSourceLink(it)?.let(graph::route) },
                                    onTurn = { graph.route(taskSourceLink(buildJsonObject { put("channelId", state.route!!.channel); put("requestId", it) })!!) }) })
                                terminal && files != null && features != null && services?.pty != null -> ({ key(files) {
                                    val fileState by files.state.collectAsStateWithLifecycle()
                                    TerminalScreen(services!!.pty!!, state.route!!.channel,
                                        fileState.devices.map { TerminalDevice(it.id, it.name, it.online) }, features.terminalPreferences,
                                        visible = lifecycle.isAtLeast(Lifecycle.State.STARTED), onClose = { graph.terminal(false) })
                                } })
                                state.route?.tab in setOf("files", "resources") && services != null && features != null -> ({
                                    val self = services!!.selfIds[state.route!!.channel].orEmpty()
                                    FilesResourcesSurface(services!!, state.route!!.channel, files, state.route?.tab == "resources",
                                        composerWritable(conversation.channel.access, self), onFiles = { graph.files(true) }, onResources = graph::resources,
                                        onClose = { graph.files(false) }, onAttach = { reference ->
                                            features.composer.addAttachments(listOf(reference)); graph.files(false)
                                        })
                                })
                                else -> null
                            }
                            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                            var restoreNoticeDismissed by remember(state.restoreNotice) { mutableStateOf(false) }
                            if (!restoreNoticeDismissed) state.restoreNotice?.let { info -> Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(info, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                TextButton({ restoreNoticeDismissed = true }) { Text("关闭") }
                            } }
                            state.channelError?.let { failure -> Surface(color = MaterialTheme.colorScheme.errorContainer) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(failure, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                    TextButton({ graph.retryChannel(state.route!!.channel) }) { Text("重试") }
                                }
                            } }
                            Box(Modifier.weight(1f)) {
                            ChannelScreen(conversation, ComposerViewState(""),
                                if (state.route?.tab == "tasks") ChannelTab.Tasks else ChannelTab.Conversation,
                                onBack = { drawer = true }, onTab = graph::tab, onScope = graph::scopeToggle, onActor = graph::filter,
                                onText = {}, onSend = {}, composerVisible = false,
                                onMenu = { action -> when (action) {
                                    Item.OpenFiles -> graph.files(true)
                                    Item.Resources -> graph.resources()
                                    Item.CloseFiles -> graph.files(false)
                                    Item.Recent -> recentOpen = true
                                    Item.OpenTerminal -> graph.terminal(true)
                                    Item.CloseTerminal -> graph.terminal(false)
                                    Item.ExportPerformance -> graph.export(true)
                                    is ChannelMenuAction.Process -> selectedMessage = action.messageId
                                    Item.Waiting -> waiting?.setCollapsed(false)
                                    Item.NewTask -> tasks?.beginCreation()
                                    Item.Running -> activityOpen = true
                                    Item.Members -> { actorOpen = false; rosterOpen = true }
                                    Item.Details -> governanceOpen = true
                                    Item.NewChild -> createChannelOpen = true
                                    else -> Unit
                                } }, onOlder = { following = false; graph.follow(false); graph.older() },
                                onLink = { link -> if (AppRoute.parse(link) != null) graph.route(link)
                                    else if (FilePaths.fromLink(state.route!!.channel, link) != null && features != null) {
                                        features.files(state.route!!.channel).openLink(link); graph.files(true)
                                    } else runCatching { uriHandler.openUri(link) } },
                                contentOverride = content,
                                messageContent = { message, default ->
                                    val target = messageTargets[message.id]
                                    val approval = taskState?.items?.firstOrNull { it.kind == "approval" && it.id == target?.envelope?.id }
                                    if (approval != null && tasks != null && taskState != null) {
                                        val command = taskState.commandStates.entries.firstOrNull { it.key.startsWith("${approval.key}:") && it.value.state == "submitting" }?.value
                                        ApprovalCard(approval, taskState.actorNames, busy = command != null,
                                            error = taskState.commandStates.filterKeys { it.startsWith("${approval.key}:") }.values.firstOrNull { it.error.isNotEmpty() }?.error.orEmpty(),
                                            onResolve = { action, text, note -> tasks.perform { execute(approval, action, note, text) } })
                                    } else if (target != null && features != null) {
                                        val self = services?.selfIds?.get(state.route!!.channel).orEmpty()
                                        val source = timelineTaskSource(state.route!!.channel, target)
                                        TimelineMessageActions(message, target.envelope, target.turn, self, actionRoster,
                                            writable = composerWritable(conversation.channel.access, self),
                                            online = state.navigation.connection == ConnectionPhase.OPEN,
                                            newerUnloaded = state.newerUnloaded, safeEditActors = waitingState?.safeEditActors.orEmpty(),
                                            editingTargetId = waitingState?.edit?.session?.targetId.orEmpty(),
                                            onReply = features.composer::setReply, onEdit = { waiting?.requestEdit(it) },
                                            onCreateTask = if (taskState?.creationReady == true && tasks != null && source != null) ({ _, _ -> tasks.beginCreation(source) }) else null,
                                            onStop = waiting?.let { owner -> { id -> owner.stop(id) } },
                                            busyActors = waitingState?.busyActors.orEmpty(), content = default)
                                    } else default()
                                    if (target?.seq == 0L && features != null) localById[target.envelope.id]?.let { row ->
                                        TimelineSubmissionStatus(row, features.composer::retry, features.composer::cancel)
                                    }
                                },
                                listState = listState, onJumpToBottom = { graph.jumpToBottom(); following = true; jump++ })
                            }
                            if (features != null && !terminal) {
                            if (waiting != null) WaitingDock(waiting)
                            AtollComposer(features.composer, showLocalEchoes = false, editing = waitingState?.let { waiting?.editorBinding(it) },
                                onPickChannelFiles = { pickingFile = true },
                                onPreviewAttachment = { reference -> features.files(reference.channelId).open(reference); graph.files(true) })
                            }
                            }
                            if (pickingFile && features != null) FilePicker(features.files(state.route!!.channel),
                                onPick = { reference -> features.composer.addAttachments(listOf(reference)); pickingFile = false },
                                onDismiss = { pickingFile = false })
                            LaunchedEffect(state.route?.focus, messageTargets) {
                                if (focusApplied) return@LaunchedEffect
                                val key = state.route?.focus?.takeIf { it.startsWith("turn:") }?.substringAfter(':') ?: return@LaunchedEffect
                                val index = conversation.entries.indexOfFirst { messageTargets[it.key]?.let { target -> target.turn?.requestId == key || target.envelope.id == key } == true }
                                if (index >= 0) { focusApplied = true; following = false; listState.scrollToItem(index + if (conversation.hasOlder) 1 else 0); graph.follow(false) }
                            }
                        }
                    }
                }
                if (settingsOpen) WorkspaceSettings(state, services, features?.terminalPreferences, { settingsOpen = false }, graph::export, { settingsOpen = false; graph.logout() })
                if (features != null && (governanceOpen || spaceOpen && state.navigation.account.root)) Dialog(
                    onDismissRequest = { governanceOpen = false; spaceOpen = false },
                    properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
                    Surface(Modifier.fillMaxSize()) {
                        if (spaceOpen) SpaceAdministrationScreen(features.governance, Modifier.safeDrawingPadding(), onClose = { spaceOpen = false })
                        else GovernanceScreen(features.governance, Modifier.safeDrawingPadding(), initialTab = "settings", onActor = { row ->
                            val declared = row["declared"] as? JsonObject ?: row
                            val id = declared["id"].string().ifEmpty { row["key"].string() }
                            if (id.isNotEmpty() && state.route != null) {
                                features.roster(state.route!!.channel).select(id)
                                governanceOpen = false; rosterOpen = true; actorOpen = true
                            }
                        }, onEnterChannel = { governanceOpen = false; graph.openChannel(it) }, onClose = { governanceOpen = false })
                    }
                }
                if (createChannelOpen && features != null) ChannelCreateDialog(features.governance,
                    onEnterChannel = { createChannelOpen = false; enterCreatedChannel(it) }, onClose = { createChannelOpen = false })
                if (rosterOpen && roster != null) Dialog(onDismissRequest = { rosterOpen = false; actorOpen = false; roster.clearSelection() },
                    properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
                    Surface(Modifier.fillMaxSize()) {
                        if (actorOpen) ActorDetail(roster, Modifier.safeDrawingPadding(), onClose = { actorOpen = false; roster.clearSelection() })
                        else RosterPanel(roster, Modifier.safeDrawingPadding(), onClose = { rosterOpen = false }, onSelect = { actorOpen = true })
                    }
                }
                selectedMessage?.let { key ->
                    val target = messageTargets[key]
                    AlertDialog(onDismissRequest = { selectedMessage = null }, title = { Text("消息操作") },
                        text = { Column {
                            Text(target?.envelope?.let(::messageText).orEmpty(), maxLines = 5)
                            val ownRequest = target?.turn?.takeIf { it.request.id == target.envelope.id && it.request.senderId == services?.selfIds?.get(state.route?.channel) }
                            if (ownRequest != null && waiting != null) TextButton({ waiting.requestEdit(ownRequest.requestId); selectedMessage = null }, enabled = !state.newerUnloaded) { Text("编辑") }
                            if (target != null && tasks != null) {
                                val source = timelineTaskSource(state.route!!.channel, target)
                                TextButton({ source?.let(tasks::beginCreation); selectedMessage = null },
                                    enabled = taskState?.creationReady == true && source != null) { Text("创建任务") }
                            }
                        } }, confirmButton = { TextButton({ selectedMessage = null }) { Text("关闭") } })
                }
                if (tasks != null && taskState?.creating == true && state.route?.tab != "tasks") CreateTaskDialog(tasks, tasks::endCreation)
                LaunchedEffect(tasks, state.route?.tab, state.route?.focus) {
                    if (state.route?.tab == "tasks") state.route?.focus?.let { tasks?.select(it) }
                }
                if (features != null) UiFormsHost(features.forms, state.roster.associate { it.id to it.name }, state.navigation.channels.associate { it.id to it.name })
                if (activityOpen) Dialog(onDismissRequest = { activityOpen = false }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
                    Surface(Modifier.fillMaxSize()) { Column(Modifier.safeDrawingPadding()) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("活动中心", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                            TextButton({ activityOpen = false }) { Text("关闭") }
                        }
                        ActivityCenterScreen(activity, Modifier.weight(1f), onSource = { source ->
                            taskSourceLink(source)?.let { activityOpen = false; graph.route(it) }
                        })
                    } }
                }
                if (recentOpen && features != null) key(features) {
                    val recent by features.fileHistory.recent.collectAsStateWithLifecycle()
                    AlertDialog(onDismissRequest = { recentOpen = false }, title = { Text("最近阅读") },
                        text = { Column(Modifier.heightIn(max = 400.dp)) {
                            if (recent.isEmpty()) Text("尚未阅读文件")
                            androidx.compose.foundation.lazy.LazyColumn { items(recent.size) { index ->
                                val reference = recent[index]
                                TextButton({ features.files(reference.channelId).open(reference); graph.route("atoll://channels/${reference.channelId}/files"); recentOpen = false }) { Text(reference.name) }
                            } }
                        } }, confirmButton = { TextButton({ recentOpen = false }) { Text("关闭") } })
                }
                if (drawer) ChannelDrawer(state.navigation, { drawer = false }, graph::openChannel, onLogout = graph::logout, onUpgrade = graph::upgrade, onSettings = { drawer = false; settingsOpen = true }, onActivity = { drawer = false; activityOpen = true },
                    onCreate = { drawer = false; createChannelOpen = true }, onSpaceAdmin = { if (state.navigation.account.root) { drawer = false; spaceOpen = true } })
            }
        }
        }
        }
    }
}

@Composable
private fun StatusPage(title: String, detail: String = "", button: String? = null, action: () -> Unit = {}) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        if (button == null) CircularProgressIndicator()
        Text(title, Modifier.padding(vertical = 16.dp), style = MaterialTheme.typography.titleMedium)
        if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall)
        if (button != null) Button(action) { Text(button) }
    }
}

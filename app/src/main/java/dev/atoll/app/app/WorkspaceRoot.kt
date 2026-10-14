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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.atoll.app.compose.AtollComposer
import dev.atoll.app.compose.ComposerActor
import dev.atoll.app.compose.TimelineMessageActions
import dev.atoll.app.compose.TimelineSubmissionStatus
import dev.atoll.app.waiting.WaitingRules
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
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import dev.atoll.app.compose.LocalSubmission
import dev.atoll.app.stars.StarredState
import dev.atoll.app.tasks.TimedActionsDialog
import dev.atoll.app.waiting.WaitingViewModel
import dev.atoll.app.waiting.WaitingUiState
import dev.atoll.app.tasks.TasksViewModel
import dev.atoll.app.tasks.TasksState
import dev.atoll.app.tasks.TaskCommandState
import dev.atoll.app.roster.RosterViewModel
import androidx.compose.foundation.gestures.scrollBy

@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
fun WorkspaceRoot(viewModel: WorkspaceViewModel, startedAt: Long) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val graph = viewModel.graph
    val services by graph.services.collectAsStateWithLifecycle()
    val features = viewModel.session(services)
    val stars = viewModel.starred
    val emptyStars = remember { MutableStateFlow(StarredState()) }
    val starredState by (stars?.state ?: emptyStars).collectAsStateWithLifecycle()
    // Keep subscription call sites present while the session/channel owners change.
    // Safe-call composable invocations would shift later slots on login/logout.
    val emptyWaiting = remember { MutableStateFlow<WaitingViewModel?>(null) }
    val emptyWaitingState = remember { MutableStateFlow(WaitingUiState()) }
    val emptySubmissions = remember { MutableStateFlow<List<LocalSubmission>>(emptyList()) }
    val waitingOwner by (features?.waiting ?: emptyWaiting).collectAsStateWithLifecycle()
    val waiting = waitingOwner?.takeIf { it.channelId == state.route?.channel }
    val observedWaitingState by (waiting?.state ?: emptyWaitingState).collectAsStateWithLifecycle()
    val waitingState = observedWaitingState.takeIf { waiting != null }
    var processReturnRequest by remember(state.route?.channel) { mutableStateOf<String?>(null) }
    val localSubmissions by (features?.composer?.submissions ?: emptySubmissions).collectAsStateWithLifecycle()
    val pendingResends by remember(features?.composer) {
        features?.composer?.state?.map { it.resendingRequestIds }?.distinctUntilChanged() ?: MutableStateFlow(emptySet<String>())
    }.collectAsStateWithLifecycle(initialValue = emptySet())
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
    var spaceOpen by remember(services?.wire, services?.boot) { mutableStateOf(false) }
    var createChannelOpen by remember(state.route?.channel, services?.wire, services?.boot) { mutableStateOf(false) }
    val routeFocus = RouteFocus.parse(state.route?.focus, state.route?.tab.orEmpty())
    val actorOpen = routeFocus?.kind == FocusKind.Participant
    val rosterOpen = actorOpen || routeFocus?.kind == FocusKind.Panel && routeFocus.key == WorkspacePanel.Members.key
    val governanceOpen = routeFocus?.kind == FocusKind.Channel
    val messageIndex by viewModel.messageTargets.collectAsStateWithLifecycle()
    val messageTargets = messageIndex.targets.takeIf { messageIndex.channelId == state.route?.channel }.orEmpty()
    val activity by viewModel.activity.collectAsStateWithLifecycle()
    val emptyApprovalCommands = remember { MutableStateFlow<Map<String, TaskCommandState>>(emptyMap()) }
    val approvalCommands by (features?.approvals?.state ?: emptyApprovalCommands).collectAsStateWithLifecycle()
    fun enterCreatedChannel(id: String) {
        val operation = features?.governance?.creations?.value?.lastOrNull { it.targetId == id }
        if (operation != null) graph.channelCreated(id, operation.parentId, operation.name, features!!.services, enter = true) else graph.openChannel(id)
    }
    val activityOpen = state.globalPanel == WorkspacePanel.Activity
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var drawer by rememberSaveable { mutableStateOf(false) }
    var loggedList by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val attachmentHandler = rememberAttachmentHandler(state.route?.channel.orEmpty(),
        onPreview = { reference -> features?.files(reference.channelId)?.let { files ->
            files.open(reference); graph.files(true)
        } },
        onDownload = { reference, destination -> features?.files(reference.channelId)?.download(context, reference, destination) },
        downloading = { state.route?.channel?.let { features?.files(it)?.state?.value?.download != null } == true })
    LaunchedEffect(state.phase, state.route, settingsOpen) { graph.telemetry.screen.set(if (settingsOpen) "settings" else state.route?.let { "channel:${it.channel}/${it.tab}" } ?: state.phase.name) }
    LaunchedEffect(state.phase) { if (state.phase != WorkspacePhase.Workspace) { settingsOpen = false; drawer = false } }
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
                BackHandler(state.route != null || state.canBack || drawer || settingsOpen) { if (settingsOpen) settingsOpen = false else if (drawer) drawer = false else graph.back() }
                Column(Modifier.fillMaxSize()) {
                    if (state.canForward) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(graph::forward) { Text("前进 ›") } }
                    state.notice?.let { text -> Surface(color = MaterialTheme.colorScheme.errorContainer) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton(graph::dismissNotice) { Text("关闭") }
                        }
                    } }
                    if (state.route == null) {
                        ChannelListScreen(state.navigation, graph::openChannel, onLogout = graph::logout, onUpgrade = graph::upgrade, onSettings = { drawer = false; settingsOpen = true }, onActivity = { graph.panel(WorkspacePanel.Activity) },
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
                            else StatusPage(if (access == ChannelAccess.Denied) "无权访问此频道" else if (!state.identityConfirmed) "正在确认身份 · ${state.navigation.channels.firstOrNull { it.id == state.route!!.channel }?.name.orEmpty()}" else "正在读取频道…", button = "频道列表", action = graph::home)
                        } else key(browseViewKey(conversation.mineOnly, conversation.actorFilters, conversation.showChannelActivity)) {
                            val readingView = browseViewKey(conversation.mineOnly, conversation.actorFilters, conversation.showChannelActivity)
                            val headerCount = timelineHeaderCount(conversation)
                            val listState = rememberLazyListState(initialFirstVisibleItemIndex = (conversation.entries.lastIndex + headerCount).coerceAtLeast(0))
                            var following by remember { mutableStateOf(state.browsePosition?.following != false) }
                            var initialized by remember { mutableStateOf(false) }
                            var autoScrolling by remember { mutableStateOf(false) }
                            var visibleLogged by remember { mutableStateOf(false) }
                            var pickingFile by remember { mutableStateOf(false) }
                            var focusApplied by remember(state.route?.tab, state.route?.focus) { mutableStateOf(false) }
                            var focusMissing by remember(state.route?.focus) { mutableStateOf(false) }
                            val bottomIndex = (conversation.entries.lastIndex + headerCount).coerceAtLeast(0)
                            val latestBottomIndex by rememberUpdatedState(bottomIndex)
                            DisposableEffect(listState) { onDispose { graph.telemetry.scrolling.set(false) } }
                            suspend fun scrollCentre(index: Int) {
                                autoScrolling = true
                                try {
                                    withFrameNanos { } // Layout-observer callbacks must finish before forceRemeasure.
                                    listState.scrollToItem(index)
                                    withFrameNanos { }
                                    val layout = listState.layoutInfo
                                    layout.visibleItemsInfo.firstOrNull { it.index == index }?.let {
                                        listState.scrollBy(centredScrollDelta(it.offset, it.size, layout.viewportStartOffset, layout.viewportEndOffset))
                                    }
                                } finally { autoScrolling = false }
                            }
                            suspend fun scrollEnd() {
                                autoScrolling = true
                                try {
                                    withFrameNanos { } // snapshotFlow may resume during placement's snapshot apply.
                                    listState.scrollToItem(latestBottomIndex)
                                    withFrameNanos { }
                                    val last = listState.layoutInfo.visibleItemsInfo.lastOrNull { it.index == latestBottomIndex }
                                    if (last != null) listState.scrollBy((last.offset + last.size - listState.layoutInfo.viewportEndOffset).coerceAtLeast(0).toFloat())
                                } finally { autoScrolling = false }
                            }
                            LaunchedEffect(state.route?.tab) { graph.follow(state.route?.tab == "conversation" && following) }
                            val latestRevision by rememberUpdatedState(state.timeline?.revision)
                            val latestDetached by rememberUpdatedState(state.newerUnloaded)
                            val latestCovered by rememberUpdatedState(drawer || settingsOpen || activityOpen || governanceOpen || rosterOpen || createChannelOpen || routeFocus != null)
                            LaunchedEffect(listState, state.route?.tab) {
                                if (state.route?.tab != "conversation") return@LaunchedEffect
                                val channel = state.route!!.channel
                                snapshotFlow {
                                    latestRevision to if (!initialized || latestCovered) emptySet() else listState.layoutInfo.let { layout ->
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
                                    if (!initialized || latestCovered || autoScrolling || listState.isScrollInProgress) null else {
                                        val layout = listState.layoutInfo
                                        layout.visibleItemsInfo.firstOrNull { (it.key as? String) in latestRowSequences }?.let {
                                            Triple(it.key as String, (layout.viewportStartOffset - it.offset).coerceAtLeast(0), following)
                                        }
                                    }
                                }.distinctUntilChanged().debounce(250).collect { position ->
                                    position?.let { (key, offset, follow) -> graph.browse(channel, readingView, key, offset, follow) }
                                }
                            }
                            val latestConversation by rememberUpdatedState(conversation)
                            LaunchedEffect(listState, state.route?.tab) {
                                if (state.route?.tab != "conversation") return@LaunchedEffect
                                var requestedBoundary: String? = null
                                snapshotFlow {
                                    val current = latestConversation
                                    val nearStart = initialized && !latestCovered && !autoScrolling && listState.isScrollInProgress &&
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
                                    val index = browseEntryIndex(conversation.entries, position)
                                    if (routeFocus?.kind == FocusKind.Turn) {
                                        following = false; graph.follow(false)
                                    } else if (index >= 0) {
                                        autoScrolling = true
                                        try { withFrameNanos { }; listState.scrollToItem(index + headerCount, position!!.offset) }
                                        finally { autoScrolling = false }
                                        following = false; graph.follow(false)
                                    } else { scrollEnd(); following = true; graph.follow(true) }
                                    if (routeFocus?.kind != FocusKind.Turn) initialized = true
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
                            LaunchedEffect(jump) { if (jump > 0) { scrollEnd(); initialized = true; following = true; graph.follow(true) } }
                            val files = if (state.route?.tab in setOf("files", "terminal")) features?.files(state.route!!.channel) else null
                            LaunchedEffect(files, state.route?.focus) {
                                if (files != null && state.route?.tab == "files") routeFocus?.takeIf { it.kind == FocusKind.Artifact }?.key?.let { resource ->
                                    if (!files.openLink(resource)) files.open(dev.atoll.app.files.FileReference(state.route!!.channel, resource, resource.substringAfterLast('/')))
                                }
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
                            if (focusMissing) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("目标消息尚未加载，请加载更早历史或返回最新动态。", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                TextButton({ graph.older() }, enabled = conversation.hasOlder && !conversation.loadingOlder) { Text("加载更早") }
                            }
                            if (!restoreNoticeDismissed) state.restoreNotice?.let { info -> Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(info, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                TextButton({ restoreNoticeDismissed = true }) { Text("关闭") }
                            } }
                            state.channelError?.takeUnless { state.channelErrorDismissed }?.let { failure -> Surface(color = MaterialTheme.colorScheme.errorContainer) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(failure, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                    TextButton({ graph.retryChannel(state.route!!.channel) }) { Text("重试") }
                                    TextButton({ graph.dismissChannelError(state.route!!.channel, failure) }) { Text("关闭") }
                                }
                            } }
                            Box(Modifier.weight(1f)) {
                            ChannelScreen(conversation, ComposerViewState(""),
                                if (state.route?.tab == "tasks") ChannelTab.Tasks else ChannelTab.Conversation,
                                onBack = graph::home, onTab = graph::tab, onScope = graph::scopeToggle, onActor = graph::filter, onChannelActivity = graph::channelActivity,
                                onText = {}, onSend = {}, composerVisible = false,
                                onMenu = { action -> when (action) {
                                    Item.OpenFiles -> graph.files(true)
                                    Item.Resources -> graph.resources()
                                    Item.CloseFiles -> graph.files(false)
                                    Item.Recent -> graph.panel(WorkspacePanel.Recent)
                                    Item.Stars -> graph.panel(WorkspacePanel.Stars)
                                    Item.Timers, Item.Schedule -> graph.panel(WorkspacePanel.Timers)
                                    Item.OpenTerminal -> graph.terminal(true)
                                    Item.CloseTerminal -> graph.terminal(false)
                                    Item.ExportPerformance -> graph.export(true)
                                    is ChannelMenuAction.Process -> {
                                        val target = processTarget(action.messageId, messageTargets, state.timelineSource)
                                        val id = target?.turn?.requestId ?: target?.envelope?.id
                                        if (id != null) graph.route(focusedChannelLink(state.route!!.channel, FocusKind.Turn, id))
                                    }
                                    Item.Waiting -> waiting?.setCollapsed(false)
                                    Item.NewTask -> tasks?.beginCreation()
                                    Item.Running -> graph.panel(WorkspacePanel.Running)
                                    Item.Members -> graph.panel(WorkspacePanel.Members)
                                    Item.Details -> graph.route(focusedChannelLink(state.route!!.channel, FocusKind.Channel, state.route!!.channel))
                                    Item.NewChild -> createChannelOpen = true
                                    else -> Unit
                                } }, onOlder = { following = false; graph.follow(false); graph.older() },
                                onLink = { link -> if (AppRoute.parse(link) != null) graph.route(link)
                                    else if (FilePaths.fromLink(state.route!!.channel, link) != null && features != null) {
                                        features.files(state.route!!.channel).openLink(link); graph.files(true)
                                    } else runCatching { uriHandler.openUri(link) } },
                                contentOverride = content,
                                onAttachment = attachmentHandler.takeIf { features != null },
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
                                            busyActors = waitingState?.busyActors.orEmpty(),
                                            starred = starredState.isStarred(state.route!!.channel, target.envelope.id),
                                            onToggleStar = stars?.let { it::toggle },
                                            onOpenProcess = { openTurnProcess(graph, state.route!!.channel, it, messageTargets, state.timelineSource) },
                                            onResend = resendAction(features.composer, target, self, message.turnState?.lostReason.orEmpty(), state.newerUnloaded),
                                            resendEnabled = composerWritable(conversation.channel.access, self) &&
                                                target.turn?.requestId !in pendingResends && waitingState?.edit?.session == null,
                                            content = default)
                                    } else default()
                                    if (target?.seq == 0L && features != null) localById[target.envelope.id]?.let { row ->
                                        TimelineSubmissionStatus(row, features.composer::retry, features.composer::cancel)
                                    }
                                },
                                newerUnloaded = state.newerUnloaded,
                                connectionPhase = state.navigation.connection, onReconnect = graph::reconnect,
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
                            LaunchedEffect(processReturnRequest, conversation.entries, messageTargets) {
                                val id = processReturnRequest ?: return@LaunchedEffect
                                val index = timelineFocusIndex(conversation.entries, messageTargets, state.timelineSource, id)
                                focusMissing = index < 0
                                if (index >= 0) {
                                    following = false; graph.follow(false)
                                    scrollCentre(index + headerCount)
                                    processReturnRequest = null
                                }
                            }
                            LaunchedEffect(state.route?.focus, state.route?.tab, messageTargets) {
                                if (state.route?.tab != "conversation") return@LaunchedEffect
                                if (focusApplied) return@LaunchedEffect
                                val key = routeFocus?.takeIf { it.kind == FocusKind.Turn }?.key ?: return@LaunchedEffect
                                following = false; graph.follow(false)
                                val index = timelineFocusIndex(conversation.entries,messageTargets,state.timelineSource,key)
                                focusMissing = index < 0
                                if (index >= 0) {
                                    scrollCentre(index + headerCount)
                                    focusApplied = true; initialized = true; following = false; graph.follow(false)
                                }
                            }
                        }
                    }
                }
                if (settingsOpen) WorkspaceSettings(state, services, features?.terminalPreferences, { settingsOpen = false }, graph::export, { settingsOpen = false; graph.logout() },
                    onSpaceAdmin = if (state.navigation.account.root && features != null) ({ settingsOpen = false; spaceOpen = true }) else null,
                    onUpgrade = graph::upgrade)
                if (features != null && (governanceOpen || spaceOpen && state.navigation.account.root)) Dialog(
                    onDismissRequest = { if (spaceOpen) spaceOpen = false else graph.closeFocus() },
                    properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
                    Surface(Modifier.fillMaxSize()) {
                        if (spaceOpen) SpaceAdministrationScreen(features.governance, Modifier.safeDrawingPadding(), onClose = { spaceOpen = false })
                        else GovernanceScreen(features.governance, Modifier.safeDrawingPadding(), initialTab = "settings", onActor = { row ->
                            val declared = row["declared"] as? JsonObject ?: row
                            val id = declared["id"].string().ifEmpty { row["key"].string() }
                            if (id.isNotEmpty() && state.route != null) {
                                graph.route(focusedChannelLink(state.route!!.channel, FocusKind.Participant, id))
                            }
                        }, onEnterChannel = graph::openChannel, onClose = graph::closeFocus)
                    }
                }
                if (createChannelOpen && features != null) ChannelCreateDialog(features.governance,
                    onEnterChannel = { createChannelOpen = false; enterCreatedChannel(it) }, onClose = { createChannelOpen = false })
                if (rosterOpen && roster != null) Dialog(onDismissRequest = graph::closeFocus,
                    properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
                    Surface(Modifier.fillMaxSize()) {
                        if (actorOpen) ActorDetail(roster, Modifier.safeDrawingPadding(), onClose = graph::closeFocus)
                        else RosterPanel(roster, Modifier.safeDrawingPadding(), onClose = graph::closeFocus, onSelect = { actor -> graph.route(focusedChannelLink(state.route!!.channel, FocusKind.Participant, actor.id)) })
                    }
                }
                if (routeFocus?.kind == FocusKind.Turn) key(routeFocus.key) {
                    val target = remember(routeFocus.key, messageTargets, state.timelineSource) { processTarget(routeFocus.key, messageTargets, state.timelineSource) }
                    if (target != null) {
                        val source = state.timelineSource
                        val detached = state.newerUnloaded
                        val self = services?.selfIds?.get(state.route!!.channel).orEmpty()
                        val panel by produceState<ProcessPanelViewState?>(null, target, source, detached, self, state.roster) {
                            processPanelUpdates(target, source, detached, selfId = self, roster = state.roster)
                                .flowOn(kotlinx.coroutines.Dispatchers.Default).collect { value = it }
                        }
                        panel?.let { presentation ->
                            val writable = state.conversation?.channel?.access?.let { composerWritable(it, self) } == true
                            val enabled = waiting != null && writable && state.navigation.connection == ConnectionPhase.OPEN && !detached &&
                                presentation.status in setOf("processing", "queued", "pending") && waitingState?.edit?.session == null
                            val row = waitingState?.groups?.firstNotNullOfOrNull { group -> group.rows.firstOrNull { it.turn.requestId == presentation.requestId } }
                            val stop = target.turn?.let { WaitingRules.interrupt(it, writable) }
                            ProcessPanel(presentation, graph::closeFocus,
                                onLink = { link -> if (AppRoute.parse(link) != null) graph.route(link)
                                    else if (FilePaths.fromLink(state.route!!.channel, link) != null && features != null) {
                                        features.files(state.route!!.channel).openLink(link); graph.files(true)
                                    } else runCatching { uriHandler.openUri(link) } },
                                onAttachment = attachmentHandler.takeIf { features != null },
                                onProcess = { id -> graph.route(focusedChannelLink(state.route!!.channel, FocusKind.Turn, id)) },
                                onOpenRequest = { id -> processReturnRequest = id; graph.closeFocus() },
                                onStop = if (enabled && stop != null) ({ waiting?.stop(presentation.requestId) }) else null,
                                onCancel = if (enabled && row?.actions?.canCancel == true) ({ waiting?.cancel(presentation.requestId) }) else null,
                                onGuide = if (enabled && row?.actions?.insert != null && presentation.executionTurnId.isNotEmpty())
                                    ({ text -> waiting?.clearError(); waiting?.guide(presentation.requestId, text) }) else null,
                                controlsBusy = presentation.agent?.id in waitingState?.busyActors.orEmpty(),
                                guideError = waitingState?.error.orEmpty())
                        }
                    } else ContentPanel("过程", graph::closeFocus) {
                        Text("目标消息尚未加载，请加载更早历史后重试。", Modifier.padding(16.dp))
                        TextButton(graph::older, enabled = state.conversation?.hasOlder == true && state.conversation?.loadingOlder != true) { Text("加载更早") }
                    }
                }
                if (routeFocus?.kind == FocusKind.Panel) key(routeFocus.key) {
                    var pendingJump by remember { mutableStateOf<String?>(null) }
                    fun jumpLoaded(id: String): Boolean {
                        if (messageTargets.values.none { it.envelope.id == id || it.turn?.requestId == id }) return false
                        pendingJump = id; return true
                    }
                    fun closeOrJump() {
                        val target = pendingJump
                        if (target != null && state.route != null) graph.route(focusedChannelLink(state.route!!.channel, FocusKind.Turn, target))
                        else graph.closeFocus()
                    }
                    when (routeFocus.key) {
                        WorkspacePanel.Members.key -> Unit // Rendered by the roster owner above.
                        WorkspacePanel.Running.key -> RunningDrawer(remember(state.timeline, state.roster) { runningPanel(state.timeline?.runningTasks.orEmpty(), state.roster) }, ::jumpLoaded, ::closeOrJump)
                        WorkspacePanel.Stars.key -> StarredDrawer(remember(starredState, state.route?.channel, state.roster) {
                            starredState.drawerItems(state.route?.channel.orEmpty(), state.roster.associate { it.id to it.name }, ::panelTime)
                        }, ::jumpLoaded, { id -> stars?.remove(state.route?.channel.orEmpty(), id) }, ::closeOrJump)
                        WorkspacePanel.Timers.key -> if (tasks != null) TimedActionsDialog(tasks, graph::closeFocus)
                        WorkspacePanel.Recent.key -> if (features != null) key(features) {
                            val recent by features.fileHistory.recent.collectAsStateWithLifecycle()
                            RecentReadsPanel(recent, { reference ->
                                features.files(reference.channelId).open(reference)
                                val id = reference.resourceId + if (reference.line > 0) ":${reference.line}" else ""
                                graph.route(focusedChannelLink(reference.channelId, FocusKind.Artifact, id))
                            }, graph::closeFocus)
                        }
                        else -> ContentPanel("未找到面板", graph::closeFocus) { Text("此面板不可用", Modifier.padding(16.dp)) }
                    }
                }
                if (tasks != null && taskState?.creating == true && state.route?.tab != "tasks") CreateTaskDialog(tasks, tasks::endCreation)
                LaunchedEffect(tasks, state.route?.tab, state.route?.focus) {
                    routeFocus?.takeIf { it.kind == FocusKind.WorkItem }?.key?.let { tasks?.select(it) }
                }
                LaunchedEffect(routeFocus, roster, state.route?.tab) {
                    if (routeFocus?.kind == FocusKind.Artifact && state.route?.tab != "files") graph.files(true)
                    if (routeFocus?.kind == FocusKind.WorkItem && state.route?.tab != "tasks") graph.tab(ChannelTab.Tasks)
                    if (routeFocus?.kind == FocusKind.Participant && roster != null) {
                        if (roster.state.value.selected?.id != routeFocus.key) roster.select(routeFocus.key)
                    }
                }
                if (features != null) UiFormsHost(features.forms, state.roster.associate { it.id to it.name }, state.navigation.channels.associate { it.id to it.name })
                if (activityOpen) Dialog(onDismissRequest = graph::closeFocus, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
                    Surface(Modifier.fillMaxSize(), color = dev.atoll.app.ui.theme.AtollColors.Ground) { Column(Modifier.safeDrawingPadding()) {
                        dev.atoll.app.ui.components.PageTitle("活动") {
                            TextButton(graph::closeFocus, Modifier.heightIn(min = 44.dp)) { Text("关闭", color = dev.atoll.app.ui.theme.AtollColors.Muted) }
                        }
                        ActivityCenterScreen(activity, Modifier.weight(1f), approvalCommands = approvalCommands,
                            onResolve = if (features == null) null else ({ row, action -> viewModel.resolveActivity(row.key, action) }), onSource = { source ->
                            taskSourceLink(source)?.let(graph::route)
                        })
                    } }
                }
                if (drawer) ChannelDrawer(state.navigation, { drawer = false }, graph::openChannel, onLogout = graph::logout, onUpgrade = graph::upgrade, onSettings = { drawer = false; settingsOpen = true }, onActivity = { drawer = false; graph.panel(WorkspacePanel.Activity) },
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

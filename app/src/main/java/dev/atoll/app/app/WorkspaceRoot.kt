package dev.atoll.app.app

import android.os.SystemClock
import android.util.Log
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
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.foundation.gestures.scrollBy

@Composable
fun WorkspaceRoot(viewModel: WorkspaceViewModel, startedAt: Long) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val graph = viewModel.graph
    val services by graph.services.collectAsStateWithLifecycle()
    val features = viewModel.session(services)
    var recentOpen by remember { mutableStateOf(false) }
    var drawer by rememberSaveable { mutableStateOf(false) }
    var loggedList by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    AtollTheme {
        when (state.phase) {
            WorkspacePhase.Booting -> StatusPage("正在启动工作区…")
            WorkspacePhase.Login -> LoginScreen(state.login, graph::changeLogin, { graph.login(state.login) })
            WorkspacePhase.Incompatible -> StatusPage("客户端版本与节点不兼容", state.incompatible, "刷新页面", graph::reconnect)
            WorkspacePhase.Workspace -> {
                BackHandler(state.route != null || drawer) { if (drawer) drawer = false else graph.back() }
                Column(Modifier.fillMaxSize()) {
                    state.notice?.let { text -> Surface(color = MaterialTheme.colorScheme.errorContainer) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton(graph::dismissNotice) { Text("关闭") }
                        }
                    } }
                    if (state.route == null) {
                        ChannelListScreen(state.navigation, graph::openChannel, onLogout = graph::logout, onUpgrade = graph::upgrade)
                        LaunchedEffect(state.navigation.channels.isNotEmpty()) {
                            if (!loggedList && state.navigation.channels.isNotEmpty()) {
                                withFrameNanos { }
                                Log.i("AtollPerf", "channel-list-visible elapsed_ms=${SystemClock.elapsedRealtime() - startedAt}")
                                loggedList = true
                            }
                        }
                    } else key(state.route!!.channel) {
                        val conversation = state.conversation
                        if (conversation == null) {
                            val access = state.navigation.channels.firstOrNull { it.id == state.route!!.channel }?.access
                            StatusPage(if (access == ChannelAccess.Denied) "无权访问此频道" else if (!state.identityConfirmed) "正在确认身份 · ${state.navigation.channels.firstOrNull { it.id == state.route!!.channel }?.name.orEmpty()}" else "正在读取频道…", button = "频道列表", action = { drawer = true })
                        } else {
                            val listState = rememberLazyListState(initialFirstVisibleItemIndex = (conversation.entries.lastIndex + if (conversation.hasOlder) 1 else 0).coerceAtLeast(0))
                            var following by remember { mutableStateOf(true) }
                            var initialized by remember { mutableStateOf(false) }
                            var autoScrolling by remember { mutableStateOf(false) }
                            var visibleLogged by remember { mutableStateOf(false) }
                            var pickingFile by remember { mutableStateOf(false) }
                            val bottomIndex = (conversation.entries.lastIndex + if (conversation.hasOlder) 1 else 0).coerceAtLeast(0)
                            val latestBottomIndex by rememberUpdatedState(bottomIndex)
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
                            LaunchedEffect(state.route?.tab, conversation.entries.lastOrNull()?.key, conversation.entries.size) {
                                if (state.route?.tab != "conversation") return@LaunchedEffect
                                if (!initialized || following) {
                                    scrollEnd()
                                    initialized = true
                                }
                                if (!visibleLogged && conversation.entries.isNotEmpty()) {
                                    withFrameNanos { }
                                    Log.i("AtollPerf", "timeline-visible channel=${state.route!!.channel} source=${if (state.firstOrigin == Origin.CACHE) "cache" else "network"} elapsed_ms=${SystemClock.elapsedRealtime() - state.channelOpenedAt}")
                                    visibleLogged = true
                                }
                            }
                            LaunchedEffect(listState) {
                                snapshotFlow { Triple(listState.isScrollInProgress, listState.canScrollForward, listState.layoutInfo.visibleItemsInfo.lastOrNull()?.size ?: 0) }
                                    .distinctUntilChanged().collect { (scrolling, canForward, _) ->
                                    if (initialized && !autoScrolling) {
                                        if (scrolling) { following = !canForward; graph.follow(following) }
                                        else if (!canForward) { following = true; graph.follow(true) }
                                        else if (following) scrollEnd()
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
                                terminal && files != null && features != null && services?.pty != null -> ({
                                    val fileState by files.state.collectAsStateWithLifecycle()
                                    TerminalScreen(services!!.pty!!, state.route!!.channel,
                                        fileState.devices.map { TerminalDevice(it.id, it.name, it.online) }, features.terminalPreferences,
                                        visible = lifecycle.isAtLeast(Lifecycle.State.STARTED), onClose = { graph.terminal(false) })
                                })
                                files != null -> ({ FilesScreen(files, onClose = { graph.files(false) }) })
                                else -> null
                            }
                            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                            Box(Modifier.weight(1f)) {
                            ChannelScreen(conversation, ComposerViewState(""),
                                if (state.route?.tab == "tasks") ChannelTab.Tasks else ChannelTab.Conversation,
                                onBack = { drawer = true }, onTab = graph::tab, onScope = graph::scopeToggle, onActor = graph::filter,
                                onText = {}, onSend = {}, composerVisible = false,
                                onMenu = { action -> when (action) {
                                    Item.OpenFiles -> graph.files(true)
                                    Item.CloseFiles -> graph.files(false)
                                    Item.Recent -> recentOpen = true
                                    Item.OpenTerminal -> graph.terminal(true)
                                    Item.CloseTerminal -> graph.terminal(false)
                                    else -> Unit
                                } }, onOlder = { following = false; graph.follow(false); graph.older() },
                                onLink = { link -> if (AppRoute.parse(link) != null) graph.route(link)
                                    else if (FilePaths.fromLink(state.route!!.channel, link) != null && features != null) {
                                        features.files(state.route!!.channel).openLink(link); graph.files(true)
                                    } else runCatching { uriHandler.openUri(link) } },
                                contentOverride = content,
                                listState = listState, onJumpToBottom = { graph.jumpToBottom(); following = true; jump++ })
                            }
                            if (features != null && !terminal) AtollComposer(features.composer,
                                onPickChannelFiles = { pickingFile = true },
                                onPreviewAttachment = { reference -> features.files(reference.channelId).open(reference); graph.files(true) })
                            }
                            if (pickingFile && features != null) FilePicker(features.files(state.route!!.channel),
                                onPick = { reference -> features.composer.addAttachments(listOf(reference)); pickingFile = false },
                                onDismiss = { pickingFile = false })
                            LaunchedEffect(state.route?.focus) {
                                val key = state.route?.focus?.takeIf { it.startsWith("turn:") }?.substringAfter(':') ?: return@LaunchedEffect
                                val index = conversation.entries.indexOfFirst { it.key == key || it.key.startsWith("$key:") }
                                if (index >= 0) { following = false; listState.scrollToItem(index + if (conversation.hasOlder) 1 else 0); graph.follow(false) }
                            }
                        }
                    }
                }
                if (recentOpen && features != null) {
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
                if (drawer) ChannelDrawer(state.navigation, { drawer = false }, graph::openChannel, onLogout = graph::logout, onUpgrade = graph::upgrade)
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

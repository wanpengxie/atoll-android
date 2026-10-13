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
import dev.atoll.app.core.Origin
import dev.atoll.app.ui.screens.*
import dev.atoll.app.ui.theme.AtollTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.foundation.gestures.scrollBy

@Composable
fun WorkspaceRoot(viewModel: WorkspaceViewModel, startedAt: Long) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val graph = viewModel.graph
    var drawer by rememberSaveable { mutableStateOf(false) }
    var loggedList by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    AtollTheme {
        when (state.phase) {
            WorkspacePhase.Booting -> StatusPage("正在启动工作区…")
            WorkspacePhase.Login -> LoginScreen(state.login, graph::changeLogin, { graph.login(state.login) }, { graph.notice("注册入口将在账号设置中提供") })
            WorkspacePhase.Incompatible -> StatusPage("客户端版本与节点不兼容", state.incompatible, "重新连接", graph::reconnect)
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
                        ChannelListScreen(state.navigation, graph::openChannel, onLogout = graph::logout)
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
                            StatusPage(if (access == ChannelAccess.Denied) "无权访问此频道" else "正在读取频道…", button = "频道列表", action = { drawer = true })
                        } else {
                            val listState = rememberLazyListState(initialFirstVisibleItemIndex = (conversation.entries.lastIndex + if (conversation.hasOlder) 1 else 0).coerceAtLeast(0))
                            var following by remember { mutableStateOf(true) }
                            var initialized by remember { mutableStateOf(false) }
                            var autoScrolling by remember { mutableStateOf(false) }
                            var visibleLogged by remember { mutableStateOf(false) }
                            var composer by rememberSaveable { mutableStateOf("") }
                            val bottomIndex = (conversation.entries.lastIndex + if (conversation.hasOlder) 1 else 0).coerceAtLeast(0)
                            suspend fun scrollEnd() {
                                autoScrolling = true
                                try {
                                    listState.scrollToItem(bottomIndex)
                                    withFrameNanos { }
                                    val last = listState.layoutInfo.visibleItemsInfo.lastOrNull { it.index == bottomIndex }
                                    if (last != null) listState.scrollBy((last.offset + last.size - listState.layoutInfo.viewportEndOffset).coerceAtLeast(0).toFloat())
                                } finally { autoScrolling = false }
                            }
                            LaunchedEffect(conversation.entries.lastOrNull()?.key, conversation.entries.size) {
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
                            ChannelScreen(conversation, ComposerViewState(composer),
                                if (state.route?.tab == "tasks") ChannelTab.Tasks else ChannelTab.Conversation,
                                onBack = { drawer = true }, onTab = graph::tab, onScope = graph::scopeToggle, onActor = graph::filter,
                                onText = { composer = it }, onSend = {}, onOlder = { following = false; graph.follow(false); graph.older() },
                                onLink = { link -> if (AppRoute.parse(link) != null) graph.route(link) else runCatching { uriHandler.openUri(link) } },
                                listState = listState, onJumpToBottom = { graph.jumpToBottom(); following = true; jump++ })
                            LaunchedEffect(state.route?.focus) {
                                val key = state.route?.focus?.takeIf { it.startsWith("turn:") }?.substringAfter(':') ?: return@LaunchedEffect
                                val index = conversation.entries.indexOfFirst { it.key == key || it.key.startsWith("$key:") }
                                if (index >= 0) { following = false; listState.scrollToItem(index + if (conversation.hasOlder) 1 else 0); graph.follow(false) }
                            }
                        }
                    }
                }
                if (drawer) ChannelDrawer(state.navigation, { drawer = false }, graph::openChannel, onLogout = graph::logout)
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

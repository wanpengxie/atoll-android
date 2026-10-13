package dev.atoll.app.terminal

import android.view.KeyEvent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/** The host owns [client] so navigating between channels never creates extra sockets. */
@Composable
fun TerminalScreen(
    client: PtyClient,
    channelId: String,
    devices: List<TerminalDevice>,
    preferences: TerminalPreferences,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    onClose: (() -> Unit)? = null,
    onAttached: ((PtyHandle) -> Unit)? = null,
) {
    val context = LocalContext.current
    val theme by preferences.theme.collectAsState()
    var selected by remember(channelId) { mutableStateOf("") }
    val device = preferredTerminalDevice(devices, selected)
    var generation by remember { mutableIntStateOf(0) }
    var status by remember(channelId, device, generation) { mutableStateOf<PtyStatus>(PtyStatus.Connecting) }
    var ctrl by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val surface = remember(context, channelId, device, generation) { RemoteTerminalSurface(context) }
    DisposableEffect(surface) { onDispose { surface.dispose() } }
    val latestAttached by rememberUpdatedState(onAttached)
    var handle by remember(surface) { mutableStateOf<PtyHandle?>(null) }
    SideEffect { surface.setTheme(theme); surface.control(ctrl); surface.onControlConsumed = { ctrl = false } }
    LaunchedEffect(client, channelId, device, generation, visible, surface) {
        if (!visible || device.isEmpty()) return@LaunchedEffect
        val stream = client.attach(channelId, device, surface.columns, surface.rows, surface::onReady, surface::onData)
        handle = stream
        try { surface.bind(stream); latestAttached?.invoke(stream); stream.status.collect { status = it } }
        finally { surface.unbind(stream); stream.detach(); handle = null }
    }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Box {
                TextButton(onClick = { menu = true }) { Text(devices.firstOrNull { it.id == device }?.name ?: "选择设备") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    devices.forEach { d -> DropdownMenuItem(text = { Text(d.name + if (d.online == false) " · 离线" else "") }, onClick = { selected = d.id; menu = false }) }
                }
            }
            Text(if (!visible) "已收起" else when (val s = status) {
                PtyStatus.Connecting -> "连接中"
                is PtyStatus.Ready -> "已连接"
                is PtyStatus.Reconnecting -> "重连中 (${s.attempt})"
                is PtyStatus.Ended -> "已结束"
                PtyStatus.Detached -> "已收起"
            }, Modifier.weight(1f).padding(top = 12.dp), style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = preferences::toggle) { Text(if (theme == TerminalTheme.DARK) "浅色" else "深色") }
            if (onClose != null) TextButton(onClick = onClose) { Text("收起") }
        }
        when {
            devices.isEmpty() -> Text("这个频道还没有设备。", Modifier.padding(16.dp))
            device.isEmpty() -> Text("选择一个设备打开终端。", Modifier.padding(16.dp))
            !visible -> Text("终端已收起，shell 会保留。", Modifier.padding(16.dp))
            else -> {
                if (status is PtyStatus.Ended) Row(Modifier.fillMaxWidth()) {
                    Text((status as PtyStatus.Ended).detail.ifEmpty { "终端已结束" }, Modifier.weight(1f).padding(12.dp), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { handle?.close(); generation++ }) { Text("重开") }
                }
                AndroidView(factory = { surface }, modifier = Modifier.weight(1f).fillMaxWidth())
                Row(Modifier.fillMaxWidth().height(44.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    val keys = listOf("Esc" to KeyEvent.KEYCODE_ESCAPE, "Tab" to KeyEvent.KEYCODE_TAB, "↑" to KeyEvent.KEYCODE_DPAD_UP, "↓" to KeyEvent.KEYCODE_DPAD_DOWN, "←" to KeyEvent.KEYCODE_DPAD_LEFT, "→" to KeyEvent.KEYCODE_DPAD_RIGHT)
                    TextButton(onClick = { ctrl = !ctrl }, contentPadding = PaddingValues(4.dp), modifier = Modifier.weight(1f)) { Text(if (ctrl) "Ctrl●" else "Ctrl") }
                    keys.forEach { (label, code) -> TextButton(onClick = { surface.special(code) }, contentPadding = PaddingValues(4.dp), modifier = Modifier.weight(1f)) { Text(label) } }
                    TextButton(onClick = surface::pasteClipboard, contentPadding = PaddingValues(4.dp), modifier = Modifier.weight(1f)) { Text("贴") }
                    TextButton(onClick = surface::showKeyboard, contentPadding = PaddingValues(4.dp), modifier = Modifier.weight(1f)) { Text("⌨") }
                }
            }
        }
    }
}

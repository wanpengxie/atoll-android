package dev.atoll.app.terminal

import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.atoll.app.ui.components.PulseDot
import dev.atoll.app.ui.theme.AtollColors
import dev.atoll.app.ui.theme.AtollMono
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
    // Read state in this composition scope, not only inside the deferred
    // side effect or button label; native state must update with its label.
    val nativeTheme = theme
    val nativeControl = ctrl
    SideEffect { surface.setTheme(nativeTheme); surface.control(nativeControl); surface.onControlConsumed = { ctrl = false } }
    LaunchedEffect(client, channelId, device, generation, visible, surface) {
        if (!visible || device.isEmpty()) return@LaunchedEffect
        val stream = client.attach(channelId, device, surface.columns, surface.rows, surface::onReady, surface::onData)
        handle = stream
        try { surface.bind(stream); latestAttached?.invoke(stream); stream.status.collect { status = it } }
        finally { surface.unbind(stream); stream.detach(); handle = null }
    }
    val dark = theme == TerminalTheme.DARK
    val chrome = if (dark) Color(0xFF181A1D) else AtollColors.Surface
    val ink = if (dark) Color(0xFFE3E5E8) else AtollColors.Ink
    val muted = if (dark) Color(0xFF9BA1A9) else AtollColors.Muted
    val keyColor = if (dark) Color(0xFF26292E) else AtollColors.Subtle
    val label = if (!visible) "已收起" else when (val s = status) {
        PtyStatus.Connecting -> "连接中"
        is PtyStatus.Ready -> "已连接"
        is PtyStatus.Reconnecting -> "重连中 (${s.attempt})"
        is PtyStatus.Ended -> "已结束"
        PtyStatus.Detached -> "已收起"
    }
    Column(modifier.fillMaxSize().background(if (dark) Color(0xFF111315) else AtollColors.Ground)) {
        Row(Modifier.fillMaxWidth().background(chrome).heightIn(min = 52.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box {
                Row(Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(10.dp)).clickable { menu = true }.padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column {
                        Text(devices.firstOrNull { it.id == device }?.name ?: "选择设备", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = ink)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            PulseDot(when (status) { is PtyStatus.Ready -> Color(0xFF4CC38A); is PtyStatus.Ended -> AtollColors.Danger; else -> AtollColors.Warning },
                                pulsing = status !is PtyStatus.Ready && status !is PtyStatus.Ended && visible)
                            Text(label, fontSize = 12.sp, color = muted)
                        }
                    }
                    Text("⌄", color = muted)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    devices.forEach { d -> DropdownMenuItem(text = { Text(d.name + if (d.online == false) " · 离线" else "") }, onClick = { selected = d.id; menu = false }) }
                }
            }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = preferences::toggle, Modifier.heightIn(min = 36.dp), shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, if (dark) Color(0xFF33373D) else AtollColors.ControlLine), contentPadding = PaddingValues(horizontal = 12.dp)) {
                Text(if (dark) "浅色" else "深色", fontSize = 13.sp, color = ink)
            }
            if (onClose != null) TextButton(onClick = onClose) { Text("收起", fontSize = 14.sp, color = muted) }
        }
        HorizontalDivider(color = if (dark) Color(0xFF23262B) else AtollColors.Line)
        when {
            devices.isEmpty() -> TerminalNote("这个频道还没有设备。", muted)
            device.isEmpty() -> TerminalNote("选择一个设备打开终端。", muted)
            !visible -> TerminalNote("终端已收起，shell 会保留。", muted)
            else -> {
                if (status is PtyStatus.Ended) Row(Modifier.fillMaxWidth().background(if (dark) Color(0xFF2A1A19) else AtollColors.DangerSurface).padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text((status as PtyStatus.Ended).detail.ifEmpty { "终端已结束" }, Modifier.weight(1f).padding(vertical = 10.dp), fontSize = 13.sp,
                        color = if (dark) Color(0xFFF2B8B2) else AtollColors.DangerText)
                    TextButton(onClick = { handle?.close(); generation++ }) { Text("重开", fontWeight = FontWeight.SemiBold, color = ink) }
                }
                key(surface) { AndroidView(factory = { surface }, modifier = Modifier.weight(1f).fillMaxWidth()) }
                Row(Modifier.fillMaxWidth().background(chrome).padding(horizontal = 6.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val keys = listOf("Esc" to KeyEvent.KEYCODE_ESCAPE, "Tab" to KeyEvent.KEYCODE_TAB, "↑" to KeyEvent.KEYCODE_DPAD_UP, "↓" to KeyEvent.KEYCODE_DPAD_DOWN, "←" to KeyEvent.KEYCODE_DPAD_LEFT, "→" to KeyEvent.KEYCODE_DPAD_RIGHT)
                    TerminalKey(if (ctrl) "Ctrl●" else "Ctrl", if (ctrl) AtollColors.Lagoon else keyColor, if (ctrl) Color.White else ink, Modifier.weight(1.2f)) {
                        val next = !ctrl; surface.control(next); ctrl = next; surface.requestFocus()
                    }
                    keys.forEach { (name, code) -> TerminalKey(name, keyColor, ink, Modifier.weight(1f)) { surface.special(code) } }
                    TerminalKey("贴", keyColor, ink, Modifier.weight(1f), surface::pasteClipboard)
                    TerminalKey("⌨", keyColor, ink, Modifier.weight(1f), surface::showKeyboard)
                }
            }
        }
    }
}

@Composable
private fun TerminalNote(text: String, color: Color) {
    Text(text, Modifier.fillMaxWidth().padding(24.dp), fontSize = 15.sp, color = color)
}

@Composable
private fun TerminalKey(label: String, background: Color, content: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp)).background(background).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) {
        Text(label, fontSize = 14.sp, fontFamily = AtollMono, fontWeight = FontWeight.Medium, color = content, maxLines = 1)
    }
}

package dev.atoll.app.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.atoll.app.terminal.TerminalPreferences
import dev.atoll.app.terminal.TerminalTheme
import dev.atoll.app.ui.components.*
import dev.atoll.app.ui.screens.*
import dev.atoll.app.ui.theme.AtollColors
import dev.atoll.app.ui.theme.AtollMono
import kotlinx.coroutines.flow.MutableStateFlow

fun connectionLabel(phase: ConnectionPhase): String = when (phase) {
    ConnectionPhase.OPEN -> "已连接"; ConnectionPhase.CONNECTING -> "连接中"
    ConnectionPhase.RECONNECTING -> "重连中"; ConnectionPhase.CLOSED -> "已断开"
}

/** The third home destination; its content scrolls while home navigation stays visible. */
@Composable
fun WorkspaceSettings(state: WorkspaceState, services: WorkspaceServices?, terminal: TerminalPreferences?,
    onClose: () -> Unit, onExport: (Boolean) -> Unit, onLogout: () -> Unit,
    onSpaceAdmin: (() -> Unit)? = null, onUpgrade: (() -> Unit)? = null,
    onChannels: () -> Unit = onClose, onActivity: () -> Unit = {}) {
    val emptyTheme = remember { MutableStateFlow(TerminalTheme.DARK) }
    val theme by (terminal?.theme ?: emptyTheme).collectAsStateWithLifecycle()
    val channel = state.navigation.channels.firstOrNull { it.id == state.route?.channel }
    val canExport = services != null && services.boot.isNotEmpty() && channel?.id in services.selfIds &&
        state.navigation.connection == ConnectionPhase.OPEN && !state.exporting
    val account = state.navigation.account
    var upgradeOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(AtollColors.Ground).safeDrawingPadding()) {
        PageTitle("我", Modifier.padding(bottom = 8.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item("account") {
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = AtollColors.Surface,
                    border = BorderStroke(1.dp, AtollColors.Line)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        ActorAvatar(account.name.ifEmpty { account.id }, account.id, agent = false, size = 52.dp, self = true)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(account.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text((services?.principalId ?: account.id) + if (account.root) " · 空间管理员" else "", fontSize = 13.sp,
                                fontFamily = AtollMono, color = AtollColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            item("connection") { GroupCard {
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("服务器", Modifier.weight(1f), fontSize = 15.sp)
                    Text(services?.server ?: state.login.server, Modifier.weight(2.5f), fontSize = 13.sp, fontFamily = AtollMono,
                        color = AtollColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End)
                }
                HorizontalDivider(color = AtollColors.LineSoft)
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("连接", Modifier.weight(1f), fontSize = 15.sp)
                    val color = if (state.navigation.connection == ConnectionPhase.OPEN) AtollColors.LagoonText else AtollColors.Warning
                    PulseDot(color, pulsing = false)
                    Text(connectionLabel(state.navigation.connection), fontSize = 14.sp, color = color)
                }
            } }
            if (account.root && (onSpaceAdmin != null || state.navigation.upgrade != null)) item("administration") { GroupCard {
                if (onSpaceAdmin != null) GroupRow("空间管理", divider = state.navigation.upgrade != null, onClick = onSpaceAdmin)
                state.navigation.upgrade?.let { update -> GroupRow("节点版本", divider = false, onClick = { upgradeOpen = true }, trailing = {
                    if (update.canStart) StatusPill("可升级", Tone.Lagoon)
                    else Text(update.current, fontSize = 13.sp, color = AtollColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("›", fontSize = 18.sp, color = AtollColors.Faint)
                }) }
            } } else state.navigation.upgrade?.let { update -> item("version") { GroupCard {
                GroupRow("节点版本", value = "${update.current}（只读）", divider = false)
            } } }
            item("preferences") { GroupCard {
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("终端深色主题", Modifier.weight(1f), fontSize = 15.sp)
                    Box(Modifier.size(48.dp, 44.dp).toggleable(theme == TerminalTheme.DARK, enabled = terminal != null, role = Role.Switch,
                        onValueChange = { terminal?.toggle() }).semantics { contentDescription = "终端深色主题" }, contentAlignment = Alignment.Center) {
                        Box(Modifier.size(48.dp, 28.dp).clip(CircleShape)
                            .background(if (theme == TerminalTheme.DARK) AtollColors.Lagoon else AtollColors.ControlLine)) {
                            Box(Modifier.align(if (theme == TerminalTheme.DARK) Alignment.CenterEnd else Alignment.CenterStart)
                                .padding(3.dp).size(22.dp).clip(CircleShape).background(AtollColors.Surface))
                        }
                    }
                }
                HorizontalDivider(color = AtollColors.LineSoft)
                GroupRow("导出诊断", subtitle = if (channel == null) "先打开一个会员频道，再导出到该频道文件。" else "",
                    onClick = if (canExport) ({ onExport(false) }) else null,
                    trailing = { Icon(Icons.Outlined.Download, null, Modifier.size(20.dp), tint = AtollColors.Faint) })
                GroupRow("导出性能记录", subtitle = if (channel == null) "" else "保存到 # ${channel.name} 的频道文件", divider = false,
                    onClick = if (canExport) ({ onExport(true) }) else null,
                    trailing = { Icon(Icons.Outlined.Download, null, Modifier.size(20.dp), tint = AtollColors.Faint) })
            } }
            if (state.exporting) item("exporting") { Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Spinner(16.dp, AtollColors.Muted, AtollColors.Line); Spacer(Modifier.width(10.dp)); Text("正在导出，请稍候…", fontSize = 14.sp)
            } }
            item("logout") {
                OutlinedButton(onLogout, Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, AtollColors.Line),
                    colors = ButtonDefaults.outlinedButtonColors(containerColor = AtollColors.Surface, contentColor = AtollColors.Danger)) {
                    Text("退出当前账号", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
        HomeBar(HomeDestination.Me, onChannels = onChannels, onActivity = onActivity, onMe = {})
    }
    if (upgradeOpen) state.navigation.upgrade?.let { update -> UpgradePanel(update, state.navigation.connection, { upgradeOpen = false }, onUpgrade) }
}

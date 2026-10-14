package dev.atoll.app.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.atoll.app.terminal.TerminalPreferences
import dev.atoll.app.terminal.TerminalTheme
import dev.atoll.app.ui.components.*
import dev.atoll.app.ui.screens.ConnectionPhase
import dev.atoll.app.ui.theme.AtollColors
import dev.atoll.app.ui.theme.AtollMono
import kotlinx.coroutines.flow.MutableStateFlow

fun connectionLabel(phase: ConnectionPhase): String = when (phase) {
    ConnectionPhase.OPEN -> "已连接"; ConnectionPhase.CONNECTING -> "连接中"
    ConnectionPhase.RECONNECTING -> "重连中"; ConnectionPhase.CLOSED -> "已断开"
}

/** The 我 destination: account, connection, space administration, appearance and diagnostics. */
@Composable
fun WorkspaceSettings(state: WorkspaceState, services: WorkspaceServices?, terminal: TerminalPreferences?,
    onClose: () -> Unit, onExport: (Boolean) -> Unit, onLogout: () -> Unit,
    onSpaceAdmin: (() -> Unit)? = null, onUpgrade: (() -> Unit)? = null) {
    val emptyTheme = remember { MutableStateFlow(TerminalTheme.DARK) }
    val theme by (terminal?.theme ?: emptyTheme).collectAsStateWithLifecycle()
    val channel = state.navigation.channels.firstOrNull { it.id == state.route?.channel }
    val canExport = services != null && services.boot.isNotEmpty() && channel?.id in services.selfIds &&
        state.navigation.connection == ConnectionPhase.OPEN && !state.exporting
    val account = state.navigation.account
    var upgradeConfirmation by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = AtollColors.Ground) {
            Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                PageTitle("我") { TextButton(onClose, Modifier.heightIn(min = 44.dp)) { Text("关闭", fontSize = 15.sp, color = AtollColors.Muted) } }
                Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    GroupCard {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            ActorAvatar(account.name.ifEmpty { account.id }, account.id, agent = false, size = 52.dp)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(account.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text((services?.principalId ?: account.id) + if (account.root) " · 空间管理员" else "", fontSize = 13.sp,
                                    fontFamily = AtollMono, color = AtollColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    GroupCard {
                        GroupRow("服务器", value = services?.server ?: state.login.server)
                        GroupRow("连接", value = connectionLabel(state.navigation.connection),
                            valueColor = if (state.navigation.connection == ConnectionPhase.OPEN) AtollColors.LagoonText else AtollColors.Warning, divider = false)
                    }
                    if (account.root && (onSpaceAdmin != null || state.navigation.upgrade != null)) GroupCard {
                        if (onSpaceAdmin != null) GroupRow("空间管理", divider = state.navigation.upgrade != null, onClick = onSpaceAdmin)
                        state.navigation.upgrade?.let { update ->
                            GroupRow("节点版本", value = update.current, subtitle = update.label(state.navigation.connection), divider = false,
                                onClick = if (update.canStart && onUpgrade != null) ({ upgradeConfirmation = true }) else null)
                        }
                    } else state.navigation.upgrade?.let { GroupCard { GroupRow("节点版本", value = "${it.current}（只读）", divider = false) } }
                    GroupCard {
                        GroupRow("终端深色主题", divider = false, trailing = {
                            Switch(checked = theme == TerminalTheme.DARK, enabled = terminal != null, onCheckedChange = { terminal?.toggle() },
                                colors = SwitchDefaults.colors(checkedTrackColor = AtollColors.Lagoon, checkedThumbColor = AtollColors.Surface))
                        })
                    }
                    SectionLabel("诊断与性能")
                    GroupCard {
                        GroupRow("导出诊断", subtitle = if (channel == null) "先打开一个会员频道，再导出到该频道文件。" else "导出到：${channel.name}",
                            onClick = if (canExport) ({ onExport(false) }) else null)
                        GroupRow("导出性能记录", subtitle = if (channel == null) "" else "保存到 # ${channel.name} 的频道文件", divider = false,
                            onClick = if (canExport) ({ onExport(true) }) else null)
                    }
                    if (state.exporting) Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Spinner(16.dp, AtollColors.Muted, AtollColors.Line); Spacer(Modifier.width(10.dp)); Text("正在导出，请稍候…", fontSize = 14.sp)
                    }
                    state.notice?.let { NoticeBanner(it, Tone.Neutral) }
                    Box(Modifier.fillMaxWidth().background(AtollColors.Ground)) {
                        AtollButton("退出当前账号", onLogout, Modifier.fillMaxWidth(), kind = ButtonKind.Danger, height = 52.dp)
                    }
                }
            }
        }
    }
    if (upgradeConfirmation) state.navigation.upgrade?.let { update -> AlertDialog(onDismissRequest = { upgradeConfirmation = false },
        title = { Text("升级到 ${update.latest}？") },
        text = { Text("升级会重启 Atoll，并暂时中断当前连接和正在进行的工作。频道记录、任务和工作区数据会保留。") },
        confirmButton = { TextButton({ upgradeConfirmation = false; onUpgrade?.invoke() }) { Text("升级") } },
        dismissButton = { TextButton({ upgradeConfirmation = false }) { Text("取消") } }) }
}

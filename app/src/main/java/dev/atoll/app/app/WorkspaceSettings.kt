package dev.atoll.app.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.atoll.app.terminal.TerminalPreferences
import dev.atoll.app.terminal.TerminalTheme
import dev.atoll.app.ui.screens.ConnectionPhase
import kotlinx.coroutines.flow.MutableStateFlow

@Composable
fun WorkspaceSettings(state: WorkspaceState, services: WorkspaceServices?, terminal: TerminalPreferences?,
    onClose: () -> Unit, onExport: (Boolean) -> Unit, onLogout: () -> Unit) {
    val emptyTheme = remember { MutableStateFlow(TerminalTheme.DARK) }
    val theme by (terminal?.theme ?: emptyTheme).collectAsStateWithLifecycle()
    val channel = state.navigation.channels.firstOrNull { it.id == state.route?.channel }
    val canExport = services != null && services.boot.isNotEmpty() && channel?.id in services.selfIds &&
        state.navigation.connection == ConnectionPhase.OPEN && !state.exporting
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("设置", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                    TextButton(onClose) { Text("关闭") }
                }
                Text("服务器", style = MaterialTheme.typography.labelLarge)
                Text(services?.server ?: state.login.server)
                Text("账号", style = MaterialTheme.typography.labelLarge)
                Text(state.navigation.account.name)
                Text(services?.principalId ?: state.navigation.account.id, style = MaterialTheme.typography.bodySmall)
                Text("连接：${state.navigation.connection.name}", style = MaterialTheme.typography.bodySmall)
                state.navigation.upgrade?.let { Text("节点版本：${it.current}（只读）", style = MaterialTheme.typography.bodySmall) }
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("终端深色主题", Modifier.weight(1f))
                    Switch(checked = theme == TerminalTheme.DARK, enabled = terminal != null, onCheckedChange = { terminal?.toggle() })
                }
                HorizontalDivider()
                Text("诊断与性能", style = MaterialTheme.typography.titleMedium)
                Text(if (channel == null) "先打开一个会员频道，再导出到该频道文件。" else "导出到：${channel.name}", style = MaterialTheme.typography.bodySmall)
                OutlinedButton({ onExport(false) }, Modifier.fillMaxWidth(), enabled = canExport) { Text("导出诊断") }
                OutlinedButton({ onExport(true) }, Modifier.fillMaxWidth(), enabled = canExport) { Text("导出性能记录") }
                if (state.exporting) Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp)); Spacer(Modifier.width(10.dp)); Text("正在导出，请稍候…")
                }
                state.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                HorizontalDivider()
                Button(onLogout, Modifier.fillMaxWidth()) { Text("退出当前账号") }
            }
        }
    }
}

package dev.atoll.app.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.atoll.app.net.AndroidKeyValueStore
import dev.atoll.app.terminal.TerminalPreferences
import dev.atoll.app.ui.screens.AccountViewState
import dev.atoll.app.ui.screens.ChannelNavigationViewState
import dev.atoll.app.ui.screens.ConnectionPhase
import dev.atoll.app.ui.screens.LoginViewState
import dev.atoll.app.ui.theme.AtollTheme

/** Offline UI fixture. Never initializes AppGraph or accesses session credentials. */
class SettingsDemoActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val preferences = TerminalPreferences(AndroidKeyValueStore(getSharedPreferences("settings-demo", MODE_PRIVATE)))
        val fixture = WorkspaceState(
            phase = WorkspacePhase.Workspace,
            identityConfirmed = true,
            login = LoginViewState(server = "https://mock.invalid"),
            navigation = ChannelNavigationViewState(emptyList(), ConnectionPhase.CLOSED,
                AccountViewState("demo", "离线测试账号")),
            notice = "离线界面测试；退出只关闭此页面。",
        )
        setContent {
            AtollTheme {
                WorkspaceSettings(fixture, services = null, terminal = preferences,
                    onClose = { finish() }, onExport = {}, onLogout = { finish() })
            }
        }
    }
}

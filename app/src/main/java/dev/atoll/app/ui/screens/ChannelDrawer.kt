package dev.atoll.app.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun ChannelDrawer(state: ChannelNavigationViewState, onClose: () -> Unit, onOpen: (String) -> Unit,
    onCreate: () -> Unit = {}, onActivity: () -> Unit = {}, onSpaceAdmin: () -> Unit = {},
    onLogout: () -> Unit = {}, onUpgrade: () -> Unit = {}) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        ChannelListScreen(state, { onOpen(it); onClose() }, onCreate, onActivity, onSpaceAdmin, onLogout, onUpgrade, onClose)
    }
}

package dev.atoll.app.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import dev.atoll.app.ui.theme.AtollColors

/** A form stays in the Activity window so normal keyboard insets apply. */
@Composable
fun FullScreenForm(onClose: () -> Unit, content: @Composable () -> Unit) {
    BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize().background(AtollColors.Surface).pointerInput(Unit) { detectTapGestures {} }) {
        content()
    }
}

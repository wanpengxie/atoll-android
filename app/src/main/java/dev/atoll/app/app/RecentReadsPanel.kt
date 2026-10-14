package dev.atoll.app.app

import androidx.compose.runtime.Composable
import dev.atoll.app.files.FileReference
import dev.atoll.app.ui.screens.ContentPanel
import dev.atoll.app.ui.screens.RecentReadsContent

@Composable
internal fun RecentReadsPanel(recent: List<FileReference>, onOpen: (FileReference) -> Unit, onClose: () -> Unit) {
    ContentPanel("最近阅读", onClose) { RecentReadsContent(recent, onOpen) }
}

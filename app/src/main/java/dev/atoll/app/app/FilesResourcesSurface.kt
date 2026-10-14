package dev.atoll.app.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import dev.atoll.app.files.FileReference
import dev.atoll.app.files.FilesScreen
import dev.atoll.app.files.FilesViewModel
import dev.atoll.app.resources.ResourcesScreen
import dev.atoll.app.resources.ResourcesViewModel

@Composable
internal fun FilesResourcesSurface(services: WorkspaceServices, channel: String, files: FilesViewModel?,
    resourceTab: Boolean, writable: Boolean, onFiles: () -> Unit, onResources: () -> Unit, onClose: () -> Unit,
    onAttach: ((FileReference) -> Unit)?) {
    Column(Modifier.fillMaxSize()) {
        if (resourceTab) {
            val vm = remember(services.wire, services.boot, channel) { ResourcesViewModel(services, channel) }
            SideEffect { vm.context(writable) }
            DisposableEffect(vm) { onDispose(vm::close) }
            TextButton(onClick = onFiles) { Text("‹ 返回文件") }
            ResourcesScreen(vm, Modifier.weight(1f), onClose)
        } else if (files != null) FilesScreen(files, Modifier.weight(1f), onClose, onAttach, onResources)
    }
}

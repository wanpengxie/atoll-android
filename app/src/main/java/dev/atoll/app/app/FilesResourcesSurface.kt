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
        TabRow(if (resourceTab) 1 else 0) {
            Tab(!resourceTab, onFiles, text = { Text("Files") })
            Tab(resourceTab, onResources, text = { Text("KV") })
        }
        if (resourceTab) {
            val vm = remember(services.wire, services.boot, channel) { ResourcesViewModel(services, channel) }
            SideEffect { vm.context(writable) }
            DisposableEffect(vm) { onDispose(vm::close) }
            ResourcesScreen(vm, Modifier.weight(1f), onClose)
        } else if (files != null) FilesScreen(files, Modifier.weight(1f), onClose, onAttach)
    }
}

package dev.atoll.app.app

import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import dev.atoll.app.files.FilePaths
import dev.atoll.app.files.FileReference
import dev.atoll.app.ui.screens.*

internal fun attachmentReference(channelId: String, value: MessageAttachmentViewState): FileReference? {
    val resource = value.resourceId.ifBlank { value.uri }
    if (resource.isBlank()) return null
    val reference = FilePaths.fromLink(channelId, resource)
        ?: FileReference(channelId, resource, resource.substringAfterLast('/'))
    return reference.copy(name = value.name.ifBlank { reference.name },
        mediaType = value.mime.ifBlank { "application/octet-stream" }, size = value.size ?: -1)
}

/** Use the same public Downloads / document-picker path as FilesScreen. */
@Composable
internal fun rememberAttachmentHandler(channelId: String, onPreview: (FileReference) -> Unit,
    onDownload: (FileReference, Uri?) -> Unit, downloading: () -> Boolean): MessageAttachmentHandler {
    val preview by rememberUpdatedState(onPreview)
    val download by rememberUpdatedState(onDownload)
    val busy by rememberUpdatedState(downloading)
    var saveReference by remember(channelId) { mutableStateOf<FileReference?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val reference = saveReference
        saveReference = null
        if (uri != null && reference != null && !busy()) download(reference, uri)
    }
    return { value, action ->
        attachmentReference(channelId, value)?.let { reference ->
            when (action) {
                MessageAttachmentAction.Preview -> preview(reference)
                MessageAttachmentAction.Download -> if (!busy()) {
                    if (Build.VERSION.SDK_INT >= 29) download(reference, null)
                    else { saveReference = reference; save.launch(reference.name) }
                }
            }
        }
    }
}

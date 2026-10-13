package dev.atoll.app.app

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import dev.atoll.app.files.FileHistory
import dev.atoll.app.files.FilesViewModel
import dev.atoll.app.net.AndroidKeyValueStore

/** Feature models survive channel switches and rotation, and end with the checked server world. */
class SessionFeatures(private val context: Context, val services: WorkspaceServices) : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
    val fileHistory = FileHistory(AndroidKeyValueStore(context.getSharedPreferences("files", Context.MODE_PRIVATE)),
        services.server, services.principalId, services.boot)
    fun files(channel: String): FilesViewModel = ViewModelProvider(this, object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = FilesViewModel(services.wire, services.httpClient,
            services.server, channel, context.cacheDir, fileHistory) as T
    })["files:$channel", FilesViewModel::class.java]
    fun clear() = viewModelStore.clear()
}

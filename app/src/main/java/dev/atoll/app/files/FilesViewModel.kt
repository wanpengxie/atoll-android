package dev.atoll.app.files

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.atoll.app.core.WireClient
import dev.atoll.app.net.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException

/** Dependencies are supplied by AppGraph; this ViewModel owns one channel's file surface. */
class FilesViewModel(wire: WireClient, http: OkHttpClient, baseUrl: String, val channelId: String, cacheDir: File, val history: FileHistory, onUnauthorized: (HttpFailure) -> Unit = {}) : ViewModel() {
    val repository = FileRepository(channelId, wire, http, baseUrl, cacheDir, onUnauthorized)
    private val mutableState = MutableStateFlow(FilesState())
    val state: StateFlow<FilesState> = mutableState.asStateFlow()
    val recent: StateFlow<List<FileReference>> = history.recent
    private var listingJob: Job? = null
    private var previewJob: Job? = null
    private val mutations = Mutex()
    private val scrollPositions = mutableMapOf<String, Pair<Int, Int>>()
    init { initialize() }
    fun initialize() {
        listingJob?.cancel()
        listingJob = viewModelScope.launch {
            mutableState.update { it.copy(busy = true, error = "") }
            try {
                val devices = repository.initialize()
                val device = devices.firstOrNull { it.default } ?: devices.firstOrNull()
                mutableState.update { it.copy(devices = devices, deviceId = device?.id.orEmpty(), channelName = repository.channelName) }
                if (device != null) loadPage(device, "", false)
                else mutableState.update { it.copy(busy = false, entries = emptyList()) }
                history.stack(channelId).lastOrNull()?.let { open(it, push = false) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutableState.update { it.copy(busy = false, error = e.message.orEmpty()) } }
        }
    }
    fun navigate(directory: String, deviceId: String = mutableState.value.deviceId) {
        val device = mutableState.value.devices.firstOrNull { it.id == deviceId } ?: return
        val path = FilePaths.normalize(directory)
        listingJob?.cancel()
        mutableState.update { it.copy(deviceId = deviceId, directory = path, entries = emptyList(), next = "", busy = true, error = "") }
        listingJob = viewModelScope.launch { safelyList { loadPage(device, path, false) } }
    }
    fun refresh() = navigate(mutableState.value.directory)
    fun loadMore() {
        val current = mutableState.value
        if (current.busy || current.next.isEmpty()) return
        val device = current.devices.firstOrNull { it.id == current.deviceId } ?: return
        listingJob = viewModelScope.launch { safelyList { loadPage(device, current.directory, true) } }
    }
    private suspend fun safelyList(work: suspend () -> Unit) {
        mutableState.update { it.copy(busy = true, error = "") }
        try { work() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { mutableState.update { it.copy(busy = false, error = e.message.orEmpty()) } }
    }
    private suspend fun loadPage(device: FileDevice, directory: String, append: Boolean) {
        val page = repository.list(device, directory, if (append) mutableState.value.next else "")
        val entries = withContext(Dispatchers.Default) {
            val combined = if (append) (mutableState.value.entries + page.entries).distinctBy { it.reference.resourceId } else page.entries
            sortedFiles(combined, mutableState.value.sort, mutableState.value.descending)
        }
        mutableState.update { it.copy(entries = entries, next = page.next, busy = false) }
    }
    fun sort(key: FileSort) {
        viewModelScope.launch(Dispatchers.Default) {
            mutableState.update { s ->
                val descending = s.sort == key && !s.descending
                s.copy(sort = key, descending = descending, entries = sortedFiles(s.entries, key, descending))
            }
        }
    }
    fun mkdir(name: String) = mutate { device, snapshot -> repository.mkdir(device, snapshot.directory, name) }
    fun delete(reference: FileReference) = mutate { _, _ -> repository.delete(reference) }
    private fun mutate(work: suspend (FileDevice, FilesState) -> Unit) {
        val captured = mutableState.value
        val device = captured.devices.firstOrNull { it.id == captured.deviceId } ?: return
        viewModelScope.launch {
            try {
                mutations.withLock { work(device, captured) }
                if (mutableState.value.deviceId == captured.deviceId && mutableState.value.directory == captured.directory) refresh()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutableState.update { it.copy(error = e.message.orEmpty()) } }
        }
    }
    fun upload(context: Context, uri: Uri, onUploaded: (FileReference) -> Unit = {}) {
        val app = context.applicationContext
        val captured = mutableState.value
        val device = captured.devices.firstOrNull { it.id == captured.deviceId } ?: return
        viewModelScope.launch {
            mutableState.update { it.copy(upload = TransferProgress(0, -1), error = "") }
            try {
                val metadata = withContext(Dispatchers.IO) {
                    app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) to (if (c.isNull(1)) -1L else c.getLong(1)) else null
                    } ?: ("upload" to -1L)
                }
                val result = mutations.withLock {
                    repository.upload(device, captured.directory, metadata.first, captured.entries.map { it.reference.name }, { app.contentResolver.openInputStream(uri) ?: throw IOException("无法读取上传文件") }, metadata.second, app.contentResolver.getType(uri) ?: "application/octet-stream") { progress -> mutableState.update { it.copy(upload = progress) } }
                }
                onUploaded(result)
                if (mutableState.value.deviceId == captured.deviceId && mutableState.value.directory == captured.directory) refresh()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutableState.update { it.copy(error = e.message.orEmpty()) } }
            finally { mutableState.update { it.copy(upload = null) } }
        }
    }
    fun download(context: Context, reference: FileReference, destination: Uri? = null) {
        val app = context.applicationContext
        viewModelScope.launch {
            mutableState.update { it.copy(download = TransferProgress(0, reference.size), downloadName = reference.name, notice = "", error = "") }
            try {
                PublicDownloads.download(app, repository, reference, destination) { progress -> mutableState.update { it.copy(download = progress) } }
                mutableState.update { it.copy(notice = "已保存到下载目录：${reference.name}") }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutableState.update { it.copy(error = e.message.orEmpty()) } }
            finally { mutableState.update { it.copy(download = null) } }
        }
    }
    fun openLink(href: String): Boolean {
        val ref = FilePaths.fromLink(channelId, href) ?: return false
        open(ref); return true
    }
    fun open(reference: FileReference, push: Boolean = true) {
        previewJob?.cancel()
        history.opened(reference, push)
        mutableState.update { it.copy(selected = reference, preview = null, previewLoading = true, previewError = "", canGoBack = history.stack(channelId).size > 1) }
        previewJob = viewModelScope.launch {
            try {
                val loaded = repository.preview(reference)
                mutableState.update { it.copy(preview = loaded, previewLoading = false) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutableState.update { it.copy(previewLoading = false, previewError = e.message.orEmpty()) } }
        }
    }
    fun backPreview() { history.back(channelId)?.let { open(it, push = false) } }
    fun closePreview() {
        previewJob?.cancel(); history.close(channelId)
        mutableState.update { it.copy(selected = null, preview = null, previewLoading = false, previewError = "", canGoBack = false) }
    }
    fun rememberScroll(deviceId: String, directory: String, index: Int, offset: Int) { scrollPositions["$deviceId:$directory"] = index to offset }
    fun scrollPosition(): Pair<Int, Int> = scrollPositions["${state.value.deviceId}:${state.value.directory}"] ?: (0 to 0)
}
data class FilesState(
    val channelName: String = "文件", val devices: List<FileDevice> = emptyList(), val deviceId: String = "", val directory: String = "",
    val entries: List<FileEntry> = emptyList(), val next: String = "", val busy: Boolean = true, val error: String = "", val notice: String = "",
    val sort: FileSort = FileSort.NAME, val descending: Boolean = false,
    val selected: FileReference? = null, val preview: LoadedPreview? = null, val previewLoading: Boolean = false, val previewError: String = "", val canGoBack: Boolean = false,
    val upload: TransferProgress? = null, val download: TransferProgress? = null, val downloadName: String = "",
)

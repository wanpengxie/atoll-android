package dev.atoll.app.files

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import dev.atoll.app.ui.theme.AtollTheme
import dev.atoll.app.core.WireState
import dev.atoll.app.net.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient
import java.io.File

/** Only registered by the debug manifest. Reads a short-lived app-private token, never an Intent secret. */
class FilesDemoActivity : ComponentActivity() {
    private var wire: Wire? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) { finish(); return }
        enableEdgeToEdge()
        var model by mutableStateOf<FilesViewModel?>(null)
        var error by mutableStateOf("")
        setContent { AtollTheme { Surface(Modifier.fillMaxSize()) {
            val vm = model
            if (vm != null) FilesScreen(vm, Modifier.systemBarsPadding(), onClose = { finish() })
            else Column(Modifier.systemBarsPadding().padding(24.dp)) { Text(if (error.isEmpty()) "正在连接文件工作区…" else error) }
        } } }
        lifecycleScope.launch {
            try {
                val token = withContext(Dispatchers.IO) { File(filesDir, "demo-token").let { file -> file.readText().trim().also { file.delete() } } }
                val base = intent.getStringExtra("server") ?: "https://tardis.tail6bc2a1.ts.net"
                val channel = intent.getStringExtra("channel") ?: "ba676224-ff3c-4975-904b-6473e4dba788"
                val http = OkHttpClient.Builder().addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer $token").build()) }.build()
                val connection = Wire(base, http, Build.MODEL, focus = { channel })
                wire = connection
                launch(start = CoroutineStart.UNDISPATCHED) { connection.frames.collect {} }
                connection.start()
                val attached = withTimeout(15_000) { connection.state.filterIsInstance<WireState.Attached>().first() }
                val history = FileHistory(AndroidKeyValueStore(getSharedPreferences("files-demo", MODE_PRIVATE)), base, "automation", attached.receipt["boot"]?.toString().orEmpty())
                val factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = FilesViewModel(connection, http, base, channel, File(cacheDir, "file-previews"), history) as T
                }
                val vm = ViewModelProvider(this@FilesDemoActivity, factory)[FilesViewModel::class.java]
                model = vm
                val directory = intent.getStringExtra("directory")
                if (directory != null) { vm.state.first { !it.busy }; vm.closePreview(); vm.navigate(directory) }
                val ref = intent.getStringExtra("file")?.let { FilePaths.fromLink(channel, it) }
                if (ref != null) {
                    vm.state.first { !it.busy }
                    if (intent.getStringExtra("action") == "download") vm.download(this@FilesDemoActivity, ref) else vm.open(ref)
                }
            } catch (e: TimeoutCancellationException) { error = "连接超时，请检查服务器地址或网络。" }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "演示无法连接" }
        }
    }
    override fun onDestroy() { wire?.close(); super.onDestroy() }
}

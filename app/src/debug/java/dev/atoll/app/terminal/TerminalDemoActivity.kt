package dev.atoll.app.terminal

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import dev.atoll.app.net.*
import dev.atoll.app.ui.theme.AtollTheme
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import java.io.File

/** Test-only entry point. A VM owns the socket across rotation; the token is consumed once. */
class TerminalDemoActivity : ComponentActivity() {
    private lateinit var model: DemoTerminalModel
    // Debug-only commands allow deterministic resize/navigation checks without
    // driving shell commands through adb's device-shell quoting or UI focus.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        when (intent.getStringExtra("action")) {
            "command" -> model.handle?.write((intent.getStringExtra("command").orEmpty() + "\r").toByteArray())
            "hide" -> model.visible = false
            "show" -> model.visible = true
            "keyboard" -> terminalSurface(window.decorView)?.showKeyboard()
        }
    }
    private fun terminalSurface(view: View): RemoteTerminalSurface? {
        if (view is RemoteTerminalSurface) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) terminalSurface(view.getChildAt(i))?.let { return it }
        return null
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) { finish(); return }
        enableEdgeToEdge()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(clazz: Class<T>): T = DemoTerminalModel(application, intent.getStringExtra("server") ?: "https://tardis.tail6bc2a1.ts.net", intent.getStringExtra("channel") ?: "ba676224-ff3c-4975-904b-6473e4dba788") as T
        }
        val vm = ViewModelProvider(this, factory)[DemoTerminalModel::class.java]
        model = vm
        setContent { AtollTheme { Surface(Modifier.fillMaxSize()) {
            Column(Modifier.systemBarsPadding().imePadding()) {
                TextButton(onClick = { vm.visible = !vm.visible }) { Text(if (vm.visible) "切走频道" else "返回终端") }
                val client = vm.client
                if (client == null) Text(vm.error.ifEmpty { "正在连接终端…" }, Modifier.padding(16.dp))
                else TerminalScreen(client, vm.channel, vm.devices, vm.preferences, visible = vm.visible, onAttached = { stream ->
                    vm.handle = stream
                    val command = intent.getStringExtra("command")
                    if (!vm.commandSent && !command.isNullOrEmpty()) { vm.commandSent = true; stream.write((command + "\r").toByteArray()) }
                })
            }
        } } }
    }
}
private class DemoTerminalModel(app: Application, base: String, val channel: String) : AndroidViewModel(app) {
    var client by mutableStateOf<PtyClient?>(null); private set
    var devices by mutableStateOf<List<TerminalDevice>>(emptyList()); private set
    var error by mutableStateOf(""); private set
    var visible by mutableStateOf(true)
    var commandSent = false
    var handle: PtyHandle? = null
    val preferences = TerminalPreferences(AndroidKeyValueStore(app.getSharedPreferences("terminal-demo", 0)))
    init { viewModelScope.launch {
        try {
            val token = withContext(Dispatchers.IO) { File(app.filesDir, "demo-token").let { file -> file.readText().trim().also { file.delete() } } }
            val http = OkHttpClient.Builder().addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer $token").build()) }.build()
            val reply = ObsClient(base, http).channelDevices(channel)
            devices = dev.atoll.app.files.projectDevices(reply).map { TerminalDevice(it.id, it.name, it.online) }
            client = PtyClient(base, http)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "无法连接终端" }
    } }
    override fun onCleared() { client?.close() }
}

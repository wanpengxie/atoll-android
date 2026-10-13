package dev.atoll.app.compose

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModel
import dev.atoll.app.files.*
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import dev.atoll.app.core.*
import dev.atoll.app.net.*
import dev.atoll.app.ui.components.MessageFrame
import dev.atoll.app.ui.screens.MessageViewState
import dev.atoll.app.ui.theme.AtollTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import java.io.File

/** Registered ONLY by debug manifest. Credentials come from app-private storage. */
class ComposerDemoActivity : ComponentActivity() {
    private var wire: DemoConnection? = null
    private var composer: ComposerViewModel? = null
    private var database: ComposerDatabase? = null
    private var files: FilesViewModel? = null
    private val messages = MutableStateFlow<List<Envelope>>(emptyList())
    private val terminalRows = MutableStateFlow<Map<String, Envelope>>(emptyMap())
    private val notice = MutableStateFlow("连接真实节点…")
    private val paused = MutableStateFlow(false)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) { finish(); return }
        val server = intent.getStringExtra("server") ?: "https://tardis.tail6bc2a1.ts.net"
        val channelId = intent.getStringExtra("channel") ?: "ba676224-ff3c-4975-904b-6473e4dba788"
        val tokenFile = File(filesDir, "composer-demo-token")
        if (!tokenFile.exists()) { setContent { AtollTheme { Text("缺少测试凭据文件", Modifier.padding(24.dp)) } }; return }
        lifecycleScope.launch {
            try {
                val token = withContext(Dispatchers.IO) { tokenFile.readText().trim() }
                val client = OkHttpClient.Builder().addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer $token").build()) }.build()
                val transport = DemoConnection(server, client, channelId)
                wire = transport
                                database = ComposerDatabase.open(this@ComposerDemoActivity)
                val repository = RoomOutboxRepository("$server:composer-demo:root", database!!)
                val viewModel = ComposerViewModel("$server:composer-demo:root", transport, repository, ChannelAttachmentUploader { id -> dev.atoll.app.files.FileRepository(id, transport, client, server, cacheDir) })
                composer = viewModel
                // Subscribe before start so even immediate terminals land in the awaiter's cache.
                launch(start = CoroutineStart.UNDISPATCHED) { transport.frames.collect { frame -> if (frame is DownFrame.Feed && frame.row.channelId == channelId) {
                    val envelope = frame.row.envelope
                    if (envelope.isTerminal && envelope.type == "agent.ask") (envelope.body["usage"] as? JsonObject)?.let { viewModel.updateUsage(channelId, envelope.senderId, it) }
                    if (envelope.isTerminal) terminalRows.value = terminalRows.value + (envelope.parentId to envelope)
                    if (envelope.kind == "request") viewModel.reconcileLanded(channelId, setOf(envelope.id))
                    if (envelope.type == "agent.ask" && (envelope.kind == "request" || envelope.isTerminal)) messages.value = (messages.value + envelope).takeLast(30)
                } } }
                launch {
                    val recovered = hashSetOf<String>()
                    viewModel.submissions.collect { rows ->
                        if (rows.any { it.state == SendState.ACCEPTED && recovered.add(it.messageId) }) {
                            try {
                                val page = transport.history(buildJsonObject { put("channel_id", channelId); put("before_seq", 9007199254740991L); put("limit", 100); put("byte_limit", 1048576); put("purpose", "hydrate"); put("priority", "background") })
                                viewModel.reconcileLanded(channelId, page.rows.map { it.envelope.id }.toSet())
                                page.rows.map { it.envelope }.filter { it.type == "agent.ask" && (it.senderId == "agent:codex:1790181618736" || "agent:codex:1790181618736" in it.audience) }.forEach { envelope ->
                                    if (envelope.kind == "request" || envelope.isTerminal) messages.value = (messages.value.filter { it.id != envelope.id } + envelope).takeLast(30)
                                }
                            } catch (_: Exception) { }
                        }
                    }
                }
                setContent { AtollTheme { DemoScreen(viewModel, transport) } }
                transport.start()
                val attach = transport.state.filterIsInstance<WireState.Attached>().first()
                val selfId = attach.receipt.array("memberships").mapNotNull { it as? JsonObject }.find { it.text("channel_id") == channelId }?.text("actor_id").orEmpty()
                files = ViewModelProvider(this@ComposerDemoActivity, object : ViewModelProvider.Factory {
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        @Suppress("UNCHECKED_CAST")
                        return FilesViewModel(transport, client, server, channelId, cacheDir, FileHistory(MemoryKeyValueStore(), server, "root", attach.receipt.text("boot"))) as T
                    }
                })[FilesViewModel::class.java]
                val roster = ObsClient(server, client).channelActors(channelId).array("items").mapNotNull { element ->
                    val item = element as? JsonObject ?: return@mapNotNull null
                    val actor = item["declared"] as? JsonObject ?: item
                    val id = actor.text("id").ifEmpty { actor.text("actor_id") }
                    if (id.isEmpty() || actor.text("name") == "claude") null else ComposerActor(id, actor.text("name").ifEmpty { id }, actor.text("kind"), actor.text("body"))
                }
                viewModel.setChannel(channelId, selfId, roster)
                roster.find { it.name == "codex" }?.let { viewModel.selectAgent(it.id) }
                notice.value = "# c0.dev.web · 已连接真实节点"
            } catch (error: Exception) { notice.value = error.message ?: "连接失败" }
        }
    }
    @Composable private fun DemoScreen(viewModel: ComposerViewModel, transport: DemoConnection) {
        val feed by messages.collectAsState()
        val banner by notice.collectAsState()
        val wireState by transport.state.collectAsState()
        val offline = wireState is WireState.Disconnected || wireState == WireState.SignedOut
        var picker by remember { mutableStateOf(false) }
        var preview by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(banner, Modifier.weight(1f).padding(top = 12.dp))
                TextButton(onClick = { if (!offline) transport.pause() else transport.resume() }) { Text(if (offline) "恢复连接" else "测试断网") }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                TextButton(onClick = { transport.dropNextSubmitReceipt(); viewModel.updateText("Android native composer smoke test: reply only 'android composer ok'. Do not change files."); viewModel.send() }, enabled = !offline) { Text("发送并丢回执") }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(feed, key = { it.id }) { envelope ->
                    val text = envelope.body.text("text").ifEmpty { envelope.body.text("detail") }
                    MessageFrame(MessageViewState(envelope.id, if (envelope.senderKind == "human") "root" else "codex", text, "", agent = envelope.senderKind == "agent", self = envelope.senderKind == "human"))
                }
            }
            AtollComposer(viewModel, onPickChannelFiles = { files?.refresh(); picker = true }, onPreviewAttachment = { ref -> files?.open(ref); preview = true })
        }
        files?.let { fileModel ->
            if (picker) FilePicker(fileModel, onPick = { ref -> viewModel.addAttachments(listOf(ref)); picker = false }, onDismiss = { picker = false })
            if (preview) {
                val state by fileModel.state.collectAsState()
                androidx.compose.ui.window.Dialog(onDismissRequest = { preview = false }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
                    FilePreviewPanel(state, fileModel::backPreview, { preview = false; fileModel.closePreview() }, { ref -> fileModel.download(this@ComposerDemoActivity, ref) }, fileModel::openLink)
                }
            }
        }
    }
    override fun onDestroy() { composer?.close(); wire?.close(); database?.close(); super.onDestroy() }
}

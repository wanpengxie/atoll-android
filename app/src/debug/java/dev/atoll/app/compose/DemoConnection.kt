package dev.atoll.app.compose

import dev.atoll.app.core.*
import dev.atoll.app.net.Wire
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.JsonObject
import dev.atoll.app.core.string
import okhttp3.OkHttpClient

/** Debug-only host: closes the real socket, then constructs a fresh transport on resume. */
internal class DemoConnection(private val server: String, private val client: OkHttpClient, private val channelId: String) : WireClient, AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var current: Wire? = null
    private var epoch = 0L
    private var loseNextReceipt = false
    private var mutedRequest = ""
    fun dropNextSubmitReceipt() { loseNextReceipt = true }
    private val mutable = MutableStateFlow<WireState>(WireState.Connecting)
    override val state = mutable.asStateFlow()
    private val events = MutableSharedFlow<DownFrame>(extraBufferCapacity = 256)
    override val frames = events.asSharedFlow()
    fun start() = resume()
    fun resume() {
        val transport = Wire(server, client, "Android Composer Demo", focus = { channelId })
        current = transport
        val generation = ++epoch
        scope.launch(start = CoroutineStart.UNDISPATCHED) { transport.state.collect { if (current === transport) mutable.value = if (it is WireState.Attached) it.copy(generation = generation) else it } }
        scope.launch(start = CoroutineStart.UNDISPATCHED) { transport.frames.collect { if (current === transport && !(it is DownFrame.Feed && mutedRequest.isNotEmpty() && (it.row.envelope.id == mutedRequest || it.row.envelope.parentId == mutedRequest))) events.emit(it) } }
        transport.start()
    }
    fun pause() { val transport = current; current = null; transport?.close(); mutable.value = WireState.Disconnected("测试断网：真实连接已关闭", 0) }
    override suspend fun request(type: String, payload: JsonObject): JsonObject {
        val drop = type == Up.SUBMIT && loseNextReceipt && payload["msg_type"].string() == "agent.ask"
        if (drop) { loseNextReceipt = false; mutedRequest = payload["id"].string() }
        val result = (current ?: throw WireException(type, "closed", "测试连接已关闭")).request(type, payload)
        if (drop) { pause(); throw WireException(type, "closed", "测试：节点已接受，但回执丢失") }
        if (type == Up.SUBMIT && payload["id"].string() == mutedRequest) mutedRequest = ""
        return result
    }
    override suspend fun history(payload: JsonObject) = (current ?: throw WireException(Up.HISTORY_BEFORE, "closed", "测试连接已关闭")).history(payload)
    override fun close() { current?.close(); current = null; scope.cancel() }
}

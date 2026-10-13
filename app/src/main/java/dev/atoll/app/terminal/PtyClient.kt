package dev.atoll.app.terminal

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.ByteString
import java.util.ArrayDeque

/**
 * App-owned multiplexed transport. The actor owns sockets, sessions, streams
 * and all retry/input state. [attach]'s data callback is installed before open
 * and must enqueue/render quickly; no first replay bytes are lost to a late subscriber.
 */
class PtyClient(
    baseUrl: String,
    private val http: OkHttpClient,
    private val reconnectDelays: List<Long> = listOf(400, 800, 1600, 3000, 5000),
    private val idleDelayMs: Long = 250,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AutoCloseable {
    private val url = baseUrl.toHttpUrl().resolve("/pty")!!
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val commands = Channel<() -> Unit>(Channel.UNLIMITED)
    private val streams = linkedMapOf<Long, Stream>()
    private val sessions = mutableMapOf<Pair<String, String>, String>()
    private var socket: WebSocket? = null
    private var open = false
    private var closing = false
    private var stopped = false
    private var epoch = 0L
    private var nextId = 1L
    private var attempt = 0
    private var reconnect: Job? = null
    private var idle: Job? = null
    private class Stream(val handle: PtyHandle, val channelId: String, val deviceId: String, var cols: Int, var rows: Int, val onData: (ByteArray) -> Unit, val onReady: () -> Unit) {
        var session = ""
        var ready = false
        var sentEpoch = -1L
        var sentCols = 0
        var sentRows = 0
        val pending = ArrayDeque<ByteArray>()
        var pendingBytes = 0
        val key get() = channelId to deviceId
    }
    init { scope.launch { try { for (command in commands) command() } finally { scope.cancel() } } }

    suspend fun attach(channelId: String, deviceId: String, cols: Int = 80, rows: Int = 24, onReady: () -> Unit = {}, onData: (ByteArray) -> Unit): PtyHandle {
        require(channelId.isNotBlank() && deviceId.isNotBlank())
        val result = CompletableDeferred<PtyHandle>(currentCoroutineContext()[Job])
        var created: PtyHandle? = null // read and written only inside actor commands
        check(enqueue {
            if (!result.isActive) return@enqueue
            if (stopped) { result.completeExceptionally(IllegalStateException("PTY client closed")); return@enqueue }
            val handle = PtyHandle(nextId++, this)
            created = handle
            val stream = Stream(handle, channelId, deviceId, cols.coerceIn(4, 65535), rows.coerceIn(4, 65535), onData, onReady)
            stream.session = sessions[stream.key].orEmpty()
            streams[handle.id] = stream
            idle?.cancel(); idle = null
            if (!result.complete(handle)) { dropStream(handle.id, false); return@enqueue }
            ensure()
            if (open) sendOpen(stream)
        }) { "PTY client closed" }
        return try { result.await() }
        catch (e: CancellationException) {
            // A completed deferred can still lose to cancellation before the
            // caller resumes. Remove that stream as well as pre-completion ones.
            enqueue { created?.let { dropStream(it.id, false) } }
            throw e
        }
    }

    internal fun write(id: Long, bytes: ByteArray) {
        val copy = bytes.copyOf()
        enqueue {
            val stream = streams[id] ?: return@enqueue
            if (!open || !stream.ready) {
                if (stream.pendingBytes + copy.size <= MAX_PENDING_INPUT) { stream.pending.add(copy); stream.pendingBytes += copy.size }
            } else if (socket?.send(PtyFrames.binary(id, copy)) != true) {
                // This send was refused, so retain it for the next ready.
                if (stream.pendingBytes + copy.size <= MAX_PENDING_INPUT) { stream.pending.add(copy); stream.pendingBytes += copy.size }
                lost("connection closed")
            }
        }
    }
    internal fun resize(id: Long, cols: Int, rows: Int) { enqueue {
        val stream = streams[id] ?: return@enqueue
        stream.cols = cols.coerceIn(4, 65535); stream.rows = rows.coerceIn(4, 65535)
        if (open && stream.ready) socket?.send(PtyFrames.resize(id, stream.cols, stream.rows))
    } }
    internal fun drop(id: Long, close: Boolean) { enqueue { dropStream(id, close) } }
    private fun dropStream(id: Long, close: Boolean) {
        val stream = streams.remove(id) ?: return
        if (close) sessions.remove(stream.key)
        if (open) socket?.send(PtyFrames.end(id, close))
        stream.handle.publish(PtyStatus.Detached)
        idleShutdown()
    }
    private fun enqueue(command: () -> Unit) = commands.trySend(command).isSuccess
    private fun ensure() {
        if (stopped || socket != null || reconnect != null || streams.isEmpty()) return
        epoch++
        socket = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) { enqueue {
                if (socket !== ws || stopped || closing) return@enqueue
                open = true
                streams.values.forEach { stream ->
                    stream.ready = false; stream.session = sessions[stream.key].orEmpty()
                    stream.handle.publish(if (attempt == 0) PtyStatus.Connecting else PtyStatus.Reconnecting(attempt, 0))
                    sendOpen(stream)
                }
            } }
            override fun onMessage(ws: WebSocket, text: String) { enqueue { if (socket === ws && !stopped && !closing) PtyFrames.decode(text)?.let(::receive) } }
            override fun onMessage(ws: WebSocket, bytes: ByteString) { enqueue {
                if (socket !== ws || stopped || closing) return@enqueue
                val decoded = PtyFrames.decode(bytes) ?: return@enqueue
                streams[decoded.id]?.onData?.invoke(decoded.bytes)
            } }
            override fun onClosing(ws: WebSocket, code: Int, reason: String) { ws.close(code, reason) }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) { enqueue { if (socket === ws && !stopped) lost(reason) } }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) { enqueue { if (socket === ws && !stopped) lost(t.message.orEmpty()) } }
        })
    }
    private fun sendOpen(stream: Stream) {
        if (!open || closing || stream.sentEpoch == epoch) return
        stream.sentEpoch = epoch
        stream.sentCols = stream.cols; stream.sentRows = stream.rows
        if (socket?.send(PtyFrames.open(stream.handle.id, stream.channelId, stream.deviceId, stream.session, stream.cols, stream.rows)) != true) lost("connection closed")
    }
    private fun receive(control: PtyControl) {
        val stream = streams[control.id] ?: return
        when (control) {
            is PtyControl.Ready -> {
                attempt = 0; stream.ready = true; stream.session = control.session
                stream.onReady()
                if (control.session.isNotEmpty()) sessions[stream.key] = control.session else sessions.remove(stream.key)
                if (stream.cols != stream.sentCols || stream.rows != stream.sentRows) socket?.send(PtyFrames.resize(control.id, stream.cols, stream.rows))
                while (stream.pending.isNotEmpty()) {
                    val bytes = stream.pending.first()
                    if (socket?.send(PtyFrames.binary(control.id, bytes)) != true) { lost("connection closed"); return }
                    stream.pending.removeFirst(); stream.pendingBytes -= bytes.size
                }
                stream.handle.publish(PtyStatus.Ready(control.session))
            }
            is PtyControl.Exit -> {
                sessions.remove(stream.key); streams.remove(control.id)
                stream.handle.publish(PtyStatus.Ended(control.reason)); idleShutdown()
            }
            is PtyControl.Error -> {
                if (stream.session.isNotEmpty()) {
                    sessions.remove(stream.key); stream.session = ""; stream.sentEpoch = -1
                    sendOpen(stream)
                } else {
                    streams.remove(control.id)
                    stream.handle.publish(PtyStatus.Ended(control.detail)); idleShutdown()
                }
            }
        }
    }
    private fun lost(reason: String) {
        socket?.cancel(); socket = null; open = false; closing = false
        streams.values.forEach { it.ready = false }
        if (streams.isEmpty()) return
        if (attempt >= reconnectDelays.size) {
            streams.values.forEach { it.handle.publish(PtyStatus.Ended("连不上终端——设备可能不在线")) }
            return
        }
        val wait = reconnectDelays[attempt++]
        streams.values.forEach { it.handle.publish(PtyStatus.Reconnecting(attempt, wait)) }
        reconnect?.cancel()
        reconnect = scope.launch { delay(wait); enqueue { reconnect = null; ensure() } }
    }
    private fun idleShutdown() {
        if (streams.isNotEmpty()) return
        idle?.cancel()
        idle = scope.launch { delay(idleDelayMs); enqueue {
            idle = null
            if (streams.isEmpty()) {
                reconnect?.cancel(); reconnect = null; attempt = 0
                // Retain a closing socket until its callback: a rapid reattach
                // must not create a second simultaneous /pty connection.
                closing = socket != null; open = false
                socket?.close(1000, "idle")
            }
        } }
    }
    override fun close() { enqueue {
        stopped = true; reconnect?.cancel(); idle?.cancel()
        streams.values.forEach { it.handle.publish(PtyStatus.Detached) }
        streams.clear(); sessions.clear()
        socket?.close(1000, "client closed"); socket = null; open = false
        commands.close()
    } }
    companion object { const val MAX_PENDING_INPUT = 64 * 1024 }
}
class PtyHandle internal constructor(val id: Long, private val client: PtyClient) {
    private val mutableStatus = MutableStateFlow<PtyStatus>(PtyStatus.Connecting)
    val status: StateFlow<PtyStatus> = mutableStatus.asStateFlow()
    internal fun publish(status: PtyStatus) { mutableStatus.value = status }
    fun write(bytes: ByteArray) = client.write(id, bytes)
    fun resize(cols: Int, rows: Int) = client.resize(id, cols, rows)
    fun detach() = client.drop(id, close = false)
    fun close() = client.drop(id, close = true)
}

package dev.atoll.app.net

import dev.atoll.app.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * One socket, one writer. OkHttp callbacks enqueue work; only this coroutine
 * owns connection state, refs and pending pages. No network or DB work on Main.
 * [label] is supplied by the app (the device model); transport doesn't inspect Android.
 */
class Wire(
    baseUrl: String,
    private val client: OkHttpClient,
    private val label: String,
    private val focus: () -> String = { "" },
    private val since: () -> JsonObject = { Envelope.EMPTY },
    private val receiptTimeoutMs: Long = 30_000,
    private val reconnectBaseMs: Long = 500,
    private val maxReconnectDelayMs: Long = 5_000,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : WireClient, AutoCloseable {
    private val url = baseUrl.toHttpUrl().resolve("/ws")!!
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val mutableState = MutableStateFlow<WireState>(WireState.Connecting)
    override val state: StateFlow<WireState> = mutableState.asStateFlow()
    private val mutableFrames = MutableSharedFlow<DownFrame>(extraBufferCapacity = 256)
    override val frames: SharedFlow<DownFrame> = mutableFrames.asSharedFlow()
    private var socket: WebSocket? = null
    private var generation = 0L
    private var counter = 0L
    private var attachRef = ""
    private var stopped = false
    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null
    private val pending = mutableMapOf<String, Pending>()
    private class Pending(
        val type: String,
        val receipt: CompletableDeferred<JsonObject>? = null,
        val page: CompletableDeferred<HistoryPage>? = null,
        val rows: MutableList<Row> = mutableListOf(),
        var timer: Job? = null,
    ) {
        fun fail(error: Exception) { timer?.cancel(); receipt?.completeExceptionally(error); page?.completeExceptionally(error) }
    }

    init {
        scope.launch {
            try { for (command in commands) command() }
            finally { scope.cancel() }
        }
        enqueue { connect() }
    }

    override suspend fun request(type: String, payload: JsonObject): JsonObject {
        val result = CompletableDeferred<JsonObject>()
        var ref = ""
        if (!enqueue { ref = transmit(type, payload, Pending(type, receipt = result)) }) unavailable(type)
        try { return result.await() }
        finally { enqueue { if (!result.isCompleted) remove(ref)?.receipt?.cancel() } }
    }

    override suspend fun history(payload: JsonObject): HistoryPage {
        val result = CompletableDeferred<HistoryPage>()
        var ref = ""
        if (!enqueue {
            val p = JsonObject(payload + ("generation" to (payload["generation"] ?: JsonPrimitive(generation))))
            ref = transmit(Up.HISTORY_BEFORE, p, Pending(Up.HISTORY_BEFORE, page = result))
        }) unavailable(Up.HISTORY_BEFORE)
        try { return result.await() }
        finally {
            enqueue {
                if (!result.isCompleted) {
                    val removed = remove(ref)
                    removed?.page?.cancel()
                    if (removed != null && mutableState.value is WireState.Attached) {
                        // Free the server's page task as well as our accumulator.
                        val cancel = buildJsonObject {
                            put("channel_id", payload["channel_id"] ?: JsonPrimitive(""))
                            put("target_ref", ref); put("generation", generation)
                        }
                        val ignored = CompletableDeferred<JsonObject>()
                        transmit(Up.HISTORY_CANCEL, cancel, Pending(Up.HISTORY_CANCEL, receipt = ignored))
                    }
                }
            }
        }
    }

    /** Foreground recovery always re-attaches to obtain a fresh history/live seam. */
    fun reconnectNow() { enqueue {
        if (!stopped) {
            reconnectAttempt = 0
            reconnectJob?.cancel(); reconnectJob = null
            socket?.cancel(); socket = null
            rejectPending("closed", "connection refreshed")
            connect()
        }
    } }

    /** Sign-out terminates pending work and prevents any automatic reconnect. */
    override fun close() { enqueue {
        stopped = true
        reconnectJob?.cancel(); reconnectJob = null
        socket?.close(1000, "signed out"); socket = null
        rejectPending("closed", "wire closed by client")
        mutableState.value = WireState.SignedOut
        commands.close()
    } }

    private fun enqueue(action: suspend () -> Unit): Boolean = commands.trySend(action).isSuccess

    private fun connect() {
        if (stopped) return
        generation++
        mutableState.value = WireState.Connecting
        socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { enqueue {
                if (socket !== webSocket || stopped) return@enqueue
                val payload = buildJsonObject {
                    put("focus", runCatching(focus).getOrDefault("")); put("since", runCatching(since).getOrDefault(Envelope.EMPTY))
                    put("history_protocol", FRAME_VERSION); put("generation", generation)
                    put("label", label)
                }
                attachRef = transmit(Up.ATTACH, payload, Pending(Up.ATTACH), allowBeforeAttach = true)
            } }
            override fun onMessage(webSocket: WebSocket, text: String) { enqueue {
                if (socket === webSocket && !stopped) receive(text)
            } }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { enqueue {
                if (socket === webSocket && !stopped) disconnected(reason.ifEmpty { "connection closed" })
            } }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { enqueue {
                if (socket === webSocket && !stopped) disconnected(t.message ?: "connection failed")
            } }
        })
    }

    private fun transmit(type: String, payload: JsonObject, entry: Pending, allowBeforeAttach: Boolean = false): String {
        val ref = "$type-${++counter}"
        try {
            val current = mutableState.value
            if (current is WireState.Incompatible) throw WireException(type, "version_incompatible", current.detail, ref)
            if (stopped) throw WireException(type, "closed", "wire closed by client", ref)
            if (socket == null || (!allowBeforeAttach && current !is WireState.Attached)) {
                throw WireException(type, "unavailable", "wire is not attached", ref)
            }
            val outgoing = if (type == Up.SUBMIT) stampOrigin(payload) else payload
            val encoded = FrameCodec.encode(type, ref, outgoing)
            pending[ref] = entry
            entry.timer = scope.launch {
                delay(receiptTimeoutMs)
                enqueue {
                    val expired = remove(ref)
                    expired?.fail(WireException(type, "timeout", "frame receipt timed out", ref))
                    if (expired != null && ref == attachRef && !stopped) disconnected("attach receipt timed out")
                }
            }
            if (socket?.send(encoded) != true) throw WireException(type, "closed", "connection closed", ref)
        } catch (e: Exception) {
            remove(ref)
            entry.fail(e)
            if (type == Up.ATTACH && !stopped) disconnected(e.message ?: "attach failed")
        }
        return ref
    }

    private fun stampOrigin(payload: JsonObject): JsonObject {
        if (payload["msg_type"].string() != "agent.ask") return payload
        val attached = mutableState.value as? WireState.Attached ?: return payload
        val session = attached.receipt["session"].string()
        val body = payload["payload"] as? JsonObject ?: return payload
        if (session.isEmpty() || body.containsKey("origin")) return payload
        val origin = buildJsonObject {
            put("session", session)
            val name = attached.receipt["label"].string()
            if (name.isNotEmpty()) put("label", name)
        }
        return JsonObject(payload + ("payload" to JsonObject(body + ("origin" to origin))))
    }

    private suspend fun receive(text: String) {
        val raw: JsonObject
        val frame: DownFrame
        try {
            raw = Json.parseToJsonElement(text).jsonObject
            frame = FrameCodec.decode(raw) ?: return
        } catch (e: FrameVersionException) {
            stopped = true
            reconnectJob?.cancel(); reconnectJob = null
            rejectPending("version_incompatible", e.message!!)
            mutableState.value = WireState.Incompatible(e.message!!)
            socket?.close(1002, "version incompatible"); socket = null
            return
        } catch (e: Exception) {
            disconnected("invalid downstream frame")
            return
        }
        val p = raw["payload"] as? JsonObject ?: Envelope.EMPTY
        if (frame is DownFrame.Feed || frame is DownFrame.Checkpoint || frame is DownFrame.PageEnd) {
            if (mutableState.value !is WireState.Attached || p.long("generation") != generation) return
        }
        when (frame) {
            is DownFrame.Receipt -> {
                val entry = pending[frame.ref] ?: return
                // History receipts acknowledge scheduling; page_end completes the page.
                if (entry.page != null) return
                remove(frame.ref)
                if (frame.ref == attachRef) {
                    reconnectAttempt = 0
                    mutableState.value = WireState.Attached(generation, frame.payload)
                }
                entry.receipt?.complete(frame.payload)
            }
            is DownFrame.Error -> {
                remove(frame.ref)?.fail(WireException(frame.frame, frame.code, frame.detail, frame.ref))
                if (frame.ref == attachRef) disconnected(frame.detail.ifEmpty { frame.code })
                else mutableFrames.emit(frame)
            }
            is DownFrame.Feed -> {
                if (frame.ref.isEmpty()) mutableFrames.emit(frame)
                else pending[frame.ref]?.takeIf { it.page != null }?.rows?.add(frame.row)
            }
            is DownFrame.PageEnd -> {
                val entry = remove(frame.ref) ?: return
                val error = frame.payload["error_code"].string()
                if (error.isNotEmpty()) entry.fail(WireException(entry.type, error, frame.payload["error_detail"].string(), frame.ref))
                else entry.page?.complete(HistoryPage(entry.rows.sortedBy { it.seq }, frame.payload))
            }
            is DownFrame.Memberships -> if (mutableState.value is WireState.Attached) mutableFrames.emit(frame)
            else -> mutableFrames.emit(frame)
        }
    }

    private fun remove(ref: String): Pending? = pending.remove(ref)?.also { it.timer?.cancel() }
    private fun rejectPending(code: String, detail: String) {
        pending.forEach { (ref, entry) -> entry.fail(WireException(entry.type, code, detail, ref)) }
        pending.clear()
    }
    private fun disconnected(reason: String) {
        socket?.cancel(); socket = null
        rejectPending("closed", reason)
        if (stopped || reconnectJob != null) return
        val wait = minOf(maxReconnectDelayMs, reconnectBaseMs * (1L shl minOf(reconnectAttempt++, 6)))
        mutableState.value = WireState.Disconnected(reason, wait)
        reconnectJob = scope.launch {
            delay(wait)
            enqueue { reconnectJob = null; connect() }
        }
    }
    private fun unavailable(type: String): Nothing = throw WireException(type, "closed", "wire closed by client")
}

package dev.atoll.app.app

import dev.atoll.app.core.*
import dev.atoll.app.net.*
import dev.atoll.app.ui.screens.UpgradeViewState
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody.Companion.toRequestBody

private val activeUpdatePhases = setOf("starting", "downloading", "verifying", "installing", "restarting")
data class NodeUpdateValue(val current: String = "", val latest: String = "", val available: Boolean = false,
    val phase: String = "checking", val detail: String = "", val pending: Boolean = false) {
    val active get() = phase in activeUpdatePhases
    val canStart get() = available && !active && !pending && phase !in setOf("unsupported", "unavailable")
    fun view() = UpgradeViewState(current.ifEmpty { "…" }, latest, when {
        pending -> "starting"
        phase == "idle" -> if (available) "available" else "current"
        else -> phase
    }, detail, available)
    companion object {
        fun fromJson(json: JsonObject): NodeUpdateValue {
            val detail = json["detail"].string()
            val unsupported = json["status"].string() == "unsupported" || detail.contains("开发版") || detail.contains("development build", true)
            return NodeUpdateValue(json["current_version"].string(), json["latest_version"].string(),
                !unsupported && json["available"] == JsonPrimitive(true), if (unsupported) "unsupported" else json["status"].string().ifEmpty { "idle" }, detail)
        }
    }
}
interface NodeUpdateApi { suspend fun status(check: Boolean): NodeUpdateValue; suspend fun start(): NodeUpdateValue }
class NodeUpdateClient(private val server: String, private val http: OkHttpClient) : NodeUpdateApi {
    override suspend fun status(check: Boolean) = call(Request.Builder().url(server.toHttpUrl().resolve(if (check) "/api/update?check=1" else "/api/update")!!).build())
    override suspend fun start() = call(Request.Builder().url(server.toHttpUrl().resolve("/api/update")!!).post("".toRequestBody()).build())
    private suspend fun call(request: Request) = http.consume(request) { response ->
        response.requireSuccess()
        NodeUpdateValue.fromJson(Json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject)
    }
}

/** One state writer; HTTP never blocks it. Only a confirmed root owner creates this monitor. */
class NodeUpdateMonitor(private val api: NodeUpdateApi, wire: WireClient, parent: CoroutineScope,
    private val root: Boolean, private val checkEveryMs: Long = 6 * 60 * 60 * 1000L, private val pollEveryMs: Long = 1000) : AutoCloseable {
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private val events = Channel<Event>(32)
    private val mutable = MutableStateFlow(NodeUpdateValue())
    val state = mutable.asStateFlow()
    private var reading: Job? = null
    private var polling: Job? = null
    private var readRevision = 0L
    private var refreshAgain = false
    private var lastGeneration = 0L
    init {
        scope.launch { for (event in events) when (event) {
            is Refresh -> read(event.check)
            is Connection -> if (event.value is WireState.Attached && event.value.generation != lastGeneration) {
                if (lastGeneration != 0L) read(true)
                lastGeneration = event.value.generation
            }
            Start -> if (root && mutable.value.canStart) {
                reading?.cancel(); reading = null; readRevision++
                mutable.value = mutable.value.copy(pending = true)
                scope.launch { result(starting = true, revision = 0) { api.start() } }
            }
            is Result -> {
                if (!event.starting && event.revision != readRevision) continue
                if (!event.starting) reading = null
                if (event.value != null) mutable.value = event.value
                else {
                    val previous = mutable.value
                    val failure = event.failure
                    val unavailable = failure is HttpFailure && failure.status in setOf(401, 403, 503)
                    // A restarting node may close HTTP before its new process is reachable.
                    if (!previous.active || unavailable || event.starting) mutable.value = previous.copy(pending = false,
                        phase = if (unavailable || !event.starting) "unavailable" else "failed",
                        available = previous.available && !unavailable, detail = failure?.message.orEmpty())
                }
                pollIfActive()
                if (refreshAgain && !mutable.value.pending) { refreshAgain = false; read(true) }
            }
        } }
        if (root) {
            scope.launch { events.send(Refresh(true)); while (isActive) { delay(checkEveryMs); events.send(Refresh(true)) } }
            scope.launch { wire.state.collect { events.send(Connection(it)) } }
        }
    }
    fun start() { if (root) scope.launch { events.send(Start) } }
    private fun read(check: Boolean) {
        if (!root || mutable.value.pending) { if (check) refreshAgain = true; return }
        if (reading != null) { if (check) refreshAgain = true; return }
        val revision = ++readRevision
        reading = scope.launch { result(false, revision) { api.status(check) } }
    }
    private suspend fun result(starting: Boolean, revision: Long, call: suspend () -> NodeUpdateValue) {
        try { events.send(Result(starting, revision, call(), null)) }
        catch (failure: Exception) { if (failure !is CancellationException) events.send(Result(starting, revision, null, failure)) }
    }
    private fun pollIfActive() {
        if (!mutable.value.active) { polling?.cancel(); polling = null }
        else if (polling == null) polling = scope.launch { while (isActive) { delay(pollEveryMs); events.send(Refresh(false)) } }
    }
    override fun close() { scope.cancel(); events.close() }
    private sealed interface Event
    private data class Refresh(val check: Boolean) : Event
    private data class Connection(val value: WireState) : Event
    private data object Start : Event
    private data class Result(val starting: Boolean, val revision: Long, val value: NodeUpdateValue?, val failure: Exception?) : Event
}

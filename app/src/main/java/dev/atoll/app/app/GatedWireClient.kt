package dev.atoll.app.app

import dev.atoll.app.core.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject

/** AppGraph checks the server world before releasing a connection to the durable cache. */
class GatedWireClient(private val delegate: WireClient) : WireClient {
    private val connection = MutableStateFlow<WireState>(WireState.Connecting)
    private val incoming = MutableSharedFlow<DownFrame>(extraBufferCapacity = 256)
    private val processedGeneration = MutableStateFlow(0L)
    override val state: StateFlow<WireState> = connection
    override val frames: SharedFlow<DownFrame> = incoming
    val subscribers get() = incoming.subscriptionCount
    fun connection(state: WireState) {
        connection.value = state
        if (state is WireState.Attached) processedGeneration.value = state.generation
    }
    /** Call on the wire collector, never the manager: a future frame waits for its checked attach. */
    suspend fun awaitReady(frame: DownFrame): Boolean {
        val generation = generation(frame)
        if (generation <= 0) return true
        return processedGeneration.first { it >= generation } == generation
    }
    /** Recheck after enqueueing: the manager may have processed another attach in the meantime. */
    suspend fun receive(frame: DownFrame): Boolean {
        val generation = generation(frame)
        if (generation > 0 && generation != processedGeneration.value) return false
        incoming.emit(frame)
        return true
    }
    override suspend fun request(type: String, payload: JsonObject) = delegate.request(type, payload)
    override suspend fun history(payload: JsonObject) = delegate.history(payload)
    private fun generation(frame: DownFrame) = when (frame) {
        is DownFrame.Feed -> frame.generation
        is DownFrame.Checkpoint -> frame.generation
        else -> 0L
    }
}

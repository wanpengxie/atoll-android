package dev.atoll.app.sync

import dev.atoll.app.core.DownFrame
import dev.atoll.app.core.WireClient
import dev.atoll.app.core.WireState
import dev.atoll.app.core.string
import dev.atoll.app.store.RowStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** One wire subscription; all member-channel live rows are cached even without an open UI. */
class SyncHub(private val wire: WireClient, private val store: RowStore, parentScope: CoroutineScope) {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job + Dispatchers.Default)
    private val mailbox = Channel<Message>(256)
    private val channels = mutableMapOf<String, ChannelSync>() // Accessed by the hub writer only.
    private var connection: WireState = wire.state.value

    init {
        scope.launch {
            try {
                for (message in mailbox) when (message) {
                    is Get -> try {
                        message.reply.complete(getOrCreate(message.id))
                    } catch (failure: Exception) {
                        message.reply.completeExceptionally(failure)
                        throw failure
                    }
                    is Connection -> attach(message.state)
                    is Frame -> {
                        if (message.frame is DownFrame.Memberships) {
                            val attached = connection as? WireState.Attached
                            if (attached != null) attach(attached.copy(receipt = JsonObject(attached.receipt + message.frame.payload)))
                            continue
                        }
                        val id = when (val frame = message.frame) {
                            is DownFrame.Feed -> frame.row.channelId.takeIf { frame.ref.isEmpty() }
                            is DownFrame.Checkpoint -> frame.channelId
                            is DownFrame.ObserveEnded -> frame.channelId
                            else -> null
                        }
                        if (!id.isNullOrEmpty()) getOrCreate(id).accept(message.frame)
                    }
                }
            } finally {
                channels.values.forEach { it.close() }
                mailbox.close()
                while (true) {
                    val message = mailbox.tryReceive().getOrNull() ?: break
                    if (message is Get) message.reply.cancel()
                }
            }
        }
        scope.launch { wire.state.collect { mailbox.send(Connection(it)) } }
        scope.launch { wire.frames.collect { mailbox.send(Frame(it)) } }
    }

    suspend fun channel(channelId: String): ChannelSync {
        require(channelId.isNotEmpty())
        val reply = CompletableDeferred<ChannelSync>()
        mailbox.send(Get(channelId, reply))
        return reply.await()
    }

    fun close() { scope.cancel() }
    /** Await this before clearing the account DB on a boot/sign-in change. */
    suspend fun stop() { job.cancelAndJoin() }

    private suspend fun getOrCreate(id: String): ChannelSync = channels[id] ?: ChannelSync(id, wire, store, scope).also {
        channels[id] = it
        it.accept(connection)
    }

    private suspend fun attach(state: WireState) {
        connection = state
        if (state is WireState.Attached) {
            // Instantiate sync writers, never timeline replicas: every membership catches up.
            val entries = listOf("memberships", "history_meta").flatMap {
                (state.receipt[it] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
            }
            entries.map { it["channel_id"].string() }.filter { it.isNotEmpty() }.distinct().forEach { getOrCreate(it) }
        }
        channels.values.forEach { it.accept(state) }
    }

    private sealed interface Message
    private data class Get(val id: String, val reply: CompletableDeferred<ChannelSync>) : Message
    private data class Connection(val state: WireState) : Message
    private data class Frame(val frame: DownFrame) : Message
}

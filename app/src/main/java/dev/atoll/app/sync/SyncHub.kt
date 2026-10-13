package dev.atoll.app.sync

import dev.atoll.app.core.DownFrame
import dev.atoll.app.core.WireClient
import dev.atoll.app.core.WireState
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

/** One wire subscription; all member-channel live rows are cached even without an open UI. */
class SyncHub(private val wire: WireClient, private val store: RowStore, parentScope: CoroutineScope) {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job + Dispatchers.Default)
    private val mailbox = Channel<Message>(256)
    private val channels = mutableMapOf<String, ChannelSync>() // Accessed by the hub writer only.

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
                    is Connection -> channels.values.forEach { it.accept(message.state) }
                    is Frame -> {
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
        it.accept(wire.state.value)
    }

    private sealed interface Message
    private data class Get(val id: String, val reply: CompletableDeferred<ChannelSync>) : Message
    private data class Connection(val state: WireState) : Message
    private data class Frame(val frame: DownFrame) : Message
}

package dev.atoll.app.stars

import androidx.lifecycle.ViewModel
import dev.atoll.app.core.Envelope
import dev.atoll.app.ui.screens.StarredMessageViewState
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.util.Collections

data class StarredState(val ready: Boolean = false, val channels: Map<String, List<StarredMessage>> = emptyMap(), val error: String = "") {
    private val ids = channels.mapValues { (_, messages) -> messages.mapTo(hashSetOf()) { it.id } }
    fun starsFor(channelId: String): List<StarredMessage> = channels[channelId].orEmpty()
    fun isStarred(channelId: String, messageId: String): Boolean = messageId in ids[channelId].orEmpty()
    fun drawerItems(channelId: String, actorNames: Map<String, String>, timeLabel: (Long) -> String): List<StarredMessageViewState> =
        starsFor(channelId).map { StarredMessageViewState(it.id, actorNames[it.sender]?.takeIf(String::isNotEmpty) ?: "未知成员", timeLabel(it.ts), it.text) }
}

/** One background writer loads before processing clicks, then publishes immutable snapshots.
 * Own one VM per principal/world; channel changes only choose a list from state.
 */
class StarredViewModel(val owner: StarScope, private val store: StarredStore,
    dispatcher: CoroutineDispatcher = Dispatchers.IO, private val now: () -> Long = System::currentTimeMillis,
) : ViewModel(), AutoCloseable {
    private sealed interface Command {
        data class Toggle(val channel: String, val message: StarredMessage) : Command
        data class Remove(val channel: String, val id: String) : Command
    }
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutable = MutableStateFlow(StarredState())
    val state: StateFlow<StarredState> = mutable.asStateFlow()
    init {
        scope.launch {
            try {
                mutable.value = try { snapshot(store.load(owner)) }
                catch (_: Exception) { snapshot(emptyMap(), "无法读取本地星标") }
                for (command in commands) {
                    val channels = mutable.value.channels.toMutableMap()
                    val channel = when (command) { is Command.Toggle -> command.channel; is Command.Remove -> command.channel }
                    val current = channels[channel].orEmpty()
                    val next = when (command) {
                        is Command.Toggle -> if (current.any { it.id == command.message.id }) current.filterNot { it.id == command.message.id }
                            else listOf(command.message.copy(text = starSnippet(command.message.text), starredAt = now())) + current
                        is Command.Remove -> current.filterNot { it.id == command.id }
                    }
                    if (next == current) continue
                    if (next.isEmpty()) channels.remove(channel) else channels[channel] = next
                    // Like web, a persistence failure retains this session's in-memory bookmarks.
                    val error = try { store.save(owner, channels); "" } catch (_: Exception) { "星标未能保存到本机，重启后可能丢失" }
                    mutable.value = snapshot(channels, error)
                }
            } finally { scope.cancel() }
        }
    }
    fun toggle(envelope: Envelope, text: String) {
        if (envelope.channelId.isEmpty() || envelope.id.isEmpty()) return
        commands.trySend(Command.Toggle(envelope.channelId, StarredMessage(envelope.id, envelope.senderId, text, envelope.ts)))
    }
    fun remove(channelId: String, messageId: String) {
        if (channelId.isEmpty() || messageId.isEmpty()) return
        commands.trySend(Command.Remove(channelId, messageId))
    }
    private fun snapshot(channels: Map<String, List<StarredMessage>>, error: String = "") = StarredState(true,
        Collections.unmodifiableMap(channels.mapValues { (_, list) -> Collections.unmodifiableList(ArrayList(list)) }), error)

    // Drain already accepted local edits on account switch instead of discarding the final click.
    override fun close() { commands.close() }
    override fun onCleared() { close() }
}

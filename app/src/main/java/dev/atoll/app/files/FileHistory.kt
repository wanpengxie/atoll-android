package dev.atoll.app.files

import dev.atoll.app.net.KeyValueStore
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** Only file references are persisted, scoped to server/principal/world; never bytes/tickets. */
class FileHistory(private val store: KeyValueStore, server: String, principalId: String, world: String) {
    private val key = "files.$server.$principalId.$world"
    private val mutableRecent = MutableStateFlow(runCatching { Json.decodeFromString<List<FileReference>>(store.get("$key.recent") ?: "[]") }.getOrDefault(emptyList()).take(24))
    val recent: StateFlow<List<FileReference>> = mutableRecent.asStateFlow()
    private val stacks = LinkedHashMap(runCatching { Json.decodeFromString<Map<String, List<FileReference>>>(store.get("$key.stacks") ?: "{}") }.getOrDefault(emptyMap()))
    @Synchronized fun opened(reference: FileReference, push: Boolean = true) {
        val ref = reference.copy(openedAt = System.currentTimeMillis())
        mutableRecent.value = (listOf(ref) + mutableRecent.value.filter { it.channelId != ref.channelId || it.resourceId != ref.resourceId }).take(24)
        store.put("$key.recent", Json.encodeToString(mutableRecent.value))
        if (push) {
            val current = stacks.remove(ref.channelId).orEmpty()
            stacks[ref.channelId] = (if (current.lastOrNull()?.key == ref.key) current.dropLast(1) else current) + ref
            stacks[ref.channelId] = stacks.getValue(ref.channelId).takeLast(20)
            while (stacks.size > 6) stacks.remove(stacks.keys.first())
            persistStacks()
        }
    }
    @Synchronized fun stack(channelId: String): List<FileReference> = stacks[channelId].orEmpty().toList()
    @Synchronized fun back(channelId: String): FileReference? {
        val stack = stacks[channelId].orEmpty()
        if (stack.size < 2) return null
        stacks[channelId] = stack.dropLast(1); persistStacks()
        return stacks[channelId]?.lastOrNull()
    }
    @Synchronized fun close(channelId: String) { stacks.remove(channelId); persistStacks() }
    private fun persistStacks() { store.put("$key.stacks", Json.encodeToString(stacks.toMap())) }
}

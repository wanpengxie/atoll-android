package dev.atoll.app.stars

import dev.atoll.app.net.KeyValueStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** An account's bookmarks belong to one validated server world, never to a transport session. */
data class StarScope(val server: String, val principalId: String, val world: String) {
    init { require(server.isNotBlank() && principalId.isNotBlank() && world.isNotBlank()) }
    val storageKey: String get() = "atoll.stars.v1." + JsonArray(listOf(server, principalId, world).map(::JsonPrimitive))
}

/** The short snapshot remains readable without loading the original envelope. */
@Serializable
data class StarredMessage(val id: String, val sender: String = "", val text: String = "", val ts: Long = 0, val starredAt: Long = 0)

fun starSnippet(text: String): String {
    val line = text.lineSequence().map(String::trim).firstOrNull(String::isNotEmpty).orEmpty()
    return if (line.length > 120) line.take(120) + "…" else line
}

/** Same channel -> newest-first lists as web starred-messages.js; no node calls. */
class StarredStore(private val values: KeyValueStore) {
    private val codec = Json { ignoreUnknownKeys = true }
    fun load(scope: StarScope): Map<String, List<StarredMessage>> {
        val stored = values.get(scope.storageKey) ?: return emptyMap()
        // Web treats unreadable bookmark data as empty too.
        val channels = runCatching { codec.parseToJsonElement(stored) as? JsonObject }.getOrNull() ?: return emptyMap()
        return channels.filterKeys(String::isNotEmpty).mapValues { (_, list) ->
            (list as? JsonArray).orEmpty().mapNotNull { item ->
                runCatching { codec.decodeFromJsonElement<StarredMessage>(item) }.getOrNull()?.takeIf { it.id.isNotEmpty() }
            }.distinctBy { it.id }
        }
            .filterValues(List<StarredMessage>::isNotEmpty)
    }
    fun save(scope: StarScope, channels: Map<String, List<StarredMessage>>) {
        if (channels.isEmpty()) values.remove(scope.storageKey) else values.put(scope.storageKey, codec.encodeToString(channels))
    }
}

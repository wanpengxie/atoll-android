package dev.atoll.app.roster

import dev.atoll.app.app.WorkspaceServices
import dev.atoll.app.core.*
import dev.atoll.app.net.actorRequest
import kotlinx.serialization.json.*

interface RosterPort {
    suspend fun roster(): JsonObject
    suspend fun actor(target: String, word: String, payload: JsonObject): JsonObject
    suspend fun descriptions(): List<JsonObject>
    suspend fun devices(): List<JsonObject>
    suspend fun globalNames(): List<String>
}
class RosterRepository(private val services: WorkspaceServices, private val channelId: String) : RosterPort {
    override suspend fun roster() = services.obs.channelActors(channelId)
    override suspend fun actor(target: String, word: String, payload: JsonObject) = services.wire.actorRequest(channelId, target, word, payload)
    override suspend fun descriptions() = RosterModel.latestDescriptions(services.obs.spaceActorDescriptions().array("items"))
    override suspend fun devices() = services.obs.spaceDaemons().array("items").filterIsInstance<JsonObject>().map { it["declared"] as? JsonObject ?: it }
    /** Read names only. Tickets and secret values never enter the feature state. */
    override suspend fun globalNames(): List<String> {
        val names = sortedSetOf<String>(); val cursors = hashSetOf<String>(); var cursor = ""
        do {
            val page = services.wire.request(Up.RESOURCE, buildJsonObject {
                put("channel_id", channelId); put("op", "list"); put("query", buildJsonObject { put("prefix", "global/"); put("limit", 500); if (cursor.isNotEmpty()) put("cursor", cursor) })
            })
            if (page.text("status") == "rejected") throw WireException("resource", "rejected", page.text("detail"))
            page.array("items").filterIsInstance<JsonObject>().forEach {
                val id = it.text("id").ifEmpty { it.text("resource_id") }
                if (id.startsWith("global/")) id.removePrefix("global/").takeIf(MemberConfig::globalName)?.let(names::add)
            }
            cursor = page.text("next")
            check(cursor.isEmpty() || cursors.add(cursor)) { "资源分页游标重复" }
        } while (cursor.isNotEmpty())
        return names.toList()
    }
}

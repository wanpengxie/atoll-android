package dev.atoll.app.governance

import dev.atoll.app.core.*
import dev.atoll.app.net.ObsClient
import dev.atoll.app.net.actorRequest
import kotlinx.coroutines.async
import kotlinx.serialization.json.*
import java.util.UUID

interface GovernancePort {
    suspend fun execute(channel: String, command: GovernanceCommand, id: String = UUID.randomUUID().toString(), accepted: () -> Unit = {}): JsonObject
    suspend fun directory(channel: String): GovernanceDirectory
    suspend fun channels(): List<JsonObject>
    suspend fun resource(channel: String, payload: JsonObject): JsonObject
}
data class GovernanceDirectory(val channels: List<JsonObject> = emptyList(), val actors: List<JsonObject> = emptyList(),
    val devices: List<JsonObject> = emptyList(), val principals: List<JsonObject> = emptyList(), val descriptions: List<JsonObject> = emptyList(),
    val profile: JsonObject = JsonObject(emptyMap()),val channelDevices: List<JsonObject> = emptyList())

class GovernanceRepository(private val wire: WireClient, private val obs: ObsClient) : GovernancePort {
    override suspend fun channels(): List<JsonObject> = obs.spaceChannels().rows()
    override suspend fun execute(channel: String, command: GovernanceCommand, id: String, accepted: () -> Unit): JsonObject {
        val receiptAware = object : WireClient by wire {
            override suspend fun request(type: String, payload: JsonObject): JsonObject = wire.request(type, payload).also { accepted() }
        }
        return receiptAware.actorRequest(channel, "system", command.word, command.payload, messageId = id)
    }
    override suspend fun directory(channel: String): GovernanceDirectory = kotlinx.coroutines.coroutineScope {
        val channels = async { obs.spaceChannels() }
        val actors = async { obs.channelActors(channel) }
        val devices = async { obs.spaceDaemons() }
        val principals = async { obs.spacePrincipals() }
        val descriptions = async { obs.spaceActorDescriptions() }
        val profile = async { obs.channelProfile(channel) }
        val attached = async { obs.channelDevices(channel) }
        GovernanceDirectory(channels.await().rows(), actors.await().rows(), devices.await().rows(),
            principals.await().rows(), descriptions.await().rows().map { it.declared() }, profile.await().rows().firstOrNull()?.declared() ?: JsonObject(emptyMap()),attached.await().rows())
    }
    override suspend fun resource(channel: String, payload: JsonObject): JsonObject = wire.request(Up.RESOURCE, JsonObject(payload + ("channel_id" to JsonPrimitive(channel)))).also {
        if (it.text("status") == "rejected") throw WireException("resource", it.text("detail").ifEmpty { "rejected" }, "资源操作被拒绝")
    }
}

/** Lists names only; no value read, reflection, or diagnostic retention. */
class GlobalKeys(private val port: GovernancePort, private val channel: String) {
    private fun address(name: String): String = name.trim().also { require(it.matches(Regex("[a-z0-9_-]{1,64}"))) { "全局 key 名称无效" } }.let { "global/$it" }
    suspend fun names(): List<String> {
        val names = sortedSetOf<String>()
        var cursor = ""
        val seen = mutableSetOf<String>()
        do {
            val page = port.resource(channel, buildJsonObject {
                put("op", "list"); put("query", buildJsonObject { put("prefix", "global/"); put("limit", 500); if (cursor.isNotEmpty()) put("cursor", cursor) })
            })
            page.rows().forEach {
                val id = it.text("id").ifEmpty { it.text("resource_id") }
                if (id.startsWith("global/")) id.removePrefix("global/").takeIf { n -> n.matches(Regex("[a-z0-9_-]{1,64}")) }?.let(names::add)
            }
            cursor = page.text("next")
            check(cursor.isEmpty() || seen.add(cursor)) { "资源分页游标重复" }
        } while (cursor.isNotEmpty())
        return names.toList()
    }
    suspend fun save(name: String, value: String, overwrite: Boolean) {
        require(value.isNotEmpty()) { "全局 key 的值不能为空" }
        val id = address(name)
        if (overwrite) { write("write", id, value); return }
        val exists = port.resource(channel, buildJsonObject { put("op", "stat"); put("resource_id", id) }).bool("exists")
        if (exists) write("write", id, value) else try { write("create", id, value) }
        catch (failure: WireException) { if (failure.code in setOf("conflict_exists", "already_exists")) write("write", id, value) else throw failure }
    }
    private suspend fun write(op: String, id: String, value: String) { port.resource(channel, buildJsonObject { put("op", op); put("resource_id", id); put("args", value) }) }
    suspend fun delete(name: String) { port.resource(channel, buildJsonObject { put("op", "delete"); put("resource_id", address(name)) }) }
}

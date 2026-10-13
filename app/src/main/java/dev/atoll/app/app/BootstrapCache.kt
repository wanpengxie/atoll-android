package dev.atoll.app.app

import dev.atoll.app.core.string
import dev.atoll.app.ui.screens.*
import kotlinx.serialization.json.*

/** Display-only until HTTP identity confirms this principal; never grants write access. */
data class BootstrapCache(val principal: String, val name: String, val memberships: Map<String, String>,
    val channels: List<ChannelRecord>, val heads: Map<String, Long> = emptyMap(), val lastChannel: String? = null) {
    fun forPrincipal(id: String) = takeIf { principal == id }
    fun navigation() = ChannelNavigationViewState(sortedChannels(channels + memberships.keys.filter { id -> channels.none { it.id == id } }
        .map { ChannelRecord(it, it, null, it) }).map { (record, depth) ->
        ChannelViewState(record.id, record.qualifiedName.ifEmpty { record.name }, record.description,
            parentId = record.parent, depth = depth, access = if (record.id in memberships) ChannelAccess.MemberStale else ChannelAccess.Discoverable,
            unreadPhase = if (record.id in memberships) UnreadPhase.Recovering else UnreadPhase.Ready)
    }, ConnectionPhase.CONNECTING, AccountViewState(principal, name, root = false), lastChannel)
    fun encode(): String = buildJsonObject {
        put("principal", principal); put("name", name); lastChannel?.let { put("last", it) }
        put("memberships", JsonObject(memberships.mapValues { JsonPrimitive(it.value) }))
        put("heads", JsonObject(heads.mapValues { JsonPrimitive(it.value) }))
        put("channels", buildJsonObject { put("items", buildJsonArray { channels.forEach { record -> add(buildJsonObject {
            put("id", record.id); put("name", record.name); put("qualified_name", record.qualifiedName)
            put("type", record.type); put("description", record.description); record.parent?.let { put("parent_id", it) }
        }) } }) })
    }.toString()
    companion object {
        fun decode(value: String?): BootstrapCache? = runCatching {
            if (value.isNullOrBlank()) return null
            val json = Json.parseToJsonElement(value).jsonObject
            val principal = json["principal"].string().takeIf(String::isNotEmpty) ?: return null
            BootstrapCache(principal, json["name"].string().ifEmpty { principal },
                (json["memberships"] as? JsonObject).orEmpty().mapValues { it.value.string() }.filterValues(String::isNotEmpty),
                channelRecords(json["channels"] as? JsonObject ?: JsonObject(emptyMap())),
                (json["heads"] as? JsonObject).orEmpty().mapValues { (it.value as? JsonPrimitive)?.longOrNull ?: 0 },
                json["last"].string().takeIf(String::isNotEmpty))
        }.getOrNull()
    }
}

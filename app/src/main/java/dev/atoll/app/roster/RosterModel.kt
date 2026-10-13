package dev.atoll.app.roster

import dev.atoll.app.core.Envelope
import kotlinx.serialization.json.*

data class RosterActor(val id: String, val name: String, val kind: String = "", val body: String = "", val description: String = "",
    val configId: String = "", val entryId: String = "", val principal: String = "", val bound: Boolean? = null, val deviceOnline: Boolean? = null) {
    val memberId: String get() = id.ifEmpty { configId }
    val presence: String get() = when { bound == true && deviceOnline == true -> "在线"; bound == true -> "已绑定"; bound == false -> "未绑定"; else -> "未知" }
}
data class ActorCapability(val type: String, val description: String, val errors: List<String>, val schema: JsonObject?, val raw: JsonObject)
data class ReferenceOption(val value: String, val label: String)

object RosterModel {
    fun rows(observation: JsonObject): List<RosterActor> = observation.array("items").mapNotNull { raw ->
        val item = raw as? JsonObject ?: return@mapNotNull null
        val declared = item["declared"] as? JsonObject ?: item
        val id = declared.text("id").ifEmpty { item.text("key") }
        if (id.isEmpty() || declared.text("body") == "generated") return@mapNotNull null
        fun measure(name: String): Boolean? {
            val row = item.obj("actual").array("measures").filterIsInstance<JsonObject>().find { it.text("name") == name }
            return if (row?.bool("unknown") == true) null else row?.bool("value") ?: false
        }
        RosterActor(id, declared.text("name").ifEmpty { id }, declared.text("kind"), declared.text("body"), declared.text("description"),
            declared.text("config_id"), declared.text("entry_id"), declared.text("principal"), measure("bound"), measure("device_online"))
    }
    fun capabilities(manifest: JsonObject): List<ActorCapability> = manifest.obj("words").mapNotNull { (type, raw) ->
        val word = raw as? JsonObject ?: return@mapNotNull null
        val schema = word["input_schema"] ?: word["inputSchema"]
        ActorCapability(type, word.text("description"), word.array("error_codes").mapNotNull { (it as? JsonPrimitive)?.content ?: (it as? JsonObject)?.text("code")?.takeIf(String::isNotEmpty) },
            schema as? JsonObject ?: (schema as? JsonPrimitive)?.content?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }, word)
    }
    fun refreshesRoster(envelope: Envelope): Boolean = envelope.type in setOf("system.member.created", "system.member.deleted", "system.member.updated", "system.build.finished") ||
        envelope.isTerminal && envelope.status == "completed" && envelope.type in setOf("system.member.create", "system.member.admit", "system.member.delete", "system.member.set", "system.member.config.set", "system.member.config.write", "system.member.restart", "system.member.restart_all")
    fun latestDescriptions(items: JsonArray): List<JsonObject> = items.filterIsInstance<JsonObject>().map { it["declared"] as? JsonObject ?: it }
        .filter { it.text("id").isNotEmpty() && it.text("status") in setOf("", "present") }.groupBy { it.text("id") }.values.map { versions -> versions.maxBy { it.number("version") } }.sortedBy { it.text("name").ifEmpty { it.text("id") } }
    fun descriptionOptions(rows: List<JsonObject>, current: String): List<ReferenceOption> = buildList {
        for (row in rows) {
            val id = row.text("id"); val name = row.text("name").ifEmpty { id }
            val label = if (rows.any { it.text("id") != id && it.text("name") == row.text("name") }) "$name（${id.take(8)}）" else name
            add(ReferenceOption(id, "$label · 最新版（频道打开时解析）"))
            for (version in row.number("version") downTo 1) add(ReferenceOption("$id@$version", "$label · 钉死 @$version"))
        }
        if (current.isNotEmpty() && none { it.value == current }) add(0, ReferenceOption(current, current))
    }
}

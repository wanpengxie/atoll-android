package dev.atoll.app.governance

import dev.atoll.app.core.string
import kotlinx.serialization.json.*

data class GovernanceCommand(val word: String, val payload: JsonObject)
enum class ChannelStart { Blank, Copy, Pick }
data class ChannelCreateForm(val name: String, val parent: String, val selfPrincipal: String,
    val humans: Set<String> = emptySet(), val start: ChannelStart = ChannelStart.Blank,
    val copyFrom: String = "", val purpose: String = "", val members: List<JsonObject> = emptyList())

/** Business payloads match WorkspaceApp's governance port; transport adds the envelope. */
object GovernanceCommands {
    private fun command(word: String, body: JsonObjectBuilder.() -> Unit) = GovernanceCommand(word, buildJsonObject(body))
    private fun required(value: String) = value.trim().also { require(it.isNotEmpty()) { "必填项不能为空" } }
    fun memberCreate(candidate: String, directClass: Boolean, name: String = "") = command("system.member.create") {
        if (name.trim().isNotEmpty()) put("name", name.trim())
        put("body", buildJsonObject { put(if (directClass) "class" else "actor", required(candidate)) })
    }
    fun admit(principal: String) = command("system.member.admit") { require(principal != "guest"); put("principal", required(principal)) }
    fun memberDelete(member: String) = command("system.member.delete") { put("member", required(member)) }
    fun memberRestart(member: String) = command("system.member.restart") { put("member", required(member)) }
    fun channelGet(channel: String) = command("system.channel.get") { put("channel_id", required(channel)) }
    fun descriptionGet(channel: String) = command("system.channel.description.get") { put("channel", required(channel)) }
    fun channelSet(channel: String, description: String, serving: Boolean) = command("system.channel.set") {
        put("channel_id", required(channel)); put("description", description); put("serving", if (serving) 1 else 0)
    }
    fun channelDelete(channel: String) = command("system.channel.delete") { put("channel_id", required(channel)) }
    fun channelCreate(form: ChannelCreateForm) = command("system.channel.create") {
        val name = form.name.trim()
        require(validateGovernanceChannelName(name).isEmpty()) { validateGovernanceChannelName(name) }
        put("name", name); put("parent", required(form.parent))
        val humans = (listOf(required(form.selfPrincipal)) + form.humans.map(::required)).distinct()
        require("guest" !in humans) { "不能添加 guest" }
        put("humans", JsonArray(humans.map(::JsonPrimitive)))
        when (form.start) {
            ChannelStart.Copy -> put("copy_from", required(form.copyFrom))
            else -> {
                val members = if (form.start == ChannelStart.Pick) form.members.map { entry ->
                    require((entry["body"] as? JsonObject)?.get("human") != JsonPrimitive(true)) { "用户须通过人员选项加入" }
                    JsonObject(entry - "id")
                }.also { require(it.isNotEmpty()) { "请至少挑一个成员条目" } } else emptyList()
                if (form.purpose.trim().isNotEmpty() || members.isNotEmpty()) put("description", buildJsonObject {
                    if (form.purpose.trim().isNotEmpty()) put("description", form.purpose.trim())
                    put("members", JsonArray(members))
                })
            }
        }
    }
    fun deviceCreate(name: String) = command("system.device.create") { put("name", required(name)) }
    fun deviceAttach(channel: String, device: String, attach: Boolean) = command(if (attach) "system.device.attach" else "system.device.detach") {
        require(device != "local-device") { "local-device 是默认设备" }
        put("channel_id", required(channel)); put("device_id", required(device))
    }
    fun deviceDelete(device: String) = command("system.device.delete") {
        require(device != "local-device") { "不能退役默认设备" }; put("device_id", required(device))
    }
    fun actorDescriptionCreate(id: String, name: String, klass: String, description: String, params: JsonObject, configurable: Boolean) = command("system.actor.description.create") {
        if (id.trim().isNotEmpty()) put("id", id.trim())
        put("name", required(name)); put("class", required(klass))
        if (description.trim().isNotEmpty()) put("description", description.trim())
        if (params.isNotEmpty()) put("params", params)
        if (!configurable) put("configurable", false)
    }
    fun actorDescriptionRetire(id: String, version: Int) = command("system.actor.description.retire") {
        require(version > 0); put("id", required(id)); put("version", version)
    }
}

internal fun JsonObject.obj(key: String) = this[key] as? JsonObject ?: JsonObject(emptyMap())
internal fun JsonObject.text(key: String) = this[key].string()
internal fun JsonObject.bool(key: String) = this[key] == JsonPrimitive(true)
internal fun JsonObject.rows() = (this["items"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
internal fun JsonObject.declared() = (this["declared"] as? JsonObject) ?: this

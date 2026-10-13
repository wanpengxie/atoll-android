package dev.atoll.app.governance

import dev.atoll.app.core.Envelope
import kotlinx.serialization.json.*
import java.text.Collator
import java.util.Locale

internal fun JsonObject.integer(key: String)=(this[key] as? JsonPrimitive)?.intOrNull ?: 0
internal fun JsonObject.measure(name: String):Boolean? = (obj("actual")["measures"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.firstOrNull { it.text("name")==name }?.let { if (it.bool("unknown")) null else (it["value"] as? JsonPrimitive)?.booleanOrNull }
internal fun JsonObject.directoryId()=declared().text("id").ifEmpty { text("key") }
internal fun JsonObject.displayName()=declared().text("name").ifEmpty { declared().text("display_name").ifEmpty { declared().text("email").ifEmpty { directoryId() } } }
data class GovernanceMember(val id: String,val name: String,val kind: String,val principal: String,val entryId: String,val body: String,val online: Boolean?,val bound: Boolean?,val protected: Boolean,val source: JsonObject)
fun governanceMembers(rows: List<JsonObject>):List<GovernanceMember> = rows.mapNotNull { row ->
    val d=row.declared();val id=row.directoryId()
    if (id.isEmpty() || d.text("body")=="generated") null else GovernanceMember(id,row.displayName(),d.text("kind").ifEmpty { id.substringBefore(':') },d.text("principal"),d.text("entry_id"),
        d["body"]?.let { (it as? JsonPrimitive)?.content ?: it.toString() }.orEmpty(),row.measure("device_online"),row.measure("bound"),d.bool("protected"),row)
}
fun governancePrincipals(rows: List<JsonObject>):List<JsonObject> = rows.map { it.declared() }.filter { it.text("id").isNotEmpty() && it.text("id")!="guest" && it.text("kind") in setOf("","human") && it.text("status") in setOf("","present") }
fun governanceDescriptions(rows: List<JsonObject>,latestOnly: Boolean=false):List<JsonObject> {
    val declared=rows.map { it.declared() }.filter { it.text("id").isNotEmpty() }
    val values=if (latestOnly) declared.filter { it.text("status") in setOf("","present") }.groupBy { it.text("id") }.values.map { versions -> versions.maxBy { it.integer("version") } } else declared
    val order=Collator.getInstance(Locale.CHINA)
    return values.sortedWith { a,b -> order.compare(a.text("name").ifEmpty { a.text("id") },b.text("name").ifEmpty { b.text("id") }).takeIf { it!=0 } ?: a.text("id").compareTo(b.text("id")) }
}
internal fun descriptionRef(row: JsonObject)=row.text("ref").ifEmpty { "${row.text("id")}@${row.integer("version")}" }
internal fun descriptionLabel(row: JsonObject,rows: List<JsonObject>):String {
    val name=row.text("name").ifEmpty { row.text("id") };val clash=rows.any { it.text("id")!=row.text("id") && it.text("name")==row.text("name") }
    return "$name${if (clash) "（${row.text("id").take(8)}）" else ""} @${row.integer("version")}"
}
fun governanceCopyableChannels(channels: List<JsonObject>,memberships: Set<String>):List<JsonObject> = channels.filter { row ->
    val d=row.declared();row.directoryId() in memberships && row.directoryId() !in setOf("c0","c0.lobby","c0.home") && d.text("qualified_name").ifEmpty { d.text("name") } !in setOf("c0","c0.lobby","c0.home")
}
internal fun currentChannel(state: GovernanceState)=state.directory.channels.firstOrNull { it.directoryId()==state.channel }?.declared() ?: state.directory.profile.takeIf { it.text("id")==state.channel } ?: Envelope.EMPTY
internal fun channelName(state: GovernanceState)=currentChannel(state).text("qualified_name").ifEmpty { currentChannel(state).text("name").ifEmpty { state.channel } }
internal fun descriptionBody(state: GovernanceState)=state.channelView?.obj("description")?.get("body") as? JsonObject
internal fun descriptionEditable(state: GovernanceState)=descriptionBody(state)?.let { !it.bool("readonly") } == true
fun validateGovernanceChannelName(name: String):String = if (name.trim().matches(Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"))) "" else "名称须为 1–63 位小写字母、数字或连字符，且不能以连字符开头或结尾"

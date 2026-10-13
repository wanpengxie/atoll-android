package dev.atoll.app.app

import dev.atoll.app.core.Envelope
import dev.atoll.app.core.string
import dev.atoll.app.model.*
import dev.atoll.app.ui.screens.*
import dev.atoll.app.ui.screens.TimelineEntry as UiEntry
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class ChannelRecord(val id: String, val name: String, val parent: String?, val qualifiedName: String, val type: String = "group", val description: String = "", val open: Boolean? = null)
data class ActorRecord(val id: String, val name: String, val kind: String)

fun channelRecords(response: JsonObject): List<ChannelRecord> = response.objects("items").map { item ->
    val declared = item["declared"] as? JsonObject ?: item
    val open = (item["actual"] as? JsonObject)?.objects("measures")?.firstOrNull { it["name"].string() == "open" }
        ?.get("value")?.let { (it as? JsonPrimitive)?.booleanOrNull }
    ChannelRecord(declared["id"].string().ifEmpty { item["key"].string() }, declared["name"].string(),
        declared["parent_id"].string().takeIf { it.isNotEmpty() }, declared["qualified_name"].string(),
        declared["type"].string().ifEmpty { "group" }, declared["description"].string(), open)
}

fun actorRecords(response: JsonObject): List<ActorRecord> = response.objects("items").mapNotNull { item ->
    val declared = item["declared"] as? JsonObject ?: item
    val id = declared["id"].string().ifEmpty { item["key"].string() }
    if ((declared["generated"] as? JsonPrimitive)?.booleanOrNull == true || id.isEmpty()) null
    else ActorRecord(id, declared["name"].string().ifEmpty { id }, declared["kind"].string().ifEmpty { id.substringBefore(':') })
}

fun sortedChannels(records: Collection<ChannelRecord>): List<Pair<ChannelRecord, Int>> {
    val visible = records.filter { it.type != "actor" && it.qualifiedName !in setOf("c0.lobby", "c0.home") }
    val byId = visible.associateBy { it.id }
    val children = visible.groupBy { it.parent?.takeIf(byId::containsKey) }
    val output = mutableListOf<Pair<ChannelRecord, Int>>()
    fun visit(parent: String?, depth: Int) { children[parent].orEmpty().sortedWith(compareBy<ChannelRecord> { it.id != "c0" }.thenBy { it.name }).forEach { record -> output += record to depth; visit(record.id, depth + 1) } }
    visit(null, 0)
    return output
}

class TimelineMapper {
    private val clock = DateTimeFormatter.ofPattern("HH:mm")
    private data class Cached(val revision: String, val continuation: Boolean, val value: List<UiEntry>)
    private val cache = mutableMapOf<String, Cached>()
    private var rosterKey: Int = 0

    fun map(snapshot: TimelineSnapshot, selfId: String, actors: List<ActorRecord>, newerUnloaded: Boolean = false): List<UiEntry> {
        val names = actors.associate { it.id to it.name }
        if (actors.hashCode() != rosterKey) { cache.clear(); rosterKey = actors.hashCode() }
        val entries = mutableListOf<UiEntry>()
        var day = ""
        val retained = mutableSetOf<String>()
        for (item in snapshot.items) {
            if (item.dayKey.isNotEmpty() && item.dayKey != day) { day = item.dayKey; entries += UiEntry.Day("day:$day:${item.key}", day) }
            retained += item.key
            val previous = cache[item.key]
            val value = if (previous?.revision == item.contentRevision && previous.continuation == item.continuation) previous.value
            else render(item, selfId, names, newerUnloaded).also { cache[item.key] = Cached(item.contentRevision, item.continuation, it) }
            entries += value
        }
        cache.keys.retainAll(retained)
        return entries
    }

    private fun render(item: PresentationItem, self: String, names: Map<String, String>, newerUnloaded: Boolean): List<UiEntry> {
        val result = mutableListOf<UiEntry>()
        fun message(envelope: Envelope, key: String, text: String = messageText(envelope), status: MessageStatus? = null, progress: String? = null, continuation: Boolean = false) {
            result += UiEntry.Message(MessageViewState(key, names[envelope.senderId] ?: envelope.senderId, text,
                time(envelope.tsReceived.takeIf { it > 0 } ?: envelope.ts), envelope.senderKind == "agent", envelope.senderId == self,
                continuation, status, progress, envelope.audience.takeIf { envelope.kind == "request" }?.joinToString("、") { names[it] ?: it }))
        }
        fun turn(entry: dev.atoll.app.model.TimelineEntry, key: String, continuation: Boolean) {
            val turn = entry.turn
            if (turn == null) {
                val envelope = entry.envelope ?: return
                if (entry.kind == "narration" || ConversationVisibility.weakNoticeEvent(envelope)) result += UiEntry.Notice(key, messageText(envelope).ifBlank { envelope.type })
                else message(envelope, "$key:message", continuation = continuation)
                return
            }
            message(turn.request, "$key:request", continuation = continuation)
            val terminal = turn.terminal
            if (terminal != null) {
                val text = messageText(terminal).ifBlank { if (terminal.status == "failed") "处理失败" else "已完成" }
                message(terminal, "$key:answer", text, if (terminal.status == "failed") MessageStatus.Failed else null)
            } else if (!newerUnloaded) {
                val latest = turn.provisional.lastOrNull()?.envelope
                if (latest != null) {
                    val textStages = turn.provisional.mapNotNull { (it.envelope.body["process"] as? JsonObject)?.takeIf { p -> p["kind"].string() == "stage" && p["stage"].string() == "text" }?.get("text").string().takeIf(String::isNotBlank) }
                    val process = latest.body["process"] as? JsonObject
                    message(latest, "$key:answer", textStages.joinToString("\n\n"),
                        if (turn.latestCoreStatus == "processing") MessageStatus.Processing else MessageStatus.Queued,
                        process?.get("text").string().takeIf { it.isNotBlank() } ?: process?.get("stage").string().takeIf { it.isNotBlank() })
                }
            }
            entry.thread.forEach { turn(it, "$key:${it.id}", false) }
        }
        turn(item.entry, item.key, item.continuation)
        return result
    }

    private fun time(value: Long) = if (value <= 0) "" else clock.format(Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()))
}

fun messageText(envelope: Envelope): String {
    val body = envelope.body
    body["text"].string().takeIf(String::isNotBlank)?.let { return it }
    body["body"].string().takeIf(String::isNotBlank)?.let { return it }
    body["message"].string().takeIf(String::isNotBlank)?.let { return it }
    val content = body.objects("content").filter { it["type"].string() == "text" }.map { it["text"].string() }.filter(String::isNotBlank)
    if (content.isNotEmpty()) return content.joinToString("\n\n")
    if (envelope.status == "failed") return body["detail"].string().ifEmpty { body["reason"].string().ifEmpty { body["error_code"].string() } }
    val result = JsonObject(body.filterKeys { it !in ConversationVisibility.terminalTransportFields })
    return if (result.isEmpty()) "" else "```json\n$result\n```"
}

internal fun JsonObject.objects(key: String) = (this[key] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
internal fun JsonObject.longValue(key: String) = (this[key] as? JsonPrimitive)?.longOrNull ?: 0L

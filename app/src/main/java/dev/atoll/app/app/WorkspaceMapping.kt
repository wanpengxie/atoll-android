package dev.atoll.app.app

import dev.atoll.app.core.Envelope
import dev.atoll.app.core.string
import dev.atoll.app.model.*
import dev.atoll.app.ui.screens.*
import dev.atoll.app.ui.components.ResultViewState
import dev.atoll.app.ui.screens.TimelineEntry as UiEntry
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class ChannelRecord(val id: String, val name: String, val parent: String?, val qualifiedName: String, val type: String = "group", val description: String = "", val open: Boolean? = null)
data class ActorRecord(val id: String, val name: String, val kind: String, val body: String = "")

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
    else ActorRecord(id, declared["name"].string().ifEmpty { id }, declared["kind"].string().ifEmpty { id.substringBefore(':') }, declared["body"]?.toString().orEmpty())
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
        fun message(envelope: Envelope, key: String, text: String = messageText(envelope), status: MessageStatus? = null, progress: String? = null, continuation: Boolean = false, resultState: ResultViewState? = null) {
            result += UiEntry.Message(MessageViewState(key, names[envelope.senderId] ?: envelope.senderId, text,
                time(envelope.tsReceived.takeIf { it > 0 } ?: envelope.ts), envelope.senderKind == "agent", envelope.senderId == self,
                continuation, status, progress, envelope.audience.takeIf { envelope.kind == "request" }?.joinToString("、") { names[it] ?: it }, result = resultState))
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
            val streamed = turn.provisional.mapNotNull { row ->
                (row.envelope.body["process"] as? JsonObject)?.takeIf { it["kind"].string()=="stage" && it["stage"].string()=="text" }
                    ?.get("text").string().takeIf(String::isNotBlank)
            }
            val superseded = terminal?.body?.let { it["merged_into"].string().isNotEmpty() || it["preempted_by"].string().isNotEmpty() } == true
            if (terminal != null) {
                val interrupted = terminal.status=="failed" && (terminal.body["error_code"].string()=="interrupted" || terminal.body["reason"].string()=="interrupted")
                val answer = when {
                    turn.terminalClosureOnly -> MappedResult("终态详情不可用，请刷新或重新进入频道")
                    interrupted -> MappedResult("✗ 已停止 · 发消息即继续")
                    superseded -> MappedResult()
                    else -> terminalResult(terminal, turn.request.type)
                }
                val finalText = terminal.body["text"].string().trim()
                val tail = streamed.lastOrNull()?.trim()?.removeSuffix("…[truncated]").orEmpty()
                val visible = if (!superseded && !interrupted && finalText.isNotEmpty() && tail.isNotEmpty() && finalText.startsWith(tail)) streamed.dropLast(1) else streamed
                val text = (visible + answer.text).filter(String::isNotBlank).joinToString("\n\n")
                if (!superseded || text.isNotEmpty() || turn.terminalClosureOnly)
                    message(terminal, "$key:answer", text, if (terminal.status=="failed" && !interrupted && !superseded) MessageStatus.Failed else null, resultState=answer.result)
            } else if (!newerUnloaded) {
                val latest = turn.provisional.lastOrNull()?.envelope
                if (latest != null) {
                    val process = latest.body["process"] as? JsonObject
                    message(latest, "$key:answer", streamed.joinToString("\n\n"),
                        if (turn.latestCoreStatus == "processing") MessageStatus.Processing else MessageStatus.Queued,
                        process?.get("text").string().takeIf { it.isNotBlank() } ?: process?.get("stage").string().takeIf { it.isNotBlank() })
                }
            }
            if (!superseded) entry.thread.forEach { turn(it, "$key:${it.id}", false) }
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

private data class MappedResult(val text: String = "", val result: ResultViewState? = null)

/** Match web StructuredResult; protocol bookkeeping never becomes business JSON. */
private fun terminalResult(envelope: Envelope, requestType: String): MappedResult {
    val body = envelope.body
    val business = JsonObject(body.filterKeys { it !in ConversationVisibility.terminalTransportFields })
    if (envelope.status == "failed") {
        val code = if (body["cancelled"] == JsonPrimitive(true)) "cancelled" else body["error_code"].string().ifEmpty { body["reason"].string() }
        return MappedResult(result = ResultViewState(errorCode = code, detail = body["detail"].string(),
            errorData = business.takeIf { it.isNotEmpty() }))
    }
    if ("text" in body) {
        val text = body["text"].string()
        if (text.isEmpty()) return MappedResult(result = ResultViewState(emptyText = true))
        val parsed = if (text.trimStart().firstOrNull() in listOf('{', '[')) runCatching { Json.parseToJsonElement(text) }.getOrNull() else null
        return if (parsed is JsonObject || parsed is JsonArray) MappedResult(result = ResultViewState(parsed, "JSON 结果")) else MappedResult(text)
    }
    if (body.objects("content").any { it["type"].string() in setOf("text", "resource_link") })
        return MappedResult(body.objects("content").filter { it["type"].string() == "text" }.map { it["text"].string().trim() }.filter(String::isNotEmpty).joinToString("\n\n"))
    if (business.isNotEmpty()) {
        val title = if (requestType == "actor.describe" || "words" in body && "class" in body)
            body["class"].string().takeIf(String::isNotEmpty)?.let { "$it 的能力" } ?: "Actor 能力" else "结构化结果"
        return MappedResult(result = ResultViewState(business, title))
    }
    return MappedResult(result = ResultViewState(acknowledgement = true))
}

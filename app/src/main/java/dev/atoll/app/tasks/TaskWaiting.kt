package dev.atoll.app.tasks

import dev.atoll.app.compose.LocalSubmission
import dev.atoll.app.compose.SendState
import dev.atoll.app.core.*
import dev.atoll.app.model.*
import kotlinx.serialization.json.*

fun selectWaitingFacts(source: TimelineSource, pending: List<LocalSubmission> = emptyList(), actionFacts: Map<String,List<String>> = emptyMap(), targetAuthority: JsonObject? = null): List<WaitingFact> =
    selectWaitingFacts(timelineTurns(source.timeline), source.channelId, pending, actionFacts, targetAuthority)
fun selectWaitingFacts(snapshot: TimelineSnapshot, pending: List<LocalSubmission> = emptyList(), actionFacts: Map<String,List<String>> = emptyMap(), targetAuthority: JsonObject? = null): List<WaitingFact> =
    selectWaitingFacts((timelineTurns(snapshot.items.map { it.entry }) + snapshot.waitingTurns).distinctBy { it.requestId }, snapshot.channelId, pending, actionFacts, targetAuthority)
private fun selectWaitingFacts(turns: List<Turn>, channel: String, pending: List<LocalSubmission>, actionFacts: Map<String,List<String>>, authority: JsonObject?): List<WaitingFact> {
    if (channel.isEmpty()) return emptyList()
    val canonical = turns.map { it.requestId }.toSet()
    val rows = turns.filter { it.terminal == null }.mapNotNull { turn ->
        val frame = turn.provisional.sortedByDescending { it.seq }.map { it.envelope.body }.firstOrNull { it.text("status") in setOf("queued","processing") && it["controls"] is JsonArray } ?: return@mapNotNull null
        val request = turn.request; val id = turn.requestId; val key = "waiting:$channel:$id"
        val declared = frame.array("controls").mapNotNull { (it as? JsonObject)?.text("word")?.takeIf(String::isNotEmpty) }
        val actions = (declared + actionFacts[key].orEmpty() + actionFacts[id].orEmpty()).filter { it.isNotEmpty() }.distinct()
        WaitingFact(key,id,channel,listOf("title","text","detail","description").firstNotNullOfOrNull { request.body.text(it).takeIf(String::isNotEmpty) }?.trim() ?: "排队指令",frame.text("status"),request.audience.firstOrNull().orEmpty(),actions,
            request.ts,turn.provisional.lastOrNull()?.envelope?.ts ?: request.ts,buildJsonObject { put("kind","canonical");put("requestId",id);put("seq",turn.requestSeq) },authority,turn)
    }.toMutableList()
    pending.filter { it.channelId == channel && it.messageId.isNotEmpty() && it.messageId !in canonical && it.state in setOf(SendState.QUEUED,SendState.TRANSMITTING,SendState.ACCEPTED,SendState.DELAYED,SendState.UNCERTAIN) && it.frame.text("msg_type") in setOf("agent.ask","agent.queue") }.forEach { row ->
        val key = "waiting:$channel:${row.messageId}"
        rows += WaitingFact(key,row.messageId,channel,row.text.ifEmpty { row.frame.objectAt("payload").text("text").ifEmpty { row.frame.text("msg_type").ifEmpty { "排队指令" } } },row.state.name.lowercase(),
            row.frame.array("audience").firstOrNull().string(),(actionFacts[key].orEmpty() + actionFacts[row.messageId].orEmpty()).distinct(),row.createdAt,row.updatedAt,
            buildJsonObject { put("kind","submission");put("messageId",row.messageId) },authority,submission=row)
    }
    return rows.sortedWith(compareBy<WaitingFact> { it.createdAt }.thenBy { it.key })
}
fun waitingControlSubmission(item: WaitingFact, type: String, all: Boolean = false, targetLabel: String = ""): TaskSubmission {
    require(item.channelId.isNotBlank()) { "频道不能为空" };require(item.actorId.isNotBlank()) { "等待区控制目标不能为空" };require(item.id.isNotBlank()) { "等待区请求不能为空" }
    require(type in item.actions) { "该控制词未由当前账本声明" }
    val payload = when (type) {
        "agent.interrupt" -> Envelope.EMPTY
        "agent.steer" -> buildJsonObject { if (all) put("all",true) else put("target",item.id) }
        else -> throw IllegalArgumentException("该控制词尚无可靠的请求构造器")
    }
    return TaskSubmission(item.channelId,"",type,listOf(item.actorId),targetLabel.ifEmpty { item.actorId },payload,buildJsonObject {
        put("source","feature");put("targetAuthority",item.targetAuthority ?: JsonNull)
        item.turn?.let { turn -> put("turn",buildJsonObject {
            put("requestId",turn.requestId);put("requestType",turn.request.type);put("audience",JsonArray(turn.request.audience.map(::JsonPrimitive)))
            put("terminal",turn.terminal != null);put("local",false);put("status",item.state)
            put("controls",JsonArray(item.actions.filter { it.startsWith("agent.") }.map(::JsonPrimitive)));put("actorId",item.actorId)
        }) }
    })
}

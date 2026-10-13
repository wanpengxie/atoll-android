package dev.atoll.app.tasks

import dev.atoll.app.compose.LocalSubmission
import dev.atoll.app.compose.SendState
import dev.atoll.app.core.*
import dev.atoll.app.model.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.text.Collator
import java.util.Locale

object TaskAction {
    const val APPROVE = "approval.approve"
    const val REJECT = "approval.reject"
    const val RETRY = "recovery.retry"
    const val CANCEL = "request.cancel"
    const val CANCEL_TIMER = "automation.cancel"
}
data class TaskFact(
    val key: String, val id: String, val channelId: String, val kind: String, val title: String,
    val state: String = "active", val description: String = "", val assigneeActorIds: List<String> = emptyList(),
    val ownerId: String = "", val requesterActorId: String = ownerId, val nativeId: String = id,
    val requestId: String = "", val dueAt: String = "", val priority: String = "normal",
    val relatedArtifacts: List<JsonElement> = emptyList(), val createdAt: Long = 0, val updatedAt: Long = createdAt,
    val actions: List<String> = emptyList(), val actionableBySelf: Boolean = false, val needsYou: Boolean = false,
    val waitingFor: String = "", val provenance: String = "ledger", val localScope: String = "", val group: String = "",
    val diagnostic: JsonObject = Envelope.EMPTY, val source: JsonObject = Envelope.EMPTY,
    val turn: Turn? = null, val submission: LocalSubmission? = null,
)
@Serializable data class AutomationRecord(
    val timerId: String, val channelId: String, val durationMs: Long, val msgType: String,
    val payload: JsonObject = JsonObject(emptyMap()), val createdAt: Long, val dueAt: Long = createdAt + durationMs,
    val state: String = "scheduled", val firedAt: Long = 0, val cancelledAt: Long = 0,
)
data class TaskProvider(val actorId: String, val name: String)
data class TaskSubmission(val channelId: String, val text: String, val msgType: String, val audience: List<String>, val targetLabel: String, val payload: JsonObject, val controlContext: JsonObject? = null)
data class WaitingFact(
    val key: String, val id: String, val channelId: String, val title: String, val state: String,
    val actorId: String, val actions: List<String>, val createdAt: Long, val updatedAt: Long,
    val source: JsonObject, val targetAuthority: JsonObject? = null, val turn: Turn? = null,
    val submission: LocalSubmission? = null,
) { val requestId: String get() = id }
data class TaskPage(val items: List<TaskFact>, val page: Int, val pageCount: Int, val start: Int, val end: Int, val total: Int, val hasOlder: Boolean, val hasNewer: Boolean)

internal fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
internal fun JsonObject.number(key: String): Long = (get(key) as? JsonPrimitive)?.longOrNull ?: 0
internal fun JsonObject.array(key: String): JsonArray = get(key) as? JsonArray ?: JsonArray(emptyList())
internal fun JsonObject.objectAt(key: String): JsonObject = get(key) as? JsonObject ?: Envelope.EMPTY
private val activeStates = setOf("active", "waiting", "blocked", "uncertain", "queued", "running", "held")
private val finishedStates = setOf("completed", "failed", "cancelled", "expired")
private val controlTypes = setOf("actor.describe", "agent.steer", "agent.queue", "agent.interrupt", "agent.dismiss", "agent.hold", "agent.unhold", "agent.replace", "agent.hold_expired", "agent.fork", "agent.compact", "agent.new", "agent.select", "agent.options", "agent.context")
private fun terminalState(turn: Turn): String = when {
    turn.terminal == null -> "active"
    turn.terminalClosureOnly -> "uncertain"
    turn.terminal.status == "completed" -> "completed"
    turn.terminal.body.text("reason").ifEmpty { turn.terminal.body.text("error_code") } in setOf("cancelled", "interrupted") -> "cancelled"
    else -> "failed"
}
private fun normalizeTaskState(state: String): String = when (state) {
    "active", "waiting", "blocked", "uncertain", "completed", "failed", "cancelled", "expired" -> state
    "done", "closed", "resolved" -> "completed"
    else -> "active"
}
private fun terminalPayload(turn: Turn): JsonObject = if (turn.terminalClosureOnly) Envelope.EMPTY else turn.terminal?.body ?: Envelope.EMPTY
private fun actions(facts: Map<String, List<String>>, keys: List<String>, source: List<String> = emptyList()): List<String> = (source + keys.flatMap { facts[it].orEmpty() }).filter { it.isNotEmpty() }.distinct()
private fun title(request: Envelope, fallback: String): String = listOf("title", "text", "detail", "description").firstNotNullOfOrNull { request.body.text(it).takeIf(String::isNotEmpty) }?.trim() ?: fallback
internal fun timelineTurns(entries: Collection<TimelineEntry>): List<Turn> {
    val result = linkedMapOf<String, Turn>()
    fun visit(entry: TimelineEntry) {
        entry.turn?.takeIf { entry.kind == "turn" && it.requestId.isNotEmpty() }?.let { result[it.requestId] = it }
        entry.thread.forEach(::visit)
    }
    entries.forEach(::visit); return result.values.toList()
}
private fun anchor(channelId: String, turn: Turn): JsonObject = buildJsonObject {
    put("channelId", channelId); put("view", "dynamic"); put("objectType", "turn")
    put("objectId", turn.requestId); put("requestId", turn.requestId); put("seq", turn.requestSeq)
}

/** Pure web feature-tasks projection; the same Room window is used for foreground/background. */
fun selectTaskFacts(
    source: TimelineSource, channelId: String = source.channelId, selfId: String = "", now: Long = 0,
    pending: List<LocalSubmission> = emptyList(), automationRecords: List<AutomationRecord> = emptyList(),
    actionFacts: Map<String, List<String>> = emptyMap(),
): List<TaskFact> = selectTaskFacts(timelineTurns(source.timeline), channelId, selfId, now, pending, automationRecords, actionFacts)

fun selectTaskFacts(
    snapshot: TimelineSnapshot, channelId: String = snapshot.channelId, selfId: String = "", now: Long = 0,
    pending: List<LocalSubmission> = emptyList(), automationRecords: List<AutomationRecord> = emptyList(),
    actionFacts: Map<String, List<String>> = emptyMap(),
): List<TaskFact> = selectTaskFacts((timelineTurns(snapshot.items.map { it.entry }) + snapshot.waitingTurns).distinctBy { it.requestId }, channelId, selfId, now, pending, automationRecords, actionFacts)

internal fun selectTaskFacts(turns: List<Turn>, channelId: String, selfId: String, now: Long, pending: List<LocalSubmission>, automationRecords: List<AutomationRecord>, actionFacts: Map<String, List<String>>): List<TaskFact> {
    if (channelId.isEmpty()) return emptyList()
    val facts = mutableListOf<TaskFact>()
    val canonical = turns.map { it.requestId }.toSet()
    for (turn in turns) {
        val request = turn.request; val body = request.body
        val terminal = terminalPayload(turn)
        val value = terminal["value"] as? JsonObject ?: terminal
        if (request.type == "task.create" && (value.text("task_id").isNotEmpty() || value.text("id").isNotEmpty())) {
            val id = value.text("task_id").ifEmpty { value.text("id").ifEmpty { turn.requestId } }
            val key = "task:$channelId:$id"
            val source = body.objectAt("source").takeIf { it.text("channelId") == channelId && it.text("objectId").isNotEmpty() } ?: anchor(channelId, turn)
            facts += TaskFact(key, id, channelId, "task", value.text("title").ifEmpty { body.text("title").ifEmpty { body.text("description").ifEmpty { "未命名任务" } } },
                normalizeTaskState(value.text("state").ifEmpty { value.text("status").ifEmpty { terminalState(turn) } }),
                description = body.text("description"), assigneeActorIds = listOf(value.text("assignee").ifEmpty { request.audience.firstOrNull().orEmpty() }).filter { it.isNotEmpty() },
                ownerId = request.senderId, dueAt = value.text("due_at").ifEmpty { body.text("due_at") }, priority = value.text("priority").ifEmpty { body.text("priority").ifEmpty { "normal" } },
                relatedArtifacts = value.array("related_artifacts").toList(), createdAt = request.ts, updatedAt = turn.terminal?.ts ?: request.ts,
                actions = actions(actionFacts, listOf(key, id, turn.requestId), value.array("actions").mapNotNull { it.string().takeIf(String::isNotEmpty) }),
                diagnostic = buildJsonObject { put("providerActorId", request.audience.firstOrNull().orEmpty()); put("rawStatus", value.text("status").ifEmpty { value.text("state") }) }, source = source, turn = turn)
        } else if (request.type in setOf("human.approve", "human.ask")) {
            val id = turn.requestId; val key = "approval:$channelId:$id"
            val state = if (turn.terminal == null && now > 0 && request.expiresAt > 0 && request.expiresAt <= now) "expired" else if (turn.terminal != null) terminalState(turn) else "waiting"
            val needs = state == "waiting" && selfId in request.audience
            facts += TaskFact(key, id, channelId, "approval", title(request, "待处理请求"), state, assigneeActorIds = request.audience,
                ownerId = request.senderId, needsYou = needs, actionableBySelf = needs, waitingFor = if (state == "waiting") "等待决定" else "",
                dueAt = request.expiresAt.takeIf { it > 0 }?.toString().orEmpty(), priority = body.text("priority").ifEmpty { "high" }, createdAt = request.ts, updatedAt = turn.terminal?.ts ?: request.ts,
                actions = actions(actionFacts, listOf(key, id)), diagnostic = buildJsonObject { put("requestType", request.type); put("impact", body.text("impact")) }, source = anchor(channelId, turn), turn = turn)
        } else if (request.type !in controlTypes && !request.type.startsWith("system.")
            && !(request.type=="human.message" && request.audience==listOf(request.senderId))) {
            val id = turn.requestId; val key = "agent_run:$channelId:$id"
            val unavailable = turn.terminalClosureOnly && (request.type == "task.create" || turn.terminal?.status == "failed")
            val phase = if (turn.terminal == null) "waiting" else if (turn.terminalClosureOnly) "unavailable" else "available"
            facts += TaskFact(key, id, channelId, "agent_run", title(request, request.type.ifEmpty { "未命名工作" }), if (unavailable) "uncertain" else terminalState(turn),
                requestId = id, assigneeActorIds = request.audience, ownerId = request.senderId,
                waitingFor = if (unavailable) "终态详情不可用，请刷新或重新进入频道" else turn.latestCoreStatus,
                createdAt = request.ts, updatedAt = turn.terminal?.ts ?: turn.provisional.lastOrNull()?.envelope?.ts ?: request.ts,
                actions = actions(actionFacts, listOf(key,id)), actionableBySelf = turn.terminal == null && request.senderId == selfId,
                diagnostic = buildJsonObject { put("requestType",request.type); put("requestId",id);put("resultUnavailable", unavailable);put("resultPhase",phase) }, source = anchor(channelId,turn), turn = turn)
        }
    }
    pending.filter { it.channelId == channelId && it.state in setOf(SendState.UNCERTAIN, SendState.REJECTED) && it.messageId.isNotEmpty() && it.messageId !in canonical }.forEach { row ->
        val key = "recovery:$channelId:${row.messageId}"
        facts += TaskFact(key,row.messageId,channelId,"recovery",row.text.ifEmpty { row.frame.text("msg_type").ifEmpty { "待确认的提交" } }, if (row.state == SendState.UNCERTAIN) "uncertain" else "failed",
            assigneeActorIds = listOf(selfId).filter { it.isNotEmpty() }, ownerId = selfId, needsYou = selfId.isNotEmpty(), actionableBySelf = selfId.isNotEmpty(),
            waitingFor = row.error.ifEmpty { if (row.state == SendState.UNCERTAIN) "等待频道账本确认" else "等待安全重试" }, createdAt = row.createdAt, updatedAt = row.updatedAt,
            actions = actions(actionFacts,listOf(key,row.messageId)), submission = row, provenance = "local_durable", localScope = "this_device",
            diagnostic = buildJsonObject { put("submissionKey",row.messageId);put("error",row.error) }, source = buildJsonObject { put("channelId",channelId);put("view","dynamic");put("objectType","message");put("objectId",row.messageId);put("messageId",row.messageId) })
    }
    automationRecords.filter { it.channelId == channelId && it.timerId.isNotEmpty() }.forEach { row ->
        val key = "automation:$channelId:${row.timerId}"
        val state = when (row.state) { "scheduled" -> "waiting"; "fired" -> "completed"; else -> "cancelled" }
        val declared = actions(actionFacts,listOf(key,row.timerId))
        facts += TaskFact(key,row.timerId,channelId,"automation",row.payload.text("text").ifEmpty { row.msgType.ifEmpty { "自动动作" } },state,
            ownerId = selfId, dueAt = row.dueAt.toString(), waitingFor = if (state == "waiting") "等待本设备定时触发" else "", createdAt = row.createdAt,
            updatedAt = row.firedAt.takeIf { it > 0 } ?: row.cancelledAt.takeIf { it > 0 } ?: row.createdAt,
            actions = if (declared.isEmpty() && state == "waiting") listOf(TaskAction.CANCEL_TIMER) else declared, actionableBySelf = state == "waiting", provenance = "local_durable", localScope = "this_device",
            diagnostic = buildJsonObject { put("msgType",row.msgType);put("payload",row.payload);put("durationMs",row.durationMs) }, source = buildJsonObject { put("channelId",channelId);put("view","tasks");put("objectType","automation");put("objectId",row.timerId) })
    }
    return facts.sortedWith(compareByDescending<TaskFact> { it.updatedAt }.thenBy { it.key })
}

fun selectTaskProviders(capabilities: Map<String, Set<String>>, roster: Map<String,String>): List<TaskProvider> {
    val collation = Collator.getInstance(Locale.CHINA)
    return capabilities.filterValues { "task.create" in it }.keys.mapNotNull { id -> roster[id]?.let { TaskProvider(id,it) } }.sortedWith { a,b -> collation.compare(a.name,b.name) }
}
fun filterTasks(items: List<TaskFact>, scope: String = "me", status: String = "active", kind: String = "all", selfId: String = ""): List<TaskFact> = items.filter { item ->
    (kind == "all" || item.kind == kind) && when (status) { "active" -> item.state in activeStates; "completed" -> item.state in setOf("completed","cancelled","expired"); "failed" -> item.state == "failed"; else -> true } &&
        (scope != "me" || item.needsYou || item.actionableBySelf || item.ownerId == selfId || selfId in item.assigneeActorIds || item.kind in setOf("automation","recovery"))
}.sortedWith(compareBy<TaskFact> { when (it.priority) { "urgent" -> 0; "high" -> 1; else -> 2 } }.thenByDescending { it.actionableBySelf || it.needsYou }.thenByDescending { it.updatedAt }.thenBy { it.key })
fun taskGroup(item: TaskFact): String = item.group.takeIf { it in setOf("needs_you","active","recovery","automation","history") } ?: when {
    item.needsYou -> "needs_you"; item.kind == "recovery" -> "recovery"; item.kind == "automation" -> "automation"; item.state in finishedStates -> "history"; else -> "active"
}
fun taskPage(items: List<TaskFact>, page: Int = 0, size: Int = 120): TaskPage {
    val limit = if (size == 0) 120 else size.coerceAtLeast(1)
    val count = ((items.size + limit - 1) / limit).coerceAtLeast(1)
    val safe = page.coerceIn(0,count - 1); val end = (items.size - safe * limit).coerceAtLeast(0); val start = (end - limit).coerceAtLeast(0)
    return TaskPage(items.subList(start,end).toList(),safe,count,start,end,items.size,start > 0,end < items.size)
}
fun createTaskSubmission(channelId: String, providerId: String, title: String, description: String = "", dueAt: String = "", source: JsonObject? = null, providerName: String = ""): TaskSubmission {
    require(channelId.isNotBlank()) { "频道不能为空" };require(providerId.isNotBlank()) { "任务执行者不能为空" };require(title.isNotBlank()) { "任务内容不能为空" }
    return TaskSubmission(channelId.trim(),title.trim(),"task.create",listOf(providerId.trim()),providerName.ifEmpty { providerId },buildJsonObject {
        put("title",title.trim());if (description.isNotBlank()) put("description",description.trim());if (dueAt.isNotEmpty()) put("due_at",dueAt)
        source?.let { put("source",JsonObject(it + ("channelId" to JsonPrimitive(channelId.trim())))) }
    })
}

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

class TimelineMapper(private val now: () -> Long = System::currentTimeMillis) {
    private val clock = DateTimeFormatter.ofPattern("HH:mm")
    private data class Cached(val revision: String, val continuation: Boolean, val newerUnloaded: Boolean, val latest: Boolean, val lifecycleKey: String, val value: List<UiEntry>)
    private val cache = mutableMapOf<String, Cached>()
    private var roster = emptyList<ActorRecord>()
    private var selfKey = ""

    fun map(snapshot: TimelineSnapshot, selfId: String, actors: List<ActorRecord>, newerUnloaded: Boolean = false, source: TimelineSource? = null): List<UiEntry> {
        val names = actors.associate { it.id to it.name }
        if (actors != roster || selfKey != selfId) { cache.clear(); roster = actors.toList(); selfKey = selfId }
        val restarts = source?.let(RequestLifecycle::memberRestarts).orEmpty()
        val instant = now()
        val entries = mutableListOf<UiEntry>()
        var day = ""
        val retained = mutableSetOf<String>()
        for (item in snapshot.items) {
            retained += item.key
            val latest = item.entry.id == snapshot.currentEntryCandidate?.id
            val lifecycleKey = (listOf(item.entry) + item.entry.thread).mapNotNull { it.turn }.mapNotNull { turn ->
                RequestLifecycle.lostReason(turn,restarts,instant).takeIf(String::isNotEmpty)?.let { "${turn.requestId}:$it" }
            }.joinToString("|")
            val previous = cache[item.key]
            val value = if (previous?.revision == item.contentRevision && previous.continuation == item.continuation && previous.newerUnloaded == newerUnloaded && previous.latest == latest && previous.lifecycleKey == lifecycleKey) previous.value
            else render(item, selfId, names, newerUnloaded, source, restarts, instant).map { entry ->
                if (entry is UiEntry.Message) UiEntry.Message(entry.value.copy(latest=latest)) else entry
            }.also { cache[item.key] = Cached(item.contentRevision, item.continuation, newerUnloaded, latest, lifecycleKey, it) }
            if (value.isNotEmpty() && item.dayKey.isNotEmpty() && item.dayKey != day) { day=item.dayKey;entries+=UiEntry.Day("day:$day:${item.key}",day) }
            entries += value
        }
        cache.keys.retainAll(retained)
        return entries
    }

    private fun render(item: PresentationItem, self: String, names: Map<String, String>, newerUnloaded: Boolean,
        source: TimelineSource?, restarts: Map<String,Long>, instant: Long): List<UiEntry> {
        if (item.entry.kind=="narration") return source?.narration.orEmpty().filter { it.seq in item.seqLow..item.seqHigh }
            .mapNotNull { row -> narrationText(row.envelope,names)?.let { UiEntry.Notice("${item.key}:${row.envelope.id}",it) } }.orEmpty()
        val result = mutableListOf<UiEntry>()
        val tasks = subTaskViews(item.entry.subTasks,names)
        val tasksByRequest = tasks.groupBy { it.requestId.orEmpty() }
        fun message(envelope: Envelope, key: String, text: String = messageText(envelope), status: MessageStatus? = null, progress: String? = null, continuation: Boolean = false, resultState: ResultViewState? = null, trailState: ProgressTrailViewState? = null, taskStates: List<SubTaskViewState> = emptyList()) {
            result += UiEntry.Message(MessageViewState(key, names[envelope.senderId] ?: envelope.senderId, text,
                time(envelope.tsReceived.takeIf { it > 0 } ?: envelope.ts), envelope.senderKind == "agent", envelope.senderId == self,
                continuation, status, progress, envelope.audience.takeIf { envelope.kind == "request" }?.joinToString("、") { names[it] ?: it }, result = resultState, trail = trailState, subTasks = taskStates))
        }
        fun turn(entry: dev.atoll.app.model.TimelineEntry, key: String, continuation: Boolean) {
            val turn = entry.turn
            if (turn == null) {
                val envelope = entry.envelope ?: return
                if (entry.kind == "narration" || ConversationVisibility.weakNoticeEvent(envelope)) result += UiEntry.Notice(key, weakNoticeText(envelope,names,time(envelope.ts)))
                else if(envelope.type=="agent.task") message(envelope,"$key:message",text="",continuation=continuation,
                    taskStates=tasks.ifEmpty { subTaskViews(listOf(envelope),names) })
                else if(envelope.type=="agent.provider.run") {
                    val summary=envelope.body["task_summary"].string()
                    val returned=summary.takeIf(String::isNotBlank)?.let { SubTaskViewState("provider:${envelope.id}",
                        dev.atoll.app.tasks.compactWorkTitle(it).replace(Regex("^#+\\s*"),"").replace(Regex("[*_`]"),"").take(80),
                        names[envelope.senderId] ?: envelope.senderId,SubTaskKind.Other,SubTaskPhase.Completed,envelope.ts,
                        requestId=envelope.parentId.takeIf(String::isNotEmpty),result=it) }
                    message(envelope,"$key:message",text=prose(envelope.body),continuation=continuation,taskStates=listOfNotNull(returned))
                } else message(envelope, "$key:message", continuation = continuation)
                return
            }
            val approval = turn.request.type in setOf("human.ask","human.approve") && self in turn.request.audience
            val requestText = if(approval) listOf(turn.request.body["title"].string().ifEmpty { prose(turn.request.body) },
                turn.request.body["detail"].string(),turn.request.body["impact"].string().takeIf(String::isNotEmpty)?.let { "影响：$it" }.orEmpty())
                .filter(String::isNotBlank).joinToString("\n\n") else messageText(turn.request)
            message(turn.request, "$key:request", text=requestText, continuation = continuation)
            val terminal = turn.terminal
            val ownedTasks = tasksByRequest[turn.requestId].orEmpty()
            val streamed = turn.provisional.mapNotNull { row ->
                (row.envelope.body["process"] as? JsonObject)?.takeIf { it["kind"].string()=="stage" && it["stage"].string()=="text" }
                    ?.get("text").string().takeIf(String::isNotBlank)
            }
            val calls = entry.thread.filter { child -> child.turn?.request?.let { it.senderKind!="human" &&
                (it.type.startsWith("system.") || it.audience.any { audience -> audience.startsWith("tool:") || audience=="system" }) } == true }
            val callIds = calls.mapNotNull { it.turn?.requestId }.toHashSet()
            val lostReason = RequestLifecycle.lostReason(turn,restarts,instant)
            val trail = progressTrail(turn,calls,terminal==null && lostReason.isEmpty() && !newerUnloaded,::time)
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
                val receipt = if(approval) "已回执 · 处理者："+(names[terminal.senderId] ?: terminal.senderId)+terminal.body["decision"].string().takeIf(String::isNotEmpty)?.let { " · $it" }.orEmpty() else ""
                val text = (listOf(receipt) + visible + answer.text).filter(String::isNotBlank).joinToString("\n\n")
                if (!superseded || text.isNotEmpty() || turn.terminalClosureOnly)
                    message(terminal, "$key:answer", text, if (terminal.status=="failed" && !interrupted && !superseded) MessageStatus.Failed else null, resultState=answer.result,trailState=trail.takeUnless { superseded },taskStates=if(superseded) emptyList() else ownedTasks)
            } else if (!newerUnloaded) {
                val latest = turn.provisional.lastOrNull()?.envelope
                val lost = lostReason
                if (latest != null || lost.isNotEmpty() || ownedTasks.isNotEmpty()) {
                    val response = latest ?: Envelope(JsonObject(turn.request.json + mapOf("kind" to JsonPrimitive("response"),
                        "sender" to buildJsonObject { put("id",turn.request.audience.firstOrNull().orEmpty());put("kind","agent") })))
                    val process = latest?.body?.get("process") as? JsonObject
                    val label = when (lost) { "restart" -> "未完成 · Agent 已重启";"expired" -> "未完成 · 已过期";else -> "" }
                    message(response, "$key:answer", (streamed + label).filter(String::isNotBlank).joinToString("\n\n"),
                        if (lost.isNotEmpty() || latest==null) null else if (turn.latestCoreStatus=="processing") MessageStatus.Processing else MessageStatus.Queued,
                        process?.takeUnless { it["kind"].string()=="stage" && it["stage"].string()=="text" }?.get("text").string().takeIf(String::isNotBlank),
                        trailState=trail,taskStates=ownedTasks)
                }
            }
            if (!superseded) entry.thread.filterNot { it.turn?.requestId in callIds }.forEach { turn(it, "$key:${it.id}", false) }
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

private fun prose(body: JsonObject): String {
    for (key in listOf("new_text","text","body","answer","message","content","prompt")) body[key].string().takeIf(String::isNotBlank)?.let { return it }
    (body["input"] as? JsonObject)?.get("text").string().takeIf(String::isNotBlank)?.let { return it }
    return body.objects("content").filter { it["type"].string()=="text" }.map { it["text"].string() }.filter(String::isNotBlank).joinToString("\n\n")
}
private fun weakNoticeText(envelope: Envelope,names: Map<String,String>,time: String): String {
    val line=prose(envelope.body).lineSequence().map(String::trim).firstOrNull(String::isNotEmpty)
        ?: when(envelope.type) { "actor.initializing" -> "正在启动";"actor.ready" -> "已就绪";else -> "后台状态已更新" }
    return (names[envelope.senderId] ?: envelope.senderId) + " " + dev.atoll.app.tasks.compactWorkTitle(line) + if(time.isEmpty()) "" else " · $time"
}
private fun narrationText(e: Envelope,names: Map<String,String>):String? {
    if(!e.hasBody) return null
    val body=e.body
    when(e.type) {
        "system.member.created","system.member.deleted" -> {
            val member=body["member"].string()
            if(member.isEmpty()) return "无法识别的频道活动"
            if(body["body"].string().startsWith("generated")) return null
            val name=names[member] ?: member
            return if(e.type=="system.member.created") "$name 已加入频道"
                else "$name 已离开频道"+body["reason"].string().takeIf(String::isNotEmpty)?.let { "（原因：$it）" }.orEmpty()
        }
        "system.channel.inbound" -> {
            val from=body["from_channel"].string();val type=body["request_type"].string();val local=body["local_request_id"].string()
            return if(from.isEmpty() || type.isEmpty() || local.isEmpty()) "无法识别的频道活动" else "收到来自 $from 的频道请求：$type"
        }
        "system.build.started","system.build.finished" -> {
            val objectValue=body["object"] as? JsonObject ?: return "无法识别的频道活动"
            val kind=objectValue["kind"].string();if(kind.isEmpty()) return "无法识别的频道活动"
            val label=if(kind=="member") "成员 "+listOf("name","actor_id","config_id","generated").firstNotNullOfOrNull { objectValue[it].string().takeIf(String::isNotEmpty) }.orEmpty()
                else if(kind=="channel") "频道 "+objectValue["channel_name"].string().ifEmpty { objectValue["channel"].string() } else "对象"
            val attempt=body.longValue("attempt").takeIf { it>0 } ?: 1
            val state=when(body["state"].string()) { "ready" -> "已就绪";"serving" -> "服务中";"stopped" -> "已停止：改描述或配置、或重启后才会再构建";else -> "" }
            return when {
                e.type=="system.build.started" || body["result"].string().isEmpty() -> "开始构建$label（第 $attempt 次尝试）"
                body["result"].string()=="ok" -> "${label}构建成功"+state.takeIf(String::isNotEmpty)?.let { "，$it" }.orEmpty()
                body["result"].string()=="superseded" -> "${label}的这次构建被更新的值取代"
                else -> "${label}构建失败（第 $attempt 次尝试"+state.takeIf(String::isNotEmpty)?.let { "，$it" }.orEmpty()+"）"+body["reason"].string().takeIf(String::isNotEmpty)?.let { "：$it" }.orEmpty()
            }
        }
    }
    val label=when(e.type) {
        "system.member.updated" -> "成员已更新";"system.member.create" -> "添加参与者";"system.member.restart" -> "重启参与者"
        "system.member.delete" -> "移除参与者";"system.member.list" -> "查看频道成员";"system.channel.create" -> "创建子频道"
        "system.channel.set" -> "更新频道配置";"system.channel.delete" -> "退役频道"
        else -> if(e.kind!="event" && e.type.startsWith("system.")) "系统操作" else "后台状态已更新"
    }
    return label + when(e.status) { "completed" -> "（已完成）";"failed" -> "（失败）";"processing" -> "（处理中）";"queued" -> "（排队中）";else -> "" }
}

private data class TrailRecord(val seq: Long,val value: ProcessRecordViewState)
private class ProcessBudget(var nodes: Int=128,var characters: Int=8192)
/** Visit only the documented input/output fields, within one small shared budget. */
private fun meaningfulToolValue(value: JsonElement?): Boolean = when(value) {
    null,JsonNull -> false;is JsonObject -> value.isNotEmpty();is JsonArray -> value.isNotEmpty()
    is JsonPrimitive -> !value.isString || value.content.isNotBlank()
}
private fun processValue(value: JsonElement?,budget: ProcessBudget,depth:Int=0):JsonElement? {
    if(value==null || value==JsonNull) return null
    if(budget.nodes--<=0) return JsonPrimitive("…（内容已省略）")
    if(value is JsonPrimitive) {
        if(!value.isString) return value
        val limit=minOf(value.content.length,4096,budget.characters.coerceAtLeast(0));budget.characters-=limit
        return JsonPrimitive(value.content.take(limit)+if(limit<value.content.length) "…（内容已省略）" else "")
    }
    if(depth>=3) return JsonPrimitive("…（内容已折叠）")
    return when(value) {
        is JsonArray -> JsonArray(value.take(20).map { processValue(it,budget,depth+1) ?: JsonNull })
        is JsonObject -> JsonObject(value.entries.take(20).associate { it.key.take(64) to (processValue(it.value,budget,depth+1) ?: JsonNull) })
        else -> null
    }
}
internal fun progressTrail(turn: Turn,calls: List<dev.atoll.app.model.TimelineEntry>,running: Boolean,time: (Long)->String):ProgressTrailViewState? {
    val records=mutableListOf<TrailRecord>();val tools=mutableMapOf<String,Int>()
    for(row in turn.provisional.sortedBy { it.seq }) {
        val process=row.envelope.body["process"] as? JsonObject ?: continue
        val kind=process["kind"].string();val stage=process["stage"].string()
        if(kind=="stage" && stage!="text") {
            val body=process["text"].string().take(8192)
            records+=TrailRecord(row.seq,ProcessRecordViewState("stage:${row.seq}",dev.atoll.app.tasks.compactWorkTitle(body.ifEmpty { if(stage=="thinking") "思考中…" else stage.ifEmpty { "处理中" } }),
                time(row.envelope.ts),stateOnly=body.isBlank(),body=body))
            continue
        }
        if(kind!="tool") continue
        val call=process["tool_call_id"].string().ifEmpty { row.seq.toString() }
        val phase=process["phase"].string();if(phase !in setOf("started","ended")) continue
        val before=tools[call]?.let { records[it].value }
        val budget=ProcessBudget();val input=processValue(process["input"],budget).takeIf(::meaningfulToolValue) ?: before?.input
        val output=processValue(process["output"],budget).takeIf(::meaningfulToolValue) ?: before?.output
        val body=process["detail"].string().take(8192).ifEmpty { before?.body.orEmpty() }
        val identifiers=linkedMapOf<String,String>()
        listOf("audit_id" to "审计编号","auditId" to "审计编号","tool_call_id" to "调用编号","toolCallId" to "调用编号",
            "turn_id" to "回合编号","turnId" to "回合编号","trace_id" to "跟踪编号","traceId" to "跟踪编号").forEach { (field,label) ->
            process[field].string().takeIf(String::isNotBlank)?.let { identifiers.putIfAbsent(label,it) }
        }
        identifiers["过程编号"]=row.envelope.id
        val outputText=dev.atoll.app.ui.components.boundedToolOutput((output as? JsonPrimitive)?.takeIf { it.isString }?.content ?: output?.toString().orEmpty())
        val tool=process["tool"].string().ifEmpty { "工具" }
        val line="tool: $tool "+if(phase=="started") "…" else if(process["outcome"].string()=="failed") "失败" else "完成"
        val record=TrailRecord(row.seq,ProcessRecordViewState("tool:$call",line,time(row.envelope.ts),ProcessKind.Tool,
            stateOnly=body.isBlank() && input==null && output==null,input=input,output=output,outputText=outputText,body=body,identifiers=identifiers.map { it.key to it.value }))
        val index=tools[call]
        if(index==null) { tools[call]=records.size;records+=record } else records[index]=record
    }
    for(entry in calls) {
        val call=entry.turn ?: continue
        val budget=ProcessBudget()
        val input=processValue(call.request.body,budget)
        val output=call.terminal?.takeUnless { call.terminalClosureOnly }?.body?.let { JsonObject(it.filterKeys { key -> key !in ConversationVisibility.terminalTransportFields }) }?.let { processValue(it,budget) }
        val label=if(call.terminal==null) "…" else if(call.terminal.status=="failed") "失败" else "完成"
        val outputText=dev.atoll.app.ui.components.boundedToolOutput((output as? JsonPrimitive)?.takeIf { it.isString }?.content ?: output?.toString().orEmpty())
        records+=TrailRecord(call.lastSeq,ProcessRecordViewState("call:${call.requestId}","tool: ${call.request.type} $label",
            time(call.terminal?.ts ?: call.request.ts),ProcessKind.Tool,input=input,output=output,outputText=outputText,
            body=call.terminal?.body?.get("detail").string().take(8192),identifiers=listOf("调用编号" to call.requestId)))
    }
    if(records.isEmpty()) return null
    val start=turn.provisional.firstOrNull { it.envelope.status=="processing" }?.envelope?.ts ?: turn.request.ts
    return ProgressTrailViewState(dev.atoll.app.tasks.compactWorkTitle(prose(turn.request.body)).ifEmpty { "过程记录" },
        records.sortedBy { it.seq }.map { it.value },running,start,time(start))
}

/** Same sender/task identity as the web tree; resumed calls do not create extra leaves. */
private fun subTaskViews(steps: List<Envelope>,names: Map<String,String>):List<SubTaskViewState> {
    if(steps.isEmpty()) return emptyList()
    val identity=BackgroundTasks.subTaskIdentity(steps)
    val tasks=linkedMapOf<String,SubTaskViewState>()
    for(e in steps) {
        val body=e.body;val key=identity(e);val previous=tasks[key]
        val phase=body["phase"].string();val text=body["text"].string().trim()
        val messages=if(phase=="message" && text.isNotEmpty()) previous?.messages.orEmpty()+text else previous?.messages.orEmpty()
        val result=if(phase in setOf("completed","failed") && text.isNotEmpty()) text else previous?.result.orEmpty()
        val task=SubTaskViewState(key,body["title"].string().ifEmpty { previous?.title.orEmpty().ifEmpty { "（未命名任务）" } },
            names[e.senderId] ?: e.senderId,
            if(body["kind"].string()=="shell") SubTaskKind.Shell else previous?.kind ?: SubTaskKind.Agent,
            when(phase) { "completed" -> SubTaskPhase.Completed;"failed" -> SubTaskPhase.Failed;else -> previous?.phase ?: SubTaskPhase.Running },
            if(previous==null || previous.startedAtMillis<=0) e.ts else if(e.ts>0) minOf(previous.startedAtMillis,e.ts) else previous.startedAtMillis,
            requestId=previous?.requestId ?: e.parentId.takeIf(String::isNotEmpty),
            latest=if(phase in setOf("progress","message") && text.isNotEmpty()) text.lineSequence().first().take(120) else previous?.latest.orEmpty(),
            result=result,messages=messages)
        tasks[key]=task
    }
    return tasks.values.map { task ->
        if(task.result.isNotEmpty() && task.messages.lastOrNull()?.trim()==task.result.trim()) task.copy(messages=task.messages.dropLast(1)) else task
    }
}

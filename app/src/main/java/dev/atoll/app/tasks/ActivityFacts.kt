package dev.atoll.app.tasks

import dev.atoll.app.core.Envelope
import kotlinx.serialization.json.*

/** Facts come from the host's current retained Room window, never a full-history query. */
data class ChannelActivityInput(val channelId: String, val name: String, val facts: List<TaskFact>)
data class ActivityAgentRun(val channelId: String, val requestId: String, val agentId: String, val type: String = "", val updatedAt: Long = 0)
/** The governance owner supplies channel-creation operations; tasks only presents them. */
data class ActivityOperation(val key: String, val channelId: String, val title: String, val state: String, val detail: String = "", val updatedAt: Long = 0, val source: JsonObject = Envelope.EMPTY)
data class ActivityItem(val key: String, val channelId: String, val channelName: String, val kind: String, val title: String, val state: String, val updatedAt: Long, val source: JsonObject, val detail: String = "", val approval: TaskFact? = null)
data class ActivitySnapshot(val activities: List<ActivityItem>, val operations: List<ActivityItem>, val operationsUnavailable: Boolean)
fun selectActivity(inputs: List<ChannelActivityInput>, agentRuns: List<ActivityAgentRun> = emptyList(), operations: List<ActivityOperation> = emptyList(), connected: Boolean = true,
    authorities: Map<String, ApprovalAuthority> = emptyMap(), now: Long = System.currentTimeMillis()): ActivitySnapshot {
    val channels = inputs.associateBy { it.channelId }
    val activity = inputs.flatMap { channel -> channel.facts.map { fact ->
        val source = if (fact.kind in setOf("approval","agent_run","task")) JsonObject(fact.source + ("workItemKey" to JsonPrimitive(fact.key))) else fact.source
        val approval = fact.takeIf { it.kind == "approval" }?.copy(turn = null, submission = null,
            actions = approvalActions(fact, authorities[channel.channelId] ?: ApprovalAuthority("", false), connected, now))
        ActivityItem("activity:${fact.key}",channel.channelId,channel.name,fact.kind,fact.title,fact.state,fact.updatedAt,source,approval = approval)
    } }
    val ops = operations.mapNotNull { op -> channels[op.channelId]?.let { channel -> ActivityItem(op.key,op.channelId,channel.name,"operation",op.title,op.state,op.updatedAt,op.source,op.detail) } } +
        agentRuns.mapNotNull { run -> channels[run.channelId]?.let { channel -> ActivityItem("operation:${run.channelId}:${run.requestId}",run.channelId,channel.name,"operation","${run.agentId.ifEmpty { "Agent" }} 正在运行","active",run.updatedAt,
            buildJsonObject { put("channelId",run.channelId);put("view","conversation");put("objectType","turn");put("objectId",run.requestId);put("requestId",run.requestId) },run.type.ifEmpty { "实时操作快照" }) } }
    val order = compareByDescending<ActivityItem> { it.updatedAt }.thenBy { it.key }
    return ActivitySnapshot(activity.sortedWith(order),ops.sortedWith(order),!connected)
}

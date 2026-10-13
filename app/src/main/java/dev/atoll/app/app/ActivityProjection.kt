package dev.atoll.app.app

import dev.atoll.app.governance.ChannelCreation
import dev.atoll.app.compose.LocalSubmission
import dev.atoll.app.core.string
import dev.atoll.app.model.Turn
import dev.atoll.app.ui.screens.ChannelViewState
import dev.atoll.app.ui.screens.ChannelAccess
import dev.atoll.app.model.TimelineSource
import dev.atoll.app.tasks.*
import kotlinx.serialization.json.*

/** Background channels retain activity metadata, never the folded request/progress trees. */
fun activityFacts(source: TimelineSource, selfId: String, now: Long): List<TaskFact> =
    selectTaskFacts(source, selfId = selfId, now = now).map(::activityMetadata)

private fun activityMetadata(fact: TaskFact):TaskFact = fact.copy(title=compactWorkTitle(fact.title),
    diagnostic=fact.turn?.requestId?.let { JsonObject(fact.diagnostic+("canonicalRequestId" to JsonPrimitive(it))) } ?: fact.diagnostic,
    turn=null,submission=null)

/** Include retained observer windows; never invent facts for undiscovered/inaccessible channels. */
fun visibleActivityChannels(channels: List<ChannelViewState>,memberIds: Set<String>):List<ChannelViewState> =
    channels.filter { it.id in memberIds || it.access in setOf(ChannelAccess.ObserverActive,ChannelAccess.ObserverStale) }

/** Merge local session state with each already-retained ledger window without re-reading history. */
fun mergeActivityInputs(inputs: List<ChannelActivityInput>,submissions: List<LocalSubmission>,timers: List<AutomationRecord>,
    selfIds: Map<String,String>,now: Long):List<ChannelActivityInput> {
    val pending=submissions.groupBy { it.channelId };val automation=timers.groupBy { it.channelId }
    return inputs.map { channel ->
        val canonical=channel.facts.filter { it.provenance=="ledger" }.map { fact ->
            fact.diagnostic["canonicalRequestId"].string().ifEmpty { fact.requestId.ifEmpty { fact.id } }
        }.toSet()
        val local=selectTaskFacts(emptyList<Turn>(),channel.channelId,selfIds[channel.channelId].orEmpty(),now,
            pending[channel.channelId].orEmpty().filterNot { it.messageId in canonical },automation[channel.channelId].orEmpty(),emptyMap())
        channel.copy(facts=channel.facts+local.map(::activityMetadata))
    }
}

fun creationActivity(operation: ChannelCreation): ActivityOperation = ActivityOperation(
    key = "operation:${operation.parentId}:${operation.requestId}", channelId = operation.parentId,
    title = "创建频道 ${operation.name}", state = when { operation.failed -> "failed"; operation.ready -> "completed"; operation.uncertain -> "uncertain"; else -> "active" },
    detail = operation.error.ifEmpty { when { operation.ready -> "频道已就绪"; operation.ledger -> "账本已确认，等待目录和成员关系"; operation.accepted -> "已提交，等待账本确认"; else -> "正在提交" } },
    updatedAt = operation.startedAt,
    source = buildJsonObject {
        put("channelId", if (operation.ready) operation.targetId else operation.parentId); put("view", "conversation")
        put("objectType", if (operation.ready) "channel" else "turn")
        if (!operation.ready) { put("objectId", operation.requestId); put("requestId", operation.requestId) }
    })

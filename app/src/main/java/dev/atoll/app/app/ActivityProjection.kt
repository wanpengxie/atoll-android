package dev.atoll.app.app

import dev.atoll.app.governance.ChannelCreation
import dev.atoll.app.model.TimelineSource
import dev.atoll.app.tasks.*
import kotlinx.serialization.json.*

/** Background channels retain activity metadata, never the folded request/progress trees. */
fun activityFacts(source: TimelineSource, selfId: String, now: Long): List<TaskFact> =
    selectTaskFacts(source, selfId = selfId, now = now).map { it.copy(turn = null, submission = null) }

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

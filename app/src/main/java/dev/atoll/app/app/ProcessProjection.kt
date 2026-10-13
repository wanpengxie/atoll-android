package dev.atoll.app.app

import dev.atoll.app.core.string
import dev.atoll.app.model.BackgroundTask
import dev.atoll.app.model.RequestLifecycle
import dev.atoll.app.core.long
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import dev.atoll.app.ui.screens.*
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun panelTime(timestamp: Long): String = if (timestamp <= 0) "" else
    DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(timestamp))

/** The detail panel and timeline use the same bounded process projection. */
internal fun processLostReason(target: TimelineMessageTarget, source: dev.atoll.app.model.TimelineSource?, now: Long = System.currentTimeMillis()): String =
    target.turn?.let { RequestLifecycle.lostReason(it, source?.let(RequestLifecycle::memberRestarts).orEmpty(), now) }.orEmpty()

internal fun processPanel(target: TimelineMessageTarget, source: dev.atoll.app.model.TimelineSource? = null, newerUnloaded: Boolean = false,
    now: Long = System.currentTimeMillis()): ProcessPanelViewState {
    val turn = target.turn
    val request = turn?.request ?: target.envelope
    fun find(entry: dev.atoll.app.model.TimelineEntry): dev.atoll.app.model.TimelineEntry? =
        if (entry.turn?.requestId == request.id) entry else entry.thread.firstNotNullOfOrNull(::find)
    val entry = source?.timeline?.firstNotNullOfOrNull(::find)
    val calls = entry?.thread.orEmpty().filter { child -> child.turn?.request?.let {
        it.senderKind != "human" && (it.type.startsWith("system.") || it.audience.any { actor -> actor.startsWith("tool:") || actor == "system" })
    } == true }
    val lost = processLostReason(target, source, now)
    val records = turn?.let { progressTrail(it, calls, it.terminal == null && lost.isEmpty() && !newerUnloaded, ::panelTime)?.records }.orEmpty()
    return ProcessPanelViewState(messageText(request).take(120).ifEmpty { request.type }, request.id, request.type,
        when {
            newerUnloaded && turn != null && turn.terminal == null -> "终态未加载"
            lost == "restart" -> "未完成 · Agent 已重启"
            lost == "expired" -> "未完成 · 已过期"
            else -> turn?.terminal?.status ?: turn?.latestCoreStatus?.takeIf(String::isNotEmpty) ?: if (turn != null) "pending" else request.kind
        },
        records, (turn?.requestSeq ?: target.seq).takeIf { it > 0 }, turn?.terminal?.id, turn?.terminal?.body?.get("detail").string())
}

/** No repeating clock: one refresh at this request's deadline, cancelled when the target changes. */
internal fun processPanelUpdates(target: TimelineMessageTarget, source: dev.atoll.app.model.TimelineSource?, newerUnloaded: Boolean,
    now: () -> Long = System::currentTimeMillis) = flow {
    emit(processPanel(target, source, newerUnloaded, now()))
    val request = target.turn?.takeIf { it.terminal == null }?.request
    val deadline = request?.expiresAt?.takeIf { it > 0 } ?: request?.body?.long("expires_at") ?: 0
    val instant = now()
    if (!newerUnloaded && deadline > instant && processLostReason(target, source, instant).isEmpty()) {
        delay(deadline - instant)
        emit(processPanel(target, source, newerUnloaded, now()))
    }
}

internal fun runningPanel(tasks: List<BackgroundTask>, roster: List<ActorRecord>): List<SubTaskViewState> {
    val names = roster.associate { it.id to it.name }
    return tasks.map { task -> SubTaskViewState(task.key, task.title, names[task.sender] ?: task.sender,
        when (task.kind) { "agent" -> SubTaskKind.Agent; "shell" -> SubTaskKind.Shell; else -> SubTaskKind.Other },
        SubTaskPhase.Running, task.startedAt, task.requestId.takeIf(String::isNotEmpty), task.lastText) }
}

internal fun processTarget(key: String, targets: Map<String, TimelineMessageTarget>, source: dev.atoll.app.model.TimelineSource?): TimelineMessageTarget? {
    targets[key]?.let { return it }
    targets.values.firstOrNull { it.turn?.requestId == key || it.envelope.id == key }?.let { return it }
    fun visit(entry: dev.atoll.app.model.TimelineEntry): TimelineMessageTarget? {
        val turn = entry.turn
        if (turn != null && (turn.requestId == key || turn.terminal?.id == key || turn.provisional.any { it.envelope.id == key }))
            return TimelineMessageTarget(turn.request, turn, turn.requestSeq)
        if (entry.envelope?.id == key) return TimelineMessageTarget(entry.envelope, null, entry.seq)
        return entry.thread.firstNotNullOfOrNull(::visit)
    }
    return source?.timeline?.firstNotNullOfOrNull(::visit)
}

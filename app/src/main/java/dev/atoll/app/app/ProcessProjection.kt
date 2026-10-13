package dev.atoll.app.app

import dev.atoll.app.core.string
import dev.atoll.app.model.BackgroundTask
import dev.atoll.app.ui.screens.*
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun panelTime(timestamp: Long): String = if (timestamp <= 0) "" else
    DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(timestamp))

/** The detail panel and timeline use the same bounded process projection. */
internal fun processPanel(target: TimelineMessageTarget, source: dev.atoll.app.model.TimelineSource? = null, newerUnloaded: Boolean = false): ProcessPanelViewState {
    val turn = target.turn
    val request = turn?.request ?: target.envelope
    fun find(entry: dev.atoll.app.model.TimelineEntry): dev.atoll.app.model.TimelineEntry? =
        if (entry.turn?.requestId == request.id) entry else entry.thread.firstNotNullOfOrNull(::find)
    val entry = source?.timeline?.firstNotNullOfOrNull(::find)
    val calls = entry?.thread.orEmpty().filter { child -> child.turn?.request?.let {
        it.senderKind != "human" && (it.type.startsWith("system.") || it.audience.any { actor -> actor.startsWith("tool:") || actor == "system" })
    } == true }
    val records = turn?.let { progressTrail(it, calls, it.terminal == null, ::panelTime)?.records }.orEmpty()
    return ProcessPanelViewState(messageText(request).take(120).ifEmpty { request.type }, request.id, request.type,
        if (newerUnloaded && turn != null && turn.terminal == null) "终态未加载" else turn?.terminal?.status ?: turn?.latestCoreStatus?.takeIf(String::isNotEmpty) ?: if (turn != null) "pending" else request.kind,
        records, (turn?.requestSeq ?: target.seq).takeIf { it > 0 }, turn?.terminal?.id, turn?.terminal?.body?.get("detail").string())
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

package dev.atoll.app.model

import dev.atoll.app.core.Envelope

data class BackgroundTask(
    val key: String, val callId: String, val sender: String, val title: String = "", val kind: String = "",
    val phase: String = "", val lastText: String = "", val startedAt: Long = 0,
    val updatedAt: Long = 0, val requestId: String = "", val seenStart: Boolean = false,
)

object BackgroundTasks {
    fun subTaskIdentity(envelopes: List<Envelope>): (Envelope) -> String {
        val callTask = mutableMapOf<String, String>()
        for (envelope in envelopes) if (envelope.type == "agent.task") {
            val call = envelope.body.text("call_id")
            val task = envelope.body.text("task_id")
            if (call.isNotEmpty() && task.isNotEmpty()) callTask["${envelope.senderId}\u0000$call"] = task
        }
        return { envelope ->
            val call = envelope.body.text("call_id")
            val task = envelope.body.text("task_id").ifEmpty { callTask["${envelope.senderId}\u0000$call"].orEmpty() }.ifEmpty { "call:${call.ifEmpty { envelope.id }}" }
            "${envelope.senderId}\u0000$task"
        }
    }
    fun subTaskEnds(state: TimelineSource): Map<String, Envelope> {
        val steps = state.rows.map { it.envelope }.filter { it.type == "agent.task" }
        val identity = subTaskIdentity(steps)
        val ends = mutableMapOf<String, Envelope>()
        for (step in steps) if (step.body.text("phase") in setOf("completed", "failed")) ends[identity(step)] = step
        return frozenMap(ends)
    }
    fun runningBackgroundTasks(state: TimelineSource, actorId: String = ""): List<BackgroundTask> {
        val steps = state.rows.map { it.envelope }.filter { it.type == "agent.task" }
        val identity = subTaskIdentity(steps)
        val tasks = linkedMapOf<String, BackgroundTask>()
        for (envelope in steps) {
            if (actorId.isNotEmpty() && envelope.senderId != actorId) continue
            val body = envelope.body
            if (body.text("call_id").isEmpty() && body.text("task_id").isEmpty()) continue
            val key = identity(envelope)
            val task = tasks[key] ?: BackgroundTask(key, body.text("call_id"), envelope.senderId, startedAt = envelope.ts)
            val phase = body.text("phase")
            tasks[key] = task.copy(
                title = body.text("title").ifEmpty { task.title }, kind = body.text("kind").ifEmpty { task.kind },
                phase = phase.ifEmpty { task.phase },
                lastText = if (phase in setOf("progress", "message") && body.text("text").isNotEmpty()) body.text("text") else task.lastText,
                requestId = task.requestId.ifEmpty { envelope.parentId }, updatedAt = maxOf(task.updatedAt, envelope.ts),
                seenStart = task.seenStart || phase == "started",
            )
        }
        return frozen(tasks.values.filter { it.seenStart && it.phase !in setOf("completed", "failed") }.sortedByDescending { it.startedAt })
    }
}

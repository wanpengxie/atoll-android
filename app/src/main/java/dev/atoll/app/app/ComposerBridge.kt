package dev.atoll.app.app

import dev.atoll.app.core.Envelope
import dev.atoll.app.model.TimelineSnapshot
import dev.atoll.app.ui.screens.ChannelAccess
import kotlinx.serialization.json.JsonObject

/** The host grants write access from membership facts; observer access never grants it. */
fun composerWritable(access: ChannelAccess, selfId: String): Boolean = selfId.isNotEmpty() &&
    access in setOf(ChannelAccess.MemberActive, ChannelAccess.MemberStale, ChannelAccess.MemberUnavailable)

data class ComposerUsage(val actor: String, val seq: Long, val value: JsonObject)
data class ComposerTimelineFacts(val landed: Set<String>, val usage: List<ComposerUsage>)

/** Run on the fold/background dispatcher, not during Compose rendering. */
fun composerTimelineFacts(snapshot: TimelineSnapshot): ComposerTimelineFacts {
    val envelopes = snapshot.items.flatMap { it.entry.envelopes() } + snapshot.waitingTurns.flatMap { listOfNotNull(it.request, it.terminal) }
    val usage = snapshot.items.flatMap { item ->
        fun collect(entry: dev.atoll.app.model.TimelineEntry): List<ComposerUsage> =
            listOfNotNull(entry.turn?.let { turn -> composerUsage(turn.terminal, turn.terminalSeq) } ?: composerUsage(entry.envelope, entry.seq)) + entry.thread.flatMap(::collect)
        collect(item.entry)
    }.groupBy { it.actor }.map { (_, values) -> values.maxBy { it.seq } }
    return ComposerTimelineFacts(envelopes.flatMap { listOf(it.id) + if (it.kind == "response") listOf(it.parentId) else emptyList() }.filter(String::isNotEmpty).toSet(), usage)
}

fun composerUsage(envelope: Envelope?, seq: Long): ComposerUsage? = envelope?.takeIf { it.isTerminal && it.type == "agent.ask" }
    ?.let { terminal -> (terminal.body["usage"] as? JsonObject)?.let { ComposerUsage(terminal.senderId, seq, it) } }

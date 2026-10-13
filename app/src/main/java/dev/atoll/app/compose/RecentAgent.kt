package dev.atoll.app.compose

import dev.atoll.app.model.TimelineSource

/** Port of web useAgentProbes.latestAgentInteraction: the newest own single-target
 * agent.ask in the unfiltered cached ledger. Sequence, not wall time, determines
 * recency; requests to actors no longer in the roster do not change the fallback.
 * Host supplies this to setChannel(recentAgentId = ...), including after cache load.
 */
fun recentAgentId(source: TimelineSource, selfId: String, roster: List<ComposerActor>): String {
    if (selfId.isEmpty()) return ""
    val agents = roster.filter { it.kind == "agent" }.mapTo(hashSetOf()) { it.id }
    if (agents.isEmpty()) return ""
    var latestId = ""
    var latestSeq = Long.MIN_VALUE
    for (row in source.rows) {
        val envelope = row.envelope
        if (envelope.kind != "request" || envelope.type != "agent.ask" || envelope.senderId != selfId) continue
        val target = envelope.audience.singleOrNull() ?: continue
        if (target !in agents || row.seq < latestSeq) continue
        latestSeq = row.seq; latestId = target
    }
    return latestId
}

package dev.atoll.app.model

import dev.atoll.app.core.Envelope

/** The web relation is one seed/parent/correlation pass, not transitive closure. */
internal class ConversationRelationIndex(val selfId: String) {
    private val seeds = hashSetOf<String>()
    private val correlations = hashSetOf<String>()
    private val byParent = hashMapOf<String, MutableSet<String>>()
    private val byCorrelation = hashMapOf<String, MutableSet<String>>()
    private val related = hashSetOf<String>()
    val ids: Set<String> get() = related

    /** True when this row also admits earlier rows, so projection order needs re-evaluation. */
    fun admit(envelope: Envelope): Boolean {
        if (envelope.type in setOf("terminal.command", "terminal.session")) return false
        val id = envelope.id
        val parent = envelope.parentId
        val correlation = envelope.correlationId
        if (id.isNotEmpty()) {
            if (parent.isNotEmpty()) byParent.getOrPut(parent) { hashSetOf() }.add(id)
            if (correlation.isNotEmpty()) byCorrelation.getOrPut(correlation) { hashSetOf() }.add(id)
        }
        var admittedEarlier = false
        fun note(candidate: String) {
            if (candidate.isNotEmpty() && related.add(candidate) && candidate != id) admittedEarlier = true
        }
        if (isSeed(envelope)) {
            if (id.isNotEmpty() && seeds.add(id)) {
                note(id)
                byParent[id]?.forEach(::note)
            }
            if (correlation.isNotEmpty() && correlations.add(correlation)) byCorrelation[correlation]?.forEach(::note)
        }
        if (parent in seeds || correlation in correlations) note(id)
        return admittedEarlier
    }
    private fun isSeed(envelope: Envelope): Boolean {
        if (selfId.isNotEmpty() && (envelope.senderId == selfId || selfId in envelope.audience)) return true
        if (envelope.senderKind !in setOf("agent", "tool", "peer") || envelope.senderId.isEmpty() || envelope.parentId.isNotEmpty()) return false
        val operation = envelope.type.startsWith("system.") || envelope.type == "actor.describe" || envelope.audience.isNotEmpty() && envelope.audience.all { it == "system" }
        if (operation) return false
        return envelope.kind != "request" || envelope.audience.any { it.startsWith("agent:") || it.startsWith("human:") || it.startsWith("peer:") && envelope.type == "agent.ask" }
    }
}

package dev.atoll.app.governance

import dev.atoll.app.core.Envelope

/** Ledger confirmation and directory/access convergence are independent evidence. */
data class ChannelCreation(val requestId: String, val parentId: String, val name: String, val startedAt: Long,
    val accepted: Boolean = false, val ledger: Boolean = false, val targetId: String = "",
    val observable: Boolean = false, val membership: Boolean = false, val serving: Boolean = false,
    val failed: Boolean = false, val uncertain: Boolean = false, val error: String = "") {
    val ready: Boolean get() = accepted && ledger && targetId.isNotEmpty() && observable && membership && serving && !failed
    fun terminal(envelope: Envelope): ChannelCreation {
        if (!envelope.isTerminal || (envelope.parentId != requestId && envelope.correlationId != requestId)) return this
        if (envelope.status == "failed") return copy(accepted = true, ledger = true, failed = true, uncertain = false,
            error = envelope.body.text("detail").ifEmpty { envelope.body.obj("error").text("message").ifEmpty { envelope.body.text("error_code") } })
        return result(envelope.body.obj("value"))
    }
    fun result(value: kotlinx.serialization.json.JsonObject): ChannelCreation = copy(accepted = true, ledger = true, failed = false, uncertain = false,
        targetId = value.text("channel_id").ifEmpty { value.text("channelId") },
        error = if (value.text("channel_id").ifEmpty { value.text("channelId") }.isEmpty()) "终态没有返回频道 ID；请刷新结果，勿重复创建。" else "")
    fun converge(channels: List<kotlinx.serialization.json.JsonObject>, memberships: Set<String>): ChannelCreation {
        if (ready) return this // This operation completed; later retirement/access changes do not reopen it.
        val row = channels.firstOrNull { it.declared().text("id").ifEmpty { it.text("key") } == targetId }
        return copy(observable = row != null, membership = targetId.isNotEmpty() && targetId in memberships,
            serving = row?.let { it.declared().bool("open") || it.declared().bool("serving") || it.obj("actual").bool("open") || it.obj("actual").bool("serving") ||
                (it.obj("actual")["measures"] as? kotlinx.serialization.json.JsonArray)?.any { m ->
                    (m as? kotlinx.serialization.json.JsonObject)?.let { fact -> fact.text("name") == "open" && fact.bool("value") } == true
                } == true } ?: false)
    }
}

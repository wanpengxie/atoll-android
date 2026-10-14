package dev.atoll.app.app

import dev.atoll.app.core.Row
import dev.atoll.app.stars.StarredMessage
import dev.atoll.app.ui.components.terminalResult
import dev.atoll.app.ui.screens.StarredMessageViewState
import kotlinx.serialization.json.JsonObject

/** Persisted snippets stay usable; only loaded canonical responses can prove a result badge. */
internal fun favoriteStars(stars: List<StarredMessage>, channelId: String, names: Map<String, String>, selfId: String,
    rows: Collection<Row>, timeLabel: (Long) -> String): List<StarredMessageViewState> {
    val ids = stars.mapTo(hashSetOf()) { it.id }
    val originals = rows.filter { it.channelId == channelId && it.envelope.id in ids }.associate { it.envelope.id to it.envelope }
    return stars.map { star ->
        val original = originals[star.id]
        val fields = original?.takeIf { it.isTerminal && it.status == "completed" && it.hasBody }
            ?.let { terminalResult(it, it.type).result?.value as? JsonObject }?.size
        StarredMessageViewState(star.id, if (star.sender == selfId && selfId.isNotEmpty()) "我" else names[star.sender]?.takeIf(String::isNotEmpty) ?: "未知成员",
            timeLabel(star.ts), star.text, senderId = star.sender,
            agent = !(selfId.isNotEmpty() && star.sender == selfId) && star.sender.substringBefore(':') != "human", resultFields = fields,
            self = selfId.isNotEmpty() && star.sender == selfId)
    }
}

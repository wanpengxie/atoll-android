package dev.atoll.app.app

import dev.atoll.app.core.Row
import dev.atoll.app.store.CoverageRange
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class BrowsePosition(val key: String, val seq: Long, val offset: Int = 0, val following: Boolean = true) {
    fun encode(): String = codec.encodeToString(serializer(), this)
    companion object {
        private val codec = Json { ignoreUnknownKeys = true }
        fun decode(value: String?): BrowsePosition? = value?.let {
            runCatching { codec.decodeFromString(serializer(), it) }.getOrNull()
        }?.takeIf { it.key.isNotEmpty() && it.seq > 0 && it.offset >= 0 }
    }
}

/** A historical window needs the same scan proof as tail hydration. */
internal fun restorableBrowseWindow(position: BrowsePosition, rows: List<Row>, coverage: List<CoverageRange>): Boolean =
    rows.any { it.seq == position.seq } && coverage.any { range ->
        range.low <= rows.first().seq && range.high >= rows.last().seq
    }

/** Only effective filters distinguish reading views; set insertion order never does. */
internal fun browseViewKey(mineOnly: Boolean, actors: Set<String>, showChannelActivity: Boolean = true): String =
    if (mineOnly) "mine:${JsonArray(actors.sorted().map(::JsonPrimitive))}" else if (showChannelActivity) "all" else "all:activity-off"

/** Loading an earlier page can extend an activity group and change its first-row key. */
internal fun browseEntryIndex(entries: List<dev.atoll.app.ui.screens.TimelineEntry>, position: BrowsePosition?): Int {
    if (position == null) return -1
    val exact = entries.indexOfFirst { it.key == position.key }
    if (exact >= 0) return exact
    return entries.indexOfFirst { entry -> entry is dev.atoll.app.ui.screens.TimelineEntry.ChannelActivity &&
        entry.items.isNotEmpty() && position.seq in entry.items.first().seq..entry.items.last().seq }
}

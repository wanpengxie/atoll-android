package dev.atoll.app.app

import dev.atoll.app.core.Row
import dev.atoll.app.store.CoverageRange
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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

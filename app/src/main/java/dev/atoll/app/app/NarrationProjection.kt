package dev.atoll.app.app

import dev.atoll.app.core.Row
import dev.atoll.app.model.*
import java.time.Instant
import java.time.ZoneId

internal data class NarrationPresentation(val items: List<PresentationItem>, val rows: Map<String, List<Row>> = emptyMap())
/** Split the model's window-wide narration placeholder at visible rows and day boundaries.
 * The ledger/fold remain unchanged; these are bounded display items with their original seqs.
 */
internal fun narrationPresentation(snapshot: TimelineSnapshot, source: TimelineSource?,
    zone: ZoneId = ZoneId.systemDefault()): NarrationPresentation {
    val normal = snapshot.items.filterNot { it.entry.kind == "narration" }
    if (source == null || snapshot.items.none { it.entry.kind == "narration" }) return NarrationPresentation(normal)
    val rows = source.narration.toList()
    if (rows.isEmpty()) return NarrationPresentation(normal)
    val result = ArrayList<PresentationItem>(normal.size + rows.size)
    val groups = linkedMapOf<String, List<Row>>()
    var next = 0
    fun timestamp(row: Row) = row.envelope.tsReceived.takeIf { it > 0 } ?: row.envelope.ts
    fun day(row: Row) = timestamp(row).takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toString() }.orEmpty()
    fun addGroups(group: List<Row>) {
        if (group.isEmpty()) return
        val first = group.first(); val last = group.last()
        val entry = TimelineEntry("narration", first.seq, envelope = first.envelope, lastSeq = last.seq)
        val id = first.envelope.id.ifEmpty { first.seq.toString() }
        val key = "channel-activity:$id"
        groups[key] = group
        result += PresentationItem(key, entry, first.seq, last.seq, "", timestamp(first), day(first),
            0, false, "compact", true, group.joinToString("|") { "${it.seq}:${it.envelope.hashCode()}" })
    }
    fun before(limit: Long?) {
        var start = next
        var currentDay = rows.getOrNull(next)?.let(::day)
        while (next < rows.size && (limit == null || rows[next].seq < limit)) {
            val nextDay = day(rows[next])
            if (nextDay != currentDay) {
                addGroups(rows.subList(start, next)); start = next; currentDay = nextDay
            }
            next++
        }
        addGroups(rows.subList(start, next))
    }
    normal.forEach { item -> before(item.entry.seq.takeUnless { item.entry.local }); result += item }
    before(null)
    return NarrationPresentation(result, groups)
}

package dev.atoll.app.app

import dev.atoll.app.core.*
import dev.atoll.app.model.*
import dev.atoll.app.ui.screens.TimelineEntry as UiEntry
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class NarrationProjectionTest {
    private val day = Instant.parse("2026-10-14T10:00:00Z").toEpochMilli()
    private fun row(seq: Long, system: Boolean = true, at: Long = day + seq * 1000, failed: Boolean = false, generated: Boolean = false) = Row("c", seq,
        Envelope(buildJsonObject {
            put("id", "row-$seq"); put("kind", if (system) "event" else "request")
            put("type", if (failed) "system.build.finished" else if (system) "system.member.created" else "human.message")
            put("ts", at); put("visibility", if (system) "system" else "public")
            put("sender", buildJsonObject { put("id", if (system) "system" else "human:self"); put("kind", if (system) "system" else "human") })
            put("payload", buildJsonObject { put("body", buildJsonObject {
                if (system) { put("member", "agent:a"); put("body", if (generated) "generated:tool" else "codex") }
                else put("text", "message $seq")
                if (failed) { put("result", "failed"); put("object", buildJsonObject { put("kind", "member"); put("name", "builder") }) }
            }) })
        }))
    private fun replica(vararg rows: Row) = ChannelReplica("c", "human:self").apply { apply(RowBatch("c", rows.toList(), Origin.LIVE)) }
    private fun snapshot(replica: ChannelReplica, show: Boolean = true) = replica.timeline(TimelineOptions(scope = ConversationScope.ALL, selfId = "human:self", showNarration = show))

    @Test fun narrationSplitsAtVisibleMessagesAndAtTheNextDayWithoutMovingMessages() {
        val model = replica(row(1), row(2), row(3, false), row(4), row(5), row(6, at = day + 86400000))
        val display = narrationPresentation(snapshot(model), model.snapshot(), ZoneId.of("UTC"))
        assertEquals(listOf("narration", "turn", "narration", "narration"), display.items.map { it.entry.kind })
        assertEquals(listOf(1L to 2L, 4L to 5L, 6L to 6L), display.items.filter { it.entry.kind == "narration" }.map { it.seqLow to it.seqHigh })
        assertEquals(listOf("2026-10-14", "2026-10-14", "2026-10-15"), display.items.filter { it.entry.kind == "narration" }.map { it.dayKey })
    }

    @Test fun liveAppendKeepsTheGroupKeyButChangesItsContentAndLeavesNeighborMessagesCached() {
        val model = replica(row(1, false), row(2))
        val mapper = TimelineMapper()
        val before = mapper.map(snapshot(model), "human:self", emptyList(), source = model.snapshot())
        val first = before.filterIsInstance<UiEntry.ChannelActivity>().single()
        model.apply(RowBatch("c", listOf(row(3, failed = true)), Origin.LIVE))
        val next = mapper.map(snapshot(model), "human:self", emptyList(), source = model.snapshot())
        val updated = next.filterIsInstance<UiEntry.ChannelActivity>().single()
        assertEquals(first.key, updated.key)
        assertEquals(2, updated.items.size)
        assertTrue(updated.items.last().failed)
        assertSame(before.filterIsInstance<UiEntry.Message>().single().value, next.filterIsInstance<UiEntry.Message>().single().value)
        val sequences = timelineRowSequences(snapshot(model), next)
        assertEquals(3L, sequences[updated.key])
        val position = BrowsePosition("channel-activity:older-key", 3)
        assertEquals(next.indexOf(updated), browseEntryIndex(next, position))
    }

    @Test fun hiddenActivityAndGeneratedMembersDoNotProduceEmptyOrMiscountedGroups() {
        val model = replica(row(1, generated = true), row(2))
        val mapper = TimelineMapper()
        val visible = mapper.map(snapshot(model), "human:self", emptyList(), source = model.snapshot())
        assertEquals(listOf("row-2"), visible.filterIsInstance<UiEntry.ChannelActivity>().single().items.map { it.id })
        assertTrue(mapper.map(snapshot(model, false), "human:self", emptyList(), source = model.snapshot()).isEmpty())
        assertEquals(browseViewKey(true, emptySet(), true), browseViewKey(true, emptySet(), false))
        assertNotEquals(browseViewKey(false, emptySet(), true), browseViewKey(false, emptySet(), false))
    }

    @Test fun localEchoesKeepTheirTailPositionAfterLedgerActivity() {
        val model = replica(row(1, false), row(2))
        val local = dev.atoll.app.model.TimelineEntry("turn", 0, turn = Turn(row(4, false).envelope, 0), local = true)
        val initial = model.timeline(TimelineOptions(scope = ConversationScope.ALL, showNarration = true, localEchoes = listOf(local)))
        assertTrue(narrationPresentation(initial, model.snapshot()).items.last().entry.local)
    }
}

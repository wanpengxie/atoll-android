package dev.atoll.app.app

import dev.atoll.app.core.*
import dev.atoll.app.model.ChannelReplica
import dev.atoll.app.ui.screens.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TimelineFeedbackTest {
    private val view = ConversationViewState(ChannelViewState("c", "channel"), hasOlder = false)
    private val source = ChannelReplica("c").apply {
        apply(RowBatch("c", listOf(Row("c", 5, Envelope(buildJsonObject {
            put("id", "message"); put("kind", "request"); put("type", "human.ask")
            put("sender", buildJsonObject { put("id", "human:other"); put("kind", "human") })
            put("payload", buildJsonObject { put("body", buildJsonObject { put("text", "others' message") }) })
        }))), Origin.CACHE))
    }.snapshot()

    @Test fun emptyChannelRequiresCompletedScanAndNoSourceEntries() {
        val empty = ChannelReplica("c").snapshot()
        val settled = timelineFeedback(view, empty, atStart = true, current = true, newerUnloaded = false)
        assertEquals(TimelineEmptyKind.Channel, settled.emptyKind)
        assertTrue(settled.emptySettled)
        assertNotEquals(TimelineEmptyKind.Channel, timelineFeedback(view, empty, false, true, false).emptyKind)
        assertNotEquals(TimelineEmptyKind.Channel, timelineFeedback(view, empty, true, false, false).emptyKind)
        assertNotEquals(TimelineEmptyKind.Channel, timelineFeedback(view, empty, true, true, true).emptyKind)
    }

    @Test fun mineAndActorEmptyResultsKeepChannelProvenance() {
        assertEquals(TimelineEmptyKind.Mine, timelineFeedback(view, source, true, true, false).emptyKind)
        val actor = timelineFeedback(view.copy(actorFilters = setOf("agent:a")), source, true, true, false)
        assertEquals(TimelineEmptyKind.ActorFilter, actor.emptyKind)
        assertTrue(actor.emptySettled)
        val pending = timelineFeedback(view.copy(actorFilters = setOf("agent:a"), hasOlder = true), source, false, true, false)
        assertEquals(TimelineEmptyKind.ActorFilter, pending.emptyKind)
        assertFalse(pending.emptySettled)
    }

    @Test fun confirmedHistoryStartUsesWebLabelsAndAddsAScrollHeader() {
        val populated = view.copy(entries = listOf(TimelineEntry.Message(MessageViewState("m", "root", "hello", "12:00"))))
        val start = timelineFeedback(populated, source, true, true, false)
        assertEquals("已到频道最早一条动态", start.historyStartLabel)
        assertEquals(1, timelineHeaderCount(start))
        assertEquals("已到频道开头，没有更早的符合筛选的往来",
            timelineFeedback(populated.copy(actorFilters = setOf("agent:a")), source, true, true, false).historyStartLabel)
        assertNull(timelineFeedback(populated, source, false, true, false).historyStartLabel)
        assertNull(timelineFeedback(populated.copy(loadingOlder = true), source, true, true, false).historyStartLabel)
        assertEquals(0, timelineHeaderCount(populated))
        assertEquals(1, timelineHeaderCount(populated.copy(hasOlder = true)))
    }
}

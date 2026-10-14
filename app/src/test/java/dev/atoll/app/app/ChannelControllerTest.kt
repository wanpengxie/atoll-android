package dev.atoll.app.app

import dev.atoll.app.core.*
import dev.atoll.app.store.*
import dev.atoll.app.sync.SyncHub
import dev.atoll.app.ui.screens.TimelineEntry
import dev.atoll.app.ui.screens.TimelineEmptyKind
import dev.atoll.app.compose.LocalSubmission
import dev.atoll.app.compose.localEnvelope
import dev.atoll.app.compose.localTimelineEntry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

class ChannelControllerTest {
    @Test fun activitySwitchPersistsAndOnlyAffectsAllWithIndependentBrowsePositions() = runBlocking {
        val member = row(1).let { it.copy(envelope = Envelope(buildJsonObject {
            put("id", "member"); put("kind", "event"); put("type", "system.member.created"); put("visibility", "system")
            put("payload", buildJsonObject { put("body", buildJsonObject { put("member", "agent:a"); put("body", "codex") }) })
        })) }
        val fixture = Fixture(head = 2, rows = listOf(member, row(2)), options = ChannelViewOptions(mineOnly = false))
        try {
            fixture.controller.open(ChannelRecord("c", "channel", null, "c"))
            val all = fixture.await { it.conversation?.mineOnly == false && it.conversation.entries.any { entry -> entry is TimelineEntry.ChannelActivity } }
            val group = all.conversation!!.entries.filterIsInstance<TimelineEntry.ChannelActivity>().single()
            fixture.controller.browse("all", group.key, 6, false)
            fixture.await { it.browsePosition?.key == group.key }
            fixture.controller.channelActivity(false)
            val hidden = fixture.await { it.conversation?.let { view -> !view.mineOnly && !view.showChannelActivity } == true }
            assertFalse(hidden.conversation!!.entries.any { it is TimelineEntry.ChannelActivity })
            assertFalse(fixture.preferences.options("ns", "c").showChannelActivity)
            assertTrue(fixture.preferences.options("another-account", "c").showChannelActivity)
            assertEquals(1, hidden.timelineSource!!.narration.size)
            fixture.controller.toggleScope()
            val mine = fixture.await { it.conversation?.let { view -> view.mineOnly && view.entries.any { it is TimelineEntry.ChannelActivity } } == true }
            assertFalse(mine.conversation!!.showChannelActivity)
            fixture.controller.toggleScope()
            fixture.await { it.conversation?.let { view -> !view.mineOnly && !view.showChannelActivity } == true }
            fixture.controller.channelActivity(true)
            val restored = fixture.await { it.conversation?.let { view -> !view.mineOnly && view.showChannelActivity } == true && it.browsePosition?.key == group.key }
            assertEquals(6, restored.browsePosition!!.offset)
            assertFalse(restored.browsePosition.following)
        } finally { fixture.stop() }
    }

    @Test fun scannedEmptyChannelPublishesExplicitEmptyState() = runBlocking {
        val fixture = Fixture(head = 0, rows = emptyList())
        try {
            fixture.controller.open(ChannelRecord("c", "channel", null, "c"))
            val state = fixture.await { it.conversation?.let { view -> view.emptyKind == TimelineEmptyKind.Channel && view.emptySettled } == true }
            assertTrue(state.conversation!!.entries.isEmpty())
            assertFalse(state.conversation.hasOlder)
            assertNull(state.conversation.historyStartLabel)
        } finally { fixture.stop() }
    }

    @Test fun scanProofReachesStartDespiteInvisibleSequenceNumbers() = runBlocking {
        val fixture = Fixture(head = 5, rows = listOf(row(5)))
        try {
            fixture.controller.open(ChannelRecord("c", "channel", null, "c"))
            val state = fixture.await { it.conversation?.historyStartLabel != null }
            assertFalse(state.conversation!!.hasOlder)
            assertEquals("已到频道最早一条动态", state.conversation.historyStartLabel)
        } finally { fixture.stop() }
    }

    @Test fun mineAndActorFilterEmptinessArePublishedWithoutClaimingAnEmptyChannel() = runBlocking {
        val other = row(1).let { it.copy(envelope = Envelope(JsonObject(it.envelope.json +
            ("audience" to buildJsonArray { add("human:someone-else") })))) }
        val fixture = Fixture(head = 1, rows = listOf(other))
        try {
            fixture.controller.open(ChannelRecord("c", "channel", null, "c"))
            val mine = fixture.await { it.conversation?.emptyKind == TimelineEmptyKind.Mine && it.conversation?.emptySettled == true }
            assertTrue(mine.conversation!!.entries.isEmpty())
            assertTrue(mine.timelineSource!!.timeline.isNotEmpty())
            fixture.controller.filter("agent:absent")
            fixture.await { it.conversation?.emptyKind == TimelineEmptyKind.ActorFilter && it.conversation?.emptySettled == true }
            fixture.controller.toggleScope()
            val all = fixture.await { it.conversation?.mineOnly == false }
            assertTrue(all.conversation!!.entries.isNotEmpty())
            assertNull(all.conversation.emptyKind)
        } finally { fixture.stop() }
    }

    @Test fun observerShowsAllRowsWithoutErasingMemberFilterPreferences() = runBlocking {
        val saved = ChannelViewOptions(mineOnly = true, actorFilters = setOf("agent:absent"))
        val fixture = Fixture(head = 1, rows = listOf(row(1)), selfId = "", options = saved)
        try {
            fixture.controller.open(ChannelRecord("c", "channel", null, "c"))
            val view = fixture.await { it.conversation != null }.conversation!!
            assertFalse(view.mineOnly)
            assertTrue(view.actorFilters.isEmpty())
            assertEquals("q1:request", view.entries.single().key)
            assertEquals(saved, fixture.preferences.options("ns", "c"))
        } finally { fixture.stop() }

        val member = Fixture(head = 1, rows = listOf(row(1)), options = saved)
        try {
            member.controller.open(ChannelRecord("c", "channel", null, "c"))
            val view = member.await { it.conversation != null }.conversation!!
            assertTrue(view.mineOnly)
            assertEquals(saved.actorFilters, view.actorFilters)
            assertTrue(view.entries.isEmpty())
        } finally { member.stop() }
    }

    @Test fun cacheReaderFailuresAreVisibleAndCanBeRetried() = runBlocking {
        val fixture = Fixture(head = 1, rows = listOf(row(1)))
        try {
            fixture.await { it.sync.current && it.taskFacts.isNotEmpty() }
            fixture.failNextRead.set(true)
            fixture.controller.open(ChannelRecord("c", "channel", null, "c"))
            assertEquals("temporary cache failure", fixture.await { it.error != null }.error)
            fixture.controller.retry()
            val restored = fixture.await { it.error == null && it.conversation != null }
            assertEquals("q1:request", restored.conversation!!.entries.single().key)
        } finally { fixture.stop() }
    }

    @Test fun scopeAndEffectiveActorFiltersRestoreIndependentAnchorsAndRejectOldViewCallbacks() = runBlocking {
        val fixture = Fixture(head = 10, rows = (1L..10).map(::row))
        val mineKey = browseViewKey(true, emptySet())
        val allKey = browseViewKey(false, emptySet())
        val filteredKey = browseViewKey(true, setOf("human:other"))
        val mine = BrowsePosition("q2:request", 2, 22, false)
        val all = BrowsePosition("q8:request", 8, 80, false)
        val filtered = BrowsePosition("q4:request", 4, 44, false)
        try {
            fixture.controller.open(ChannelRecord("c", "channel", null, "c"))
            fixture.await { it.conversation != null }
            fixture.controller.browse(mineKey, mine.key, mine.offset, false)
            fixture.await { it.browsePosition == mine }
            fixture.controller.toggleScope()
            assertNull(fixture.await { it.conversation?.mineOnly == false }.browsePosition)
            fixture.controller.browse(mineKey, "q3:request", 30, false) // Delayed callback from the old view.
            fixture.controller.browse(allKey, all.key, all.offset, false)
            fixture.await { it.browsePosition == all }
            assertEquals(mine, BrowsePosition.decode(fixture.preferences.get("ns", "c.browse.$mineKey")))
            fixture.controller.filter("human:other") // ALL ignores actor filters, retaining the same anchor.
            assertEquals(all, fixture.await { it.conversation?.actorFilters == setOf("human:other") }.browsePosition)
            fixture.controller.toggleScope()
            assertNull(fixture.await { it.conversation?.mineOnly == true }.browsePosition)
            fixture.controller.browse(filteredKey, filtered.key, filtered.offset, false)
            fixture.await { it.browsePosition == filtered }
            fixture.controller.filter(null)
            assertEquals(mine, fixture.await { it.conversation?.actorFilters == emptySet<String>() }.browsePosition)
            fixture.controller.toggleScope()
            assertEquals(all, fixture.await { it.conversation?.mineOnly == false }.browsePosition)
            fixture.controller.toggleScope()
            fixture.await { it.conversation?.mineOnly == true }
            fixture.controller.filter("human:other")
            assertEquals(filtered, fixture.await { it.conversation?.actorFilters == setOf("human:other") }.browsePosition)
            assertEquals(filtered, BrowsePosition.decode(fixture.preferences.get("ns", "c.browse.$filteredKey")))
        } finally { fixture.stop() }
    }

    @Test fun obsoleteSingleChannelBrowsePositionIsNotRead() = runBlocking {
        val fixture = Fixture(head = 10, rows = (1L..10).map(::row), legacyBrowse = BrowsePosition("q2:request", 2, 50, false))
        try {
            fixture.controller.open(ChannelRecord("c", "channel", null, "c"))
            assertNull(fixture.await { it.conversation != null }.browsePosition)
        } finally { fixture.stop() }
    }

    @Test fun historicalBrowsePositionRestoresOneBoundedCacheWindowAndJumpReturnsToTail() = runBlocking {
        val saved = BrowsePosition("q500:request", 500, 87, following = false)
        val fixture = Fixture(head = 6000, rows = (1L..6000).map(::row), browse = saved)
        try {
            fixture.controller.open(ChannelRecord("c", "channel", null, "c"))
            val restored = fixture.await { it.newerUnloaded && it.conversation != null }
            assertEquals(saved, restored.browsePosition)
            assertEquals("q1:request", restored.conversation!!.entries.first().key)
            assertEquals("q700:request", restored.conversation.entries.last().key)
            assertEquals(0L, fixture.memory.channel("c").meta.readPosition)
            fixture.controller.browse(browseViewKey(true, emptySet()), "q550:request", 22, false)
            fixture.await { it.browsePosition?.seq == 550L }
            assertEquals(BrowsePosition("q550:request", 550, 22, false), BrowsePosition.decode(fixture.preferences.get("ns", "c.browse.${browseViewKey(true, emptySet())}")))
            fixture.controller.closeView()
            fixture.await { it.conversation == null }
            fixture.controller.open(ChannelRecord("c", "channel", null, "c"))
            fixture.await { it.newerUnloaded && it.conversation?.entries?.lastOrNull()?.key == "q750:request" }
            fixture.controller.jumpToBottom()
            val tail = fixture.await { !it.newerUnloaded && it.conversation?.entries?.lastOrNull()?.key == "q6000:request" }
            assertEquals(4500, tail.timelineSource!!.rows.size)
        } finally { fixture.stop() }
    }

    @Test fun prunedBrowsePositionFallsBackToTailWithAnExplicitNotice() = runBlocking {
        val fixture = Fixture(head = 6000, rows = (1501L..6000).map(::row), browse = BrowsePosition("q500:request", 500, following = false))
        try {
            fixture.controller.open(ChannelRecord("c", "channel", null, "c"))
            val state = fixture.await { it.conversation != null }
            assertFalse(state.newerUnloaded)
            assertNotNull(state.restoreNotice)
            assertEquals("q6000:request", state.conversation!!.entries.last().key)
        } finally { fixture.stop() }
    }

    @Test fun oldWindowsKeepTheirRowsAndReadOnlyToTheLoadedBoundaryThenReturnToTail() = runBlocking {
        val fixture = Fixture(head = 5100, rows = (1L..5100).map(::row))
        try {
            fixture.controller.foreground(false)
            fixture.controller.open(ChannelRecord("c", "channel", null, "c"))
            fixture.await { it.conversation?.entries?.filterIsInstance<TimelineEntry.Message>()?.size == 4500 }
            repeat(3) { index ->
                fixture.controller.loadOlder()
                val expected = "q${401 - index * 200}:request"
                fixture.await { state -> state.conversation?.entries?.firstOrNull()?.key == expected || state.newerUnloaded }
            }
            val historical = fixture.await { it.newerUnloaded }
            val messages = historical.conversation!!.entries.filterIsInstance<TimelineEntry.Message>()
            assertEquals(4500, messages.size)
            assertEquals("q1:request", messages.first().key)
            assertEquals("q4500:request", messages.last().key)
            assertEquals(0, historical.conversation.waitingCount)
            fixture.controller.foreground(true)
            fixture.controller.follow(true)
            fixture.controller.visibleRows(setOf("q4500:request"))
            withTimeout(10_000) { while (fixture.store.channel("c").meta.readPosition != 4500L) yield() }
            assertEquals(4500L, fixture.store.channel("c").meta.readPosition)
            val live = row(5101)
            fixture.wire.frames.emit(DownFrame.Feed("", live, 1))
            fixture.controller.receive(live)
            fixture.await { it.conversation?.newMessages == 1 }
            assertEquals("q4500:request", fixture.controller.state.value.conversation!!.entries.last().key)
            fixture.memory.awaitRows("c") { it.any { row -> row.seq == 5101L } }
            fixture.controller.jumpToBottom()
            val returned = fixture.await { !it.newerUnloaded && it.conversation?.entries?.lastOrNull()?.key == "q5101:request" }
            assertEquals(4500, returned.conversation!!.entries.size)
        } finally { fixture.stop() }
    }

    @Test fun aBurstOfBackgroundNotificationsUsesOneCombinedRoomWindow() = runBlocking {
        val fixture = Fixture(head = 1, rows = listOf(row(1)), baseline = false)
        try {
            fixture.await { it.sync.current }
            repeat(100) { index ->
                val live = row(index + 2L)
                fixture.wire.frames.emit(DownFrame.Feed("", live, 1))
                fixture.controller.receive(live)
            }
            fixture.wire.frames.emit(DownFrame.Checkpoint("c", 2, 101, 1))
            val state = fixture.await { it.unread.count == 100 && !it.unread.pending }
            assertNull(state.conversation)
            assertTrue("Rows lookups=${fixture.lookups.get()}", fixture.lookups.get() <= 2)
        } finally { fixture.stop() }
    }

    @Test fun onlyVisibleRowsAdvanceReadPositionAndBackgroundOrClosedViewsCannotMarkRead() = runBlocking {
        val fixture = Fixture(head = 10, rows = (1L..10).map(::row))
        try {
            fixture.controller.open(ChannelRecord("c", "channel", null, "c"))
            fixture.await { it.conversation != null }
            assertEquals(0L, fixture.store.channel("c").meta.readPosition)
            fixture.controller.visibleRows(setOf("q3:request", "day:unknown"))
            withTimeout(10_000) { while (fixture.store.channel("c").meta.readPosition != 3L) yield() }
            fixture.controller.foreground(false)
            fixture.controller.visibleRows(setOf("q9:request"))
            fixture.controller.foreground(true)
            fixture.controller.follow(false)
            fixture.controller.visibleRows(setOf("q6:request"))
            withTimeout(10_000) { while (fixture.store.channel("c").meta.readPosition != 6L) yield() }
            fixture.controller.closeView()
            fixture.await { it.conversation == null }
            fixture.controller.visibleRows(setOf("q10:request"))
            fixture.controller.open(ChannelRecord("c", "channel", null, "c"))
            fixture.await { it.conversation != null }
            assertEquals(6L, fixture.store.channel("c").meta.readPosition)
        } finally { fixture.stop() }
    }

    @Test fun localEchoesAreViewOnlyAndTheCanonicalRowTakesOverWithoutDuplicatingOrMarkingThemRead() = runBlocking {
        val fixture = Fixture(head = 1, rows = listOf(row(1)))
        try {
            fixture.controller.open(ChannelRecord("c", "channel", null, "c"))
            fixture.await { it.conversation != null }
            val submission = LocalSubmission("local:opaque", "c", buildJsonObject {
                put("kind", "request"); put("msg_type", "agent.ask")
                put("audience", buildJsonArray { add("agent:a") })
                put("payload", buildJsonObject { put("text", "local message") })
            }, "local message", "agent:a", createdAt = 1)
            fixture.controller.localPlacement(listOf(submission.localTimelineEntry("human:self")), setOf(submission.messageId))
            val projected = fixture.await { it.timeline?.items?.any { item -> item.entry.id == submission.messageId && item.entry.local } == true }
            assertEquals(1, projected.timelineSource!!.rows.size)
            assertEquals(1, fixture.memory.rows("c").size)
            fixture.controller.visibleRows(setOf("${submission.messageId}:request"))
            fixture.controller.localPlacement(emptyList(), emptySet())
            fixture.await { it.timeline?.items?.none { item -> item.entry.id == submission.messageId } == true }
            assertEquals(0L, fixture.memory.channel("c").meta.readPosition)
            fixture.controller.localPlacement(listOf(submission.localTimelineEntry("human:self")), setOf(submission.messageId))
            val canonical = Row("c", 2, Envelope(JsonObject(submission.localEnvelope("human:self").json.filterKeys { it != "local_submission_state" })))
            fixture.wire.frames.emit(DownFrame.Feed("", canonical, 1))
            fixture.controller.receive(canonical)
            val landed = fixture.await { state -> state.timelineSource?.rows?.size == 2 && state.timeline?.items?.any { it.entry.id == submission.messageId && !it.entry.local } == true }
            assertEquals(1, landed.timeline!!.items.count { it.entry.id == submission.messageId })
            fixture.controller.visibleRows(setOf("${submission.messageId}:request"))
            withTimeout(10_000) { while (fixture.memory.channel("c").meta.readPosition != 2L) yield() }
        } finally { fixture.stop() }
    }

    @Test fun multipleActorSelectionsPersistAndToggleIndependently() = runBlocking {
        val fixture = Fixture(head = 1, rows = listOf(row(1)))
        try {
            fixture.controller.open(ChannelRecord("c", "channel", null, "c"))
            fixture.await { it.conversation != null }
            fixture.controller.filter("agent:a")
            fixture.controller.filter("agent:b")
            fixture.await { it.conversation?.actorFilters == setOf("agent:a", "agent:b") }
            fixture.controller.filter("agent:a")
            fixture.await { it.conversation?.actorFilters == setOf("agent:b") }
            assertEquals(setOf("agent:b"), fixture.preferences.options("ns", "c").actorFilters)
        } finally { fixture.stop() }
    }

    @Test fun baselinedBackgroundChannelsStillPublishActivityFactsFromOneRoomRead() = runBlocking {
        val fixture = Fixture(head = 1, rows = listOf(row(1)), baseline = false)
        try {
            val state = fixture.await { it.taskFacts.isNotEmpty() && it.sync.current }
            assertEquals(0, state.unread.count)
            assertNull(state.conversation)
            assertNull(state.timelineSource)
            assertEquals("q1", state.taskFacts.single().id)
            assertNull(state.taskFacts.single().turn)
            assertTrue("Rows lookups=${fixture.lookups.get()}", fixture.lookups.get() <= 2)
        } finally { fixture.stop() }
    }

    @Test fun backgroundApprovalExpiresWithoutAnotherMessageOrARepeatingClock() = runBlocking {
        val approval = Row("c", 1, Envelope(buildJsonObject {
            put("id", "approval"); put("kind", "request"); put("type", "human.approve"); put("expires_at", System.currentTimeMillis() + 2_000)
            put("sender", buildJsonObject { put("id", "agent:a"); put("kind", "agent") }); put("audience", buildJsonArray { add("human:self") })
            put("payload", buildJsonObject { put("body", buildJsonObject { put("text", "approve?") }) })
        }))
        val fixture = Fixture(head = 1, rows = listOf(approval), baseline = false)
        try {
            fixture.await { it.taskFacts.singleOrNull()?.state == "waiting" }
            val expired = fixture.await { it.taskFacts.singleOrNull()?.state == "expired" }
            assertFalse(expired.taskFacts.single().needsYou)
            assertNull(expired.conversation)
        } finally { fixture.stop() }
    }

    private class Fixture(head: Long, rows: List<Row>, baseline: Boolean = true, browse: BrowsePosition? = null,
        selfId: String = "human:self", options: ChannelViewOptions? = null, legacyBrowse: BrowsePosition? = null) {
        val memory = MemoryRowStore()
        val lookups = AtomicInteger()
        val failNextRead = AtomicBoolean()
        val store = object : RowStore by memory {
            override suspend fun rows(channelId: String, beforeSeq: Long, limit: Int): List<Row> {
                lookups.incrementAndGet()
                if (failNextRead.compareAndSet(true, false)) error("temporary cache failure")
                return memory.rows(channelId, beforeSeq, limit)
            }
        }
        val wire = FakeWire(head)
        val preferences = FakePreferences()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        lateinit var hub: SyncHub
        lateinit var controller: ChannelController
        init {
            runBlocking {
                memory.commit("c", rows, if (head > 0) listOf(CoverageRange(1, head)) else emptyList())
                if (legacyBrowse != null) preferences.put("ns", "c.browse", legacyBrowse.encode())
                if (options != null) preferences.saveOptions("ns", "c", options)
                if (browse != null) preferences.put("ns", "c.browse.${browseViewKey((options?.mineOnly ?: true) && selfId.isNotBlank(), options?.actorFilters.orEmpty())}", browse.encode())
                if (baseline) preferences.put("ns", "c.baselined", "true")
                hub = SyncHub(wire, store, scope)
                controller = ChannelController("c", selfId, hub.channel("c"), store, preferences, "ns", head, scope)
                withTimeout(10_000) { wire.frames.subscriptionCount.first { it > 0 } }
            }
        }
        suspend fun await(predicate: (ChannelRuntimeState) -> Boolean) = withTimeout(10_000) { controller.state.first(predicate) }
        suspend fun stop() { controller.stop(); hub.stop(); scope.cancel() }
    }
    private class FakeWire(head: Long) : WireClient {
        override val state = MutableStateFlow<WireState>(WireState.Attached(1, buildJsonObject {
            put("history_meta", buildJsonArray { add(buildJsonObject { put("channel_id", "c"); put("head_seq", head) }) })
        }))
        override val frames = MutableSharedFlow<DownFrame>(extraBufferCapacity = 256)
        override suspend fun request(type: String, payload: JsonObject): JsonObject = error("Unexpected request")
        override suspend fun history(payload: JsonObject): HistoryPage = error("Unexpected network history")
    }
    private class FakePreferences : ViewPreferences {
        private val values = ConcurrentHashMap<String, String>()
        private val selections = ConcurrentHashMap<String, ChannelViewOptions>()
        override suspend fun get(namespace: String, key: String) = values["$namespace.$key"]
        override suspend fun put(namespace: String, key: String, value: String) { values["$namespace.$key"] = value }
        override suspend fun options(namespace: String, channel: String) = selections["$namespace.$channel"] ?: ChannelViewOptions()
        override suspend fun saveOptions(namespace: String, channel: String, options: ChannelViewOptions) { selections["$namespace.$channel"] = options }
    }
    companion object {
        private fun row(seq: Long) = Row("c", seq, Envelope(buildJsonObject {
            put("id", "q$seq"); put("kind", "request"); put("type", "human.ask")
            put("sender", buildJsonObject { put("id", "human:other"); put("kind", "human") })
            put("audience", buildJsonArray { add("human:self") })
            put("payload", buildJsonObject { put("body", buildJsonObject { put("text", "message $seq") }) })
        }))
    }
}

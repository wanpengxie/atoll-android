package dev.atoll.app.ui

import dev.atoll.app.ui.screens.*
import org.junit.Assert.*
import org.junit.Test

class NavigationPresentationTest {
    @Test fun collapsingBranchKeepsPeersAndRestoresGrandchildrenOnExpand() {
        val channels = listOf(ChannelViewState("c0", "c0"), ChannelViewState("dev", "dev", parentId = "c0", depth = 1),
            ChannelViewState("web", "web", parentId = "dev", depth = 2), ChannelViewState("other", "other", parentId = "c0", depth = 1))
        assertEquals(listOf("c0", "dev", "other"), visibleChannels(channels, setOf("dev")).map { it.id })
        assertEquals(listOf("c0"), visibleChannels(channels, setOf("c0")).map { it.id })
        assertEquals(channels, visibleChannels(channels, emptySet()))
    }
    @Test fun unreadAndTimersMatchWeb() {
        assertEquals("99+", unreadLabel(120, UnreadPhase.Ready))
        assertEquals("…", unreadLabel(0, UnreadPhase.Recovering))
        assertEquals("?", unreadLabel(3, UnreadPhase.Unknown))
        assertEquals("", unreadLabel(0, UnreadPhase.Ready))
        assertEquals("01:03", activityDuration(1000, 64000))
        assertEquals("00:00", activityDuration(1000, 0))
    }
}

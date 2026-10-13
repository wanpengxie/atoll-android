package dev.atoll.app.app

import org.junit.Assert.*
import org.junit.Test

class RouteHistoryTest {
    @Test fun panelsAndListEntriesWalkBackAndForwardAndNewNavigationCutsTheForwardBranch() {
        val history = RouteHistory()
        val channel = AppRoute("c")
        val panel = channel.copy(focus = "panel:running")
        history.push(null); history.push(channel)
        assertEquals(channel, history.back(panel))
        assertEquals(panel, history.forward(channel))
        assertEquals(channel, history.back(panel))
        history.push(channel)
        assertFalse(history.canForward)
        assertEquals(channel, history.back(AppRoute("other")))
        assertNull(history.back(channel))
        assertFalse(history.canBack)
        history.clear(); assertFalse(history.canForward)
    }
    @Test fun oldEntriesAreBounded() {
        val history = RouteHistory(2)
        repeat(3) { history.push(AppRoute("$it")) }
        assertEquals(AppRoute("2"), history.back(AppRoute("3")))
        assertEquals(AppRoute("1"), history.back(AppRoute("2")))
        assertFalse(history.canBack)
    }
}

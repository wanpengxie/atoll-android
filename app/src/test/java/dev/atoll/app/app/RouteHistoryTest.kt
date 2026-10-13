package dev.atoll.app.app

import org.junit.Assert.*
import org.junit.Test

class RouteHistoryTest {
    @Test fun panelsAndListEntriesWalkBackAndForwardAndNewNavigationCutsTheForwardBranch() {
        val history = RouteHistory<AppRoute?>()
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
        val history = RouteHistory<AppRoute?>(2)
        repeat(3) { history.push(AppRoute("$it")) }
        assertEquals(AppRoute("2"), history.back(AppRoute("3")))
        assertEquals(AppRoute("1"), history.back(AppRoute("2")))
        assertFalse(history.canBack)
    }

    @Test fun activityFromTheListAndMemberDetailShareOneBackForwardHistory() {
        val history = RouteHistory<WorkspaceLocation>()
        val list = WorkspaceLocation(null)
        val activity = WorkspaceLocation(null, WorkspacePanel.Activity)
        val channel = WorkspaceLocation(AppRoute("c"))
        val members = WorkspaceLocation(AppRoute("c", focus = "panel:members"))
        val detail = WorkspaceLocation(AppRoute("c", focus = "participant:agent:opaque"))
        val governance = WorkspaceLocation(AppRoute("c", focus = "channel:c"))
        history.push(list)
        assertEquals(list, history.back(activity))
        assertEquals(activity, history.forward(list))
        history.push(activity); history.push(channel); history.push(members); history.push(detail)
        assertEquals(detail, history.back(governance))
        assertEquals(members, history.back(detail))
        assertEquals(detail, history.forward(members))
        assertEquals(governance, history.forward(detail))
    }
}

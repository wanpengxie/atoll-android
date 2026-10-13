package dev.atoll.app.app

import org.junit.Assert.*
import org.junit.Test

class RouteFocusTest {
    @Test fun typedLinksSelectTheirSurfaceAndKeepOpaqueKeys() {
        for (kind in FocusKind.entries) {
            val key = "opaque:part/path + spaces"
            val route = AppRoute.parse(focusedChannelLink("c/id", kind, key))!!
            assertEquals("c/id", route.channel)
            assertEquals(kind.tab, route.tab)
            assertEquals(RouteFocus(kind, key), RouteFocus.parse(route.focus, route.tab))
        }
        assertEquals(RouteFocus(FocusKind.Artifact, "daemon://d/c/a"), RouteFocus.parse("artifact:daemon://d/c/a", "conversation"))
    }
    @Test fun legacyTaskAndFileKeysKeepWorkingAndEmptyTargetsAreIgnored() {
        assertEquals(RouteFocus(FocusKind.WorkItem, "approval:c:q"), RouteFocus.parse("approval:c:q", "tasks"))
        assertEquals(RouteFocus(FocusKind.Artifact, "daemon://d/c/a"), RouteFocus.parse("daemon://d/c/a", "files"))
        assertNull(RouteFocus.parse("turn:", "conversation"))
    }
    @Test fun normalRowsAreCentredAndTallRowsKeepTheirHeadingVisible() {
        assertEquals(-250f, centredScrollDelta(0, 100, 0, 600))
        assertEquals(0f, centredScrollDelta(0, 1000, 0, 600))
        assertEquals(150f, centredScrollDelta(430, 100, 30, 630))
    }
}

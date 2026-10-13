package dev.atoll.app.app

import org.junit.Assert.*
import org.junit.Test

class RouteResolutionTest {
    private val records = listOf(ChannelRecord("uuid", "test", "parent", "c0.test"))
    @Test fun nameLinksResolveAndPreserveTheSelectedSurfaceAndFocus() {
        assertEquals(AppRoute("uuid", "tasks", "task:42"), AppRoute("c0.test", "tasks", "task:42").resolve(records, emptySet()))
    }
    @Test fun fallbackPrefersMembershipThenAnyDeclaredChannel() {
        val all = records + ChannelRecord("member", "member", null, "member")
        assertEquals(AppRoute("member"), fallbackChannelRoute(all, setOf("member")))
        assertNotNull(fallbackChannelRoute(all, emptySet()))
        assertNull(fallbackChannelRoute(emptyList(), emptySet()))
    }
    @Test fun membershipsResolveBeforeTheTreeAndDeletedIdsDoNotResolve() {
        assertEquals(AppRoute("new-id"), AppRoute("new-id").resolve(records, setOf("new-id")))
        assertNull(AppRoute("deleted-id").resolve(records, emptySet()))
    }
}

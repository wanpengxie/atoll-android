package dev.atoll.app.app

import dev.atoll.app.ui.screens.*
import org.junit.Assert.*
import org.junit.Test

class BootstrapCacheTest {
    @Test fun cacheIsDisplayOnlyAndOnlyMatchingConfirmedIdentityCanOpenIt() {
        val saved = BootstrapCache("root", "Root", mapOf("c" to "human:root:123"),
            listOf(ChannelRecord("c", "dev", null, "c0.dev")), mapOf("c" to 500), "c")
        val loaded = BootstrapCache.decode(saved.encode())!!
        assertEquals(saved, loaded)
        assertNull(loaded.forPrincipal("different-user"))
        assertEquals(saved, loaded.forPrincipal("root"))
        assertFalse(loaded.navigation().account.root)
        assertEquals(ChannelAccess.MemberStale, loaded.navigation().channels.single().access)
        assertEquals(UnreadPhase.Recovering, loaded.navigation().channels.single().unreadPhase)
        assertEquals("human:root:123", loaded.memberships["c"])
        assertNull(BootstrapCache.decode("{}"))
        assertNull(BootstrapCache.decode("broken json"))
    }
}

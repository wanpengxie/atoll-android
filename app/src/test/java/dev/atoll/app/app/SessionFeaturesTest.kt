package dev.atoll.app.app

import org.junit.Assert.*
import org.junit.Test

class SessionFeaturesTest {
    @Test fun filesRetainSixChannelsReuseRecentModelsAndReleaseEvictedModelsExactlyOnce() {
        val released=mutableListOf<String>();var created=0
        val cache=ChannelModelCache(6,{ channel -> created++;channel to Any() },{ released.add(it.first) })
        val first=cache.get("0")
        repeat(5) { cache.get((it+1).toString()) }
        assertSame(first,cache.get("0"));assertEquals(6,created)
        cache.get("6");assertEquals(listOf("1"),released)
        assertSame(first,cache.get("0"))
        cache.get("1");assertEquals(listOf("1","2"),released)
        cache.clear();assertEquals(8,released.size);assertEquals(1,released.count { it=="0" })
        cache.clear();assertEquals(8,released.size)
    }
}

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package dev.atoll.app.stars

import dev.atoll.app.core.Envelope
import dev.atoll.app.net.KeyValueStore
import dev.atoll.app.net.MemoryKeyValueStore
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class StarredViewModelTest {
    private val owner = StarScope("https://node", "reader", "world")
    private fun envelope(id: String, channel: String = "c") = Envelope(buildJsonObject {
        put("id", id); put("channel_id", channel); put("ts", 1234)
        put("sender", buildJsonObject { put("id", "human:actor") })
    })
    @Test fun clicksBeforeRestoreAreSerializedNewestFirstAndOlderSnapshotsStayImmutable() = runTest {
        val values = MemoryKeyValueStore(); val store = StarredStore(values)
        store.save(owner, mapOf("c" to listOf(StarredMessage("old"))))
        var time = 0L
        val vm = StarredViewModel(owner, store, StandardTestDispatcher(testScheduler)) { ++time }
        try {
            vm.toggle(envelope("a"), "\n A \nsecond line"); vm.toggle(envelope("b"), "B"); advanceUntilIdle()
            val previous = vm.state.value
            assertTrue(previous.ready); assertEquals(listOf("b", "a", "old"), previous.starsFor("c").map { it.id })
            assertEquals("A", previous.starsFor("c")[1].text)
            assertEquals(listOf(2L, 1L, 0L), previous.starsFor("c").map { it.starredAt })
            assertTrue(previous.isStarred("c", "a")); assertFalse(previous.isStarred("different", "a"))
            vm.toggle(envelope("a"), "ignored"); vm.remove("c", "missing"); vm.toggle(envelope("x", "other"), "X"); advanceUntilIdle()
            assertEquals(listOf("b", "old"), vm.state.value.starsFor("c").map { it.id })
            assertEquals(listOf("b", "a", "old"), previous.starsFor("c").map { it.id })
            assertEquals(vm.state.value.channels, StarredStore(values).load(owner))
            try { (previous.starsFor("c") as MutableList<StarredMessage>).clear(); fail("mutable snapshot") } catch (_: UnsupportedOperationException) { }
            val drawer = previous.drawerItems("c", mapOf("human:actor" to "Alice")) { "time:$it" }
            assertEquals("Alice", drawer[0].sender); assertEquals("time:1234", drawer[0].time)
            assertEquals("未知成员", drawer[2].sender)
        } finally { vm.close(); advanceUntilIdle() }
    }
    @Test fun rapidTogglesAndCloseDrainTheLastAcceptedClickWithoutNodeAccess() = runTest {
        val values = MemoryKeyValueStore(); val store = StarredStore(values)
        val vm = StarredViewModel(owner, store, StandardTestDispatcher(testScheduler))
        repeat(100) { vm.toggle(envelope("same"), "S") }
        vm.toggle(envelope("final"), "saved before close"); vm.close(); advanceUntilIdle()
        assertEquals(listOf("final"), store.load(owner)["c"]?.map { it.id })
        vm.toggle(envelope("after-close"), "not accepted"); advanceUntilIdle()
        assertEquals(listOf("final"), store.load(owner)["c"]?.map { it.id })
    }
    @Test fun failedLocalPersistenceKeepsTheBookmarkAndReportsItsLimit() = runTest {
        val values = object : KeyValueStore {
            override fun get(key: String): String? = null
            override fun put(key: String, value: String) { error("test storage failure") }
            override fun remove(key: String) { }
        }
        val vm = StarredViewModel(owner, StarredStore(values), StandardTestDispatcher(testScheduler))
        try {
            vm.toggle(envelope("a"), "A"); advanceUntilIdle()
            assertTrue(vm.state.value.isStarred("c", "a")); assertTrue(vm.state.value.error.isNotEmpty())
            vm.remove("c", "a"); advanceUntilIdle()
            assertFalse(vm.state.value.isStarred("c", "a")); assertTrue(vm.state.value.error.isEmpty())
        } finally { vm.close(); advanceUntilIdle() }
    }
}

package dev.atoll.app.stars

import dev.atoll.app.net.MemoryKeyValueStore
import org.junit.Assert.*
import org.junit.Test

class StarredStoreTest {
    private val scope = StarScope("https://node", "principal", "world")
    @Test fun scopesIsolateAccountsServersAndWorldsAndSurviveReload() {
        val values = MemoryKeyValueStore(); val store = StarredStore(values)
        val message = StarredMessage("id", "human:member", "snippet", 5, 8)
        store.save(scope, mapOf("c" to listOf(message)))
        assertEquals(listOf(message), StarredStore(values).load(scope)["c"])
        for (other in listOf(scope.copy(server = "https://other"), scope.copy(principalId = "other"), scope.copy(world = "other"))) assertTrue(store.load(other).isEmpty())
        // Encode the tuple rather than concatenate identifiers with an ambiguous separator.
        assertNotEquals(StarScope("a:b", "c", "d").storageKey, StarScope("a", "b:c", "d").storageKey)
        store.save(scope, emptyMap()); assertNull(values.get(scope.storageKey))
    }
    @Test fun firstNonemptyLineAnd120CharacterLimitMatchWeb() {
        assertEquals("first line", starSnippet("\n  \n  first line  \nsecond line"))
        assertEquals("x".repeat(120), starSnippet("x".repeat(120)))
        assertEquals("x".repeat(120) + "…", starSnippet("x".repeat(121)))
        assertEquals("", starSnippet(" \n "))
    }
    @Test fun unreadableDataStartsEmptyAndInvalidEntriesDoNotHideValidBookmarks() {
        val values = MemoryKeyValueStore(); val store = StarredStore(values)
        values.put(scope.storageKey, "invalid JSON"); assertTrue(store.load(scope).isEmpty())
        values.put(scope.storageKey, """{"c":[null,{}, {"id":""},{"id":"ok","extra":"ignored"},{"id":"ok"}],"other":"not a list"}""")
        assertEquals(mapOf("c" to listOf(StarredMessage("ok"))), store.load(scope))
    }
}

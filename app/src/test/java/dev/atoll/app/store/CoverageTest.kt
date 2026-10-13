package dev.atoll.app.store

import dev.atoll.app.core.Envelope
import dev.atoll.app.core.Row
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class CoverageTest {
    @Test fun mergesOverlappingAndAdjacentScansWithoutFillingHoles() {
        assertEquals(listOf(CoverageRange(1, 8), CoverageRange(11, 15)), mergeCoverage(listOf(
            CoverageRange(11, 15), CoverageRange(6, 8), CoverageRange(1, 3), CoverageRange(3, 5),
        )))
    }

    @Test fun picksTheHighestGapRatherThanTheNewestCachedRow() {
        val coverage = listOf(CoverageRange(1, 5), CoverageRange(9, 11))
        assertEquals(CoverageRange(12, 13), tailGap(coverage, 13))
        assertEquals(CoverageRange(6, 8), tailGap(coverage, 11))
        assertNull(tailGap(listOf(CoverageRange(9, 11)), 11))
        assertNull(tailGap(emptyList(), 11))
    }

    @Test fun retainsOnlyTheNewestRowsAndShrinksTheScanProof() = runBlocking {
        val store = MemoryRowStore(maxRows = 3)
        store.commit("c", (1L..5).map(::row), listOf(CoverageRange(1, 5)))
        assertEquals(listOf(3L, 4L, 5L), store.rows("c").map { it.seq })
        assertEquals(listOf(CoverageRange(3, 5)), store.channel("c").coverage)
        store.commit("c", meta = store.channel("c").meta.copy(readPosition = 4))
        assertEquals(4L, store.channel("c").meta.readPosition)
        store.clear()
        assertTrue(store.rows("c").isEmpty())
        assertTrue(store.channel("c").coverage.isEmpty())
        assertEquals(0L, store.channel("c").meta.headSeq)
    }

    @Test fun immutableRowsAreInsertedOnce() = runBlocking {
        val store = MemoryRowStore()
        assertEquals(1, store.commit("c", listOf(row(1), row(1))).size)
        assertTrue(store.commit("c", listOf(row(1))).isEmpty())
        assertEquals(1, store.rows("c").size)
    }

    private fun row(seq: Long) = Row("c", seq, Envelope(buildJsonObject { put("id", "m$seq") }))
}

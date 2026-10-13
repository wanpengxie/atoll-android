package dev.atoll.app.app

import dev.atoll.app.core.Envelope
import dev.atoll.app.core.Row
import dev.atoll.app.store.CoverageRange
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class BrowsePositionTest {
    @Test fun opaqueKeyAndPixelOffsetRoundTripAndInvalidValuesAreIgnored() {
        val position = BrowsePosition("opaque:id:answer", 2048, 135, false)
        assertEquals(position, BrowsePosition.decode(position.encode()))
        assertNull(BrowsePosition.decode("garbage"))
        assertNull(BrowsePosition.decode(BrowsePosition("id", -1).encode()))
    }
    @Test fun aCachedAnchorDoesNotPermitPresentingAnUnprovedGapAsOneWindow() {
        val position = BrowsePosition("id", 3, following = false)
        val rows = listOf(1L, 3L, 8L).map { Row("c", it, Envelope(JsonObject(emptyMap()))) }
        assertFalse(restorableBrowseWindow(position, rows, listOf(CoverageRange(1, 3), CoverageRange(8, 8))))
        assertTrue(restorableBrowseWindow(position, rows, listOf(CoverageRange(1, 8))))
        assertFalse(restorableBrowseWindow(position.copy(seq = 5), rows, listOf(CoverageRange(1, 8))))
    }
}

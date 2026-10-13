package dev.atoll.app.app

import dev.atoll.app.core.Envelope
import dev.atoll.app.model.Turn
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TimelineTaskSourceTest {
    private fun envelope(id: String) = Envelope(buildJsonObject { put("id", id) })
    @Test fun replyCreatesAStableTurnSourceAtTheRequestSequenceWhileStandaloneUsesItsOwnRow() {
        val request = envelope("opaque:request:answer")
        val reply = envelope("reply")
        val turn = Turn(request, 4321, terminal = reply, terminalSeq = 9876)
        val source = timelineTaskSource("c", TimelineMessageTarget(reply, turn, 9876))!!
        assertEquals(JsonPrimitive(4321L), source["seq"])
        assertEquals(JsonPrimitive(request.id), source["objectId"])
        assertEquals(JsonPrimitive(request.id), source["requestId"])
        val standalone = timelineTaskSource("c", TimelineMessageTarget(envelope("event:42"), null, 42))!!
        assertEquals(JsonPrimitive(42L), standalone["seq"])
        assertEquals(JsonPrimitive("message"), standalone["objectType"])
        assertNull(standalone["requestId"])
    }
    @Test fun unlandedOrCompactRowsDoNotInventASequenceFromTheirDisplayedIndex() {
        assertNull(timelineTaskSource("c", TimelineMessageTarget(envelope("local"), null)))
        assertNull(timelineTaskSource("c", TimelineMessageTarget(envelope("placeholder"), Turn(envelope("request"), 0), 12)))
    }
}

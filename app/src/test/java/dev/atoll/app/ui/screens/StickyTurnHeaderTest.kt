package dev.atoll.app.ui.screens

import org.junit.Assert.*
import org.junit.Test

class StickyTurnHeaderTest {
    private val request = TimelineEntry.Message(MessageViewState("opaque:request", "root", "回合请求", "12:00", part = MessagePart.Request))
    private val answer = TimelineEntry.Message(MessageViewState("opaque:answer", "worker", "答案", "12:01", part = MessagePart.Answer))
    @Test fun onlyExpandedTallRowsWhoseHeaderHasScrolledOutArePinned() {
        fun candidate(expanded: Set<String> = setOf(answer.value.id), offset: Int = -44, size: Int = 1000,
            start: Int = 0, end: Int = 800) = stickyTurnHeader(answer, request, expanded, offset, size, start, end, 44)
        assertEquals(request.value, candidate()!!.request)
        assertNull(candidate(expanded = emptySet()))
        assertNull(candidate(offset = -43))
        assertNull(candidate(size = 800))
        assertNull(candidate(end = 0))
        assertNotNull(candidate(offset = -64, start = -20))
        assertNull(candidate(offset = -63, start = -20))
    }
    @Test fun noticesAndOtherRowsCannotBorrowAnotherTurnsRequest() {
        assertNull(stickyTurnHeader(TimelineEntry.Day("d", "今天"), request, setOf(answer.value.id), -100, 1000, 0, 800, 44))
        val other = request.copy(value = request.value.copy(id = "different:request"))
        assertNull(stickyTurnHeader(answer, other, setOf(answer.value.id), -100, 1000, 0, 800, 44)!!.request)
        val single = answer.copy(value = answer.value.copy(part = MessagePart.Single))
        assertNull(stickyTurnHeader(single, request, setOf(answer.value.id), -100, 1000, 0, 800, 44)!!.request)
    }
}

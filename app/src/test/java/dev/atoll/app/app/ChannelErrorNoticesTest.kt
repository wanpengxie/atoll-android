package dev.atoll.app.app

import org.junit.Assert.*
import org.junit.Test

class ChannelErrorNoticesTest {
    @Test fun dismissesOnlyTheClickedMessageAndChannel() {
        val notices = ChannelErrorNotices()
        notices.dismiss("c", "first failure")
        assertNull(notices.visible("c", "first failure"))
        assertEquals("first failure", notices.visible("other", "first failure"))
        assertEquals("new failure", notices.visible("c", "new failure"))
        assertEquals("first failure", notices.visible("c", "first failure"))
    }
    @Test fun recoveryRetryAndSessionChangePermitAnotherNotice() {
        val notices = ChannelErrorNotices()
        notices.dismiss("c", "failure"); assertNull(notices.visible("c", null))
        assertEquals("failure", notices.visible("c", "failure"))
        notices.dismiss("c", "failure"); notices.reset("c")
        assertEquals("failure", notices.visible("c", "failure"))
        notices.dismiss("c", "failure"); notices.clear()
        assertEquals("failure", notices.visible("c", "failure"))
    }
}

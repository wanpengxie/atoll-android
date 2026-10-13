package dev.atoll.app.app

import org.junit.Assert.*
import org.junit.Test

class CreatedChannelTest {
    @Test fun aCreatedChildHasItsNameBeforeTheDirectoryRoundTrip() {
        val parent = ChannelRecord("parent-uuid", "test", "c0", "c0.test")
        val child = createdChannelRecord("child-uuid", parent.id, "android-ui-123", parent)
        assertEquals("c0.test.android-ui-123", child.qualifiedName)
        assertEquals("android-ui-123", child.name)
        assertEquals(parent.id, child.parent)
        assertEquals("child-uuid", child.id)
        assertEquals("android-ui-123", createdChannelRecord(child.id, parent.id, child.name, null).qualifiedName)
    }
}

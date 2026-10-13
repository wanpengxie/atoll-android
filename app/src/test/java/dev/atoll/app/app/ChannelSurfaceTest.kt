package dev.atoll.app.app

import org.junit.Assert.*
import org.junit.Test

class ChannelSurfaceTest {
    @Test fun filesChoiceSurvivesTasksAndStoredChannelReturn() {
        val files = ChannelSurface().files(true)
        assertEquals("files", files.routeTab)
        val tasks = files.selectTab(true)
        assertEquals("tasks", tasks.routeTab)
        assertEquals(files, ChannelSurface.decode(tasks.encode()).selectTab(false))
        assertEquals("conversation", files.files(false).routeTab)
    }
    @Test fun explicitDeepLinkSelectsItsSurfaceRatherThanAStoredFileChoice() {
        assertEquals("conversation", ChannelSurface.fromRoute("conversation").routeTab)
        assertEquals("files", ChannelSurface.fromRoute("files").routeTab)
        assertEquals(ChannelSurface(), ChannelSurface.decode(null))
        assertEquals(ChannelSurface(), ChannelSurface.decode("invalid"))
    }
}

package dev.atoll.app.app

import org.junit.Assert.*
import org.junit.Test

class ChannelSurfaceTest {
    @Test fun resourceTabHasItsOwnRouteAndSurvivesTasksTerminalAndStoredChannelReturn() {
        val resources = ChannelSurface().resources()
        assertEquals("resources", resources.routeTab)
        assertEquals(resources, ChannelSurface.decode(resources.encode()))
        assertEquals(resources, resources.selectTab(true).selectTab(false))
        assertEquals(resources, resources.terminal(true).terminal(false))
        assertEquals("files", resources.files(true).routeTab)
        assertEquals(resources, ChannelSurface.fromRoute("resources"))
        assertEquals(AppRoute("c", "resources"), AppRoute.parse("atoll://channels/c/resources"))
    }
    @Test fun filesChoiceSurvivesTasksAndStoredChannelReturn() {
        val files = ChannelSurface().files(true)
        assertEquals("files", files.routeTab)
        val tasks = files.selectTab(true)
        assertEquals("tasks", tasks.routeTab)
        assertEquals(files, ChannelSurface.decode(tasks.encode()).selectTab(false))
        assertEquals("conversation", files.files(false).routeTab)
    }
    @Test fun terminalDetachesBackToFilesAndItsFlagSurvivesChannelReturn() {
        val files = ChannelSurface().files(true)
        val terminal = files.terminal(true)
        assertEquals("terminal", terminal.routeTab)
        assertEquals(terminal, ChannelSurface.decode(terminal.encode()))
        assertEquals(files, terminal.terminal(false))
        assertEquals("terminal", terminal.selectTab(true).selectTab(false).routeTab)
        assertFalse(terminal.files(true).terminalOpen)
        assertEquals(files, ChannelSurface.decode("conversation:1"))
        assertEquals(AppRoute("c", "terminal"), AppRoute.parse("atoll://channels/c/terminal"))
    }
    @Test fun explicitDeepLinkSelectsItsSurfaceRatherThanAStoredFileChoice() {
        assertEquals("conversation", ChannelSurface.fromRoute("conversation").routeTab)
        assertEquals("files", ChannelSurface.fromRoute("files").routeTab)
        assertEquals(ChannelSurface(), ChannelSurface.decode(null))
        assertEquals(ChannelSurface(), ChannelSurface.decode("invalid"))
    }
}

package dev.atoll.app.app

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TaskNavigationTest {
    @Test fun sourceNavigationKeepsOpaqueChannelsAndChoosesTheTaskOrTurnSurface() {
        fun link(vararg values: Pair<String,String>) = taskSourceLink(buildJsonObject { values.forEach { put(it.first,it.second) } })
        assertEquals(AppRoute("c/id", "conversation", "turn:q:answer"), AppRoute.parse(link("channelId" to "c/id", "requestId" to "q:answer")))
        assertEquals(AppRoute("c", "tasks", "approval:c:q"), AppRoute.parse(link("channelId" to "c", "workItemKey" to "approval:c:q", "requestId" to "q")))
        assertEquals(AppRoute("c", "files", "daemon://device/channel/a b.png"), AppRoute.parse(link("channelId" to "c", "view" to "files", "resourceId" to "daemon://device/channel/a b.png")))
        assertNull(link("requestId" to "q"))
    }
}

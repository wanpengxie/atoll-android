package dev.atoll.app.ui.screens

import org.junit.Assert.*
import org.junit.Test

class CollaborationIndexTest {
    private fun call(id: String, parent: String = "", conversation: Boolean = true) = RelatedCallViewState(id, id,
        "same", listOf("same"), "", MessageStatus.Completed, conversation = conversation, parentId = parent)

    @Test fun pathsUseIdsEvenWhenEveryActorHasTheSameName() {
        val root = call("root")
        val index = CollaborationIndex(CollaborationViewState(root, listOf(call("reviewer", "root"), call("test-writer", "reviewer"), call("other", "root"))))
        assertEquals(listOf("root", "reviewer", "test-writer"), index.path("test-writer").map { it.id })
        assertEquals(listOf("reviewer", "other"), index.children("root").map { it.id })
        assertEquals(listOf("test-writer"), index.children("reviewer").map { it.id })
    }

    @Test fun toolIntermediariesStayInCallCountsAndDoNotBecomeNavigationLevels() {
        val index = CollaborationIndex(CollaborationViewState(call("root"), listOf(call("tool", "root", false), call("child", "tool"))))
        assertEquals(listOf("root", "child"), index.path("child").map { it.id })
        assertEquals(listOf("child"), index.children("root").map { it.id })
        assertEquals(1, index.ordinaryCount("root"))
    }

    @Test fun aMissingSelectedCallReturnsToTheRootOfThisThread() {
        val index = CollaborationIndex(CollaborationViewState(call("new-root"), emptyList()))
        assertEquals("new-root", index.selected("old-child").id)
        assertEquals(listOf("new-root"), index.path("old-child").map { it.id })
    }
}

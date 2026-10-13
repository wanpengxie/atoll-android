package dev.atoll.app.compose

import dev.atoll.app.core.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

class EditorWriteAuthorityTest {
    @Test fun nativeTypingAndSelectionAreNeverOverwrittenByOldEchoes() {
        val authority = EditorWriteAuthority()
        var viewText = ""
        var cursor = 0
        fun update(command: EditorCommand) {
            if (authority.accept(command)) { viewText = command.text; cursor = command.selectionStart }
        }
        update(EditorCommand())
        "/status".forEachIndexed { index, character ->
            viewText += character; cursor++
            // A recomposition may still carry the preceding native echo.
            update(EditorCommand(0, "/status".take(index), index))
        }
        assertEquals("/status", viewText); assertEquals(7, cursor)
        update(EditorCommand(1, "", 0))
        assertEquals("", viewText); assertEquals(0, cursor)
        update(EditorCommand(2, "reply original", 5))
        update(EditorCommand(1, "stale", 0))
        assertEquals("reply original", viewText); assertEquals(5, cursor)
    }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun nativeDraftEchoDoesNotAdvanceExternalVersionButExplicitChangesDo() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vm = ComposerViewModel("owner", FakeComposerWire(), MemoryOutbox(), AttachmentUploader { _, _ -> error("unused") }, dispatcher, dispatcher)
        try {
            vm.setChannel("c0", HUMAN.id, listOf(HUMAN, AGENT)); runCurrent()
            val initial = vm.state.value.editorCommand
            "/status".indices.forEach { vm.onEditorText("/status".take(it + 1)) }
            assertEquals("/status", vm.state.value.draft.text)
            assertEquals(initial, vm.state.value.editorCommand)
            vm.replaceEditorText("/status\n", 8)
            assertTrue(vm.state.value.editorCommand.version > initial.version)
            assertEquals(8, vm.state.value.editorCommand.selectionStart)
            vm.setChannel("c1", HUMAN.id, listOf(HUMAN, AGENT))
            assertEquals("", vm.state.value.editorCommand.text)
            vm.setChannel("c0", HUMAN.id, listOf(HUMAN, AGENT))
            assertEquals("/status\n", vm.state.value.editorCommand.text)
        } finally { vm.close() }
    }
}

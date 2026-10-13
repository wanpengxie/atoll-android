@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package dev.atoll.app.compose

import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SubmitFrameTest {
    @Test fun otherFeatureJournalsOfflineWithSameIdAndDoesNotTouchDraft() = runTest {
        val store = MemoryOutbox(); val dispatcher = StandardTestDispatcher(testScheduler)
        val vm = ComposerViewModel("owner", FakeComposerWire(), store, AttachmentUploader { _, _ -> error("unused") }, dispatcher, dispatcher, now = { 1000 })
        try {
            vm.setChannel("c0", HUMAN.id, listOf(HUMAN, AGENT)); vm.updateText("ordinary draft")
            vm.setChannelWritable("tasks", true); runCurrent()
            val frame = JsonObject(submission("task-1").frame + ("channel_id" to JsonPrimitive("tasks")))
            assertEquals("task-1", vm.submitFrame(frame, "Create task", "codex"))
            val saved = store.rows.getValue("task-1")
            assertEquals("tasks", saved.channelId); assertEquals(SendState.QUEUED, saved.state)
            assertEquals(1000 + 24 * 60 * 60 * 1000L, saved.expiresAt)
            assertEquals("ordinary draft", vm.state.value.draft.text)
        } finally { vm.close() }
    }
    @Test fun readonlyAndExpiredFramesAreRejectedBeforeJournaling() = runTest {
        val store = MemoryOutbox(); val dispatcher = StandardTestDispatcher(testScheduler)
        val vm = ComposerViewModel("owner", FakeComposerWire(), store, AttachmentUploader { _, _ -> error("unused") }, dispatcher, dispatcher, now = { 1000 })
        try {
            runCurrent()
            try { vm.submitFrame(submission().frame, "task", "agent"); fail("readonly") } catch (_: ComposerFailure) {}
            vm.setChannelWritable("c0", true)
            try { vm.submitFrame(submission(expires = 1000).frame, "task", "agent"); fail("expired") } catch (_: ComposerFailure) {}
            assertTrue(store.rows.isEmpty())
        } finally { vm.close() }
    }
}

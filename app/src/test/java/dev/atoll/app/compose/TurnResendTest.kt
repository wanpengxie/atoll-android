@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package dev.atoll.app.compose

import dev.atoll.app.core.Up
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class TurnResendTest {
    @Test fun rejectedResendReusesItsOriginalJournalFrameAndUuid() = runTest {
        val store = MemoryOutbox(); val dispatcher = StandardTestDispatcher(testScheduler)
        store.putMany(listOf(submission("already-rejected", SendState.REJECTED).copy(resendOf = "original")))
        val vm = ComposerViewModel("owner", FakeComposerWire(), store, AttachmentUploader { _, _ -> error("unused") }, dispatcher, dispatcher)
        try {
            vm.setChannel("c0", HUMAN.id, listOf(HUMAN, AGENT)); runCurrent()
            vm.resend(failedResendTurn()); vm.resend(failedResendTurn()); runCurrent()
            assertEquals(setOf("already-rejected"), store.rows.keys)
            assertEquals(SendState.QUEUED, store.rows.values.single().state)
            assertEquals("hello", store.rows.values.single().text)
        } finally { vm.close() }
    }
    @Test fun doubleTapProducesOneDurableIntentWithoutChangingDraft() = runTest {
        val store = MemoryOutbox(); val dispatcher = StandardTestDispatcher(testScheduler)
        val vm = ComposerViewModel("owner", FakeComposerWire(), store, AttachmentUploader { _, _ -> error("unused") }, dispatcher, dispatcher, now = { 1000 })
        try {
            vm.setChannel("c0", HUMAN.id, listOf(HUMAN, AGENT)); vm.updateText("preserve draft"); runCurrent()
            val turn = failedResendTurn()
            vm.resend(turn); vm.resend(turn); runCurrent(); vm.resend(turn); runCurrent()
            assertEquals(1, store.rows.size)
            val saved = store.rows.values.single()
            assertNotEquals(turn.requestId, saved.messageId); assertEquals(turn.requestId, saved.resendOf)
            assertEquals("/status", (saved.frame["payload"] as JsonObject).text("text"))
            assertEquals("preserve draft", vm.state.value.draft.text)
            assertTrue(turn.requestId in vm.state.value.resendingRequestIds)
            vm.reconcileLanded("c0", setOf(saved.messageId)); runCurrent()
            vm.resend(turn); runCurrent()
            assertTrue(store.rows.isEmpty()) // A second click after the feed won cannot create a second intent.
        } finally { vm.close() }
    }
    @Test fun restoringAnOutstandingResendMustFinishBeforeAnotherClickCanCreateAnId() = runTest {
        val store = MemoryOutbox()
        val saved = submission("already-saved").copy(resendOf = "original")
        store.putMany(listOf(saved))
        val loaded = CompletableDeferred<Unit>()
        val repository = object : OutboxRepository by store {
            override suspend fun restore(): List<LocalSubmission> { loaded.await(); return store.restore() }
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vm = ComposerViewModel("owner", FakeComposerWire(), repository, AttachmentUploader { _, _ -> error("unused") }, dispatcher, dispatcher)
        try {
            vm.setChannel("c0", HUMAN.id, listOf(HUMAN, AGENT)); vm.resend(failedResendTurn()); runCurrent()
            loaded.complete(Unit); runCurrent()
            assertEquals(setOf("already-saved"), store.rows.keys)
        } finally { vm.close() }
    }
    @Test fun uncertainTransportAndReconnectRetryTheSameResendUuid() = runTest {
        val store = MemoryOutbox(); val wire = FakeComposerWire(); val dispatcher = StandardTestDispatcher(testScheduler)
        val vm = ComposerViewModel("owner", wire, store, AttachmentUploader { _, _ -> error("unused") }, dispatcher, dispatcher,
            actorQueries = ActorQueries { _, _, _, _ -> JsonObject(emptyMap()) })
        try {
            vm.setChannel("c0", HUMAN.id, listOf(HUMAN, AGENT)); runCurrent()
            vm.resend(failedResendTurn()); runCurrent(); val id = store.rows.keys.single()
            wire.attach(); runCurrent()
            wire.calls.single { it.type == Up.SUBMIT }.result.completeExceptionally(java.io.IOException("connection lost")); runCurrent()
            wire.disconnect(); runCurrent(); wire.attach(2); runCurrent()
            val calls = wire.calls.filter { it.type == Up.SUBMIT }
            assertEquals(2, calls.size); assertEquals(listOf(id, id), calls.map { it.frame.text("id") })
            assertEquals(setOf(id), store.rows.keys)
        } finally { vm.close() }
    }
}

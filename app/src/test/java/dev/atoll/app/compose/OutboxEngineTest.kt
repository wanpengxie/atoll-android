@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package dev.atoll.app.compose

import dev.atoll.app.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class OutboxEngineTest {
    @Test fun offlinePersistenceAndReconnectUseOriginalIdAndExactStampedFrame() = runTest {
        val wire = FakeComposerWire(); val store = MemoryOutbox()
        val engine = OutboxEngine(wire, store, StandardTestDispatcher(testScheduler), now = { 10 })
        try {
            engine.accept(listOf(submission()))
            assertEquals(SendState.QUEUED, store.rows.getValue("message").state)
            assertTrue(wire.calls.isEmpty())
            wire.attach(); runCurrent()
            assertEquals(SendState.TRANSMITTING, engine.submissions.value.single().state)
            val first = wire.calls.single()
            assertEquals("session-1", first.frame.getValue("payload").jsonObject.getValue("origin").jsonObject.text("session"))
            first.result.completeExceptionally(WireException(Up.SUBMIT, "closed", "connection lost")); runCurrent()
            assertEquals(SendState.UNCERTAIN, engine.submissions.value.single().state)
            wire.disconnect(); runCurrent(); wire.attach(2, "different-session"); runCurrent()
            assertEquals(2, wire.calls.size)
            assertEquals(first.frame, wire.calls.last().frame)
            assertEquals("message", wire.calls.last().frame.text("id"))
            wire.calls.last().result.complete(json("message_id" to text("message"))); runCurrent()
            assertEquals(SendState.ACCEPTED, engine.submissions.value.single().state)
            engine.reconcileLanded("c0", setOf("message"))
            assertTrue(engine.submissions.value.isEmpty()); assertTrue(store.rows.isEmpty())
        } finally { engine.close() }
    }
    @Test fun explicitRejectionRetriesWithSameIdAndNeverAutomaticallyLoops() = runTest {
        val wire = FakeComposerWire().apply { attach() }; val store = MemoryOutbox()
        val engine = OutboxEngine(wire, store, StandardTestDispatcher(testScheduler))
        try {
            engine.accept(listOf(submission())); runCurrent()
            wire.calls.first().result.completeExceptionally(WireException(Up.SUBMIT, "forbidden", "not writable")); runCurrent()
            assertEquals(SendState.REJECTED, engine.submissions.value.single().state)
            wire.disconnect(); runCurrent(); wire.attach(2); runCurrent(); assertEquals(1, wire.calls.size)
            engine.retry("message"); runCurrent(); assertEquals("message", wire.calls.last().frame.text("id"))
        } finally { engine.close() }
    }
    @Test fun restoringTransmittingIsUncertainAndExpiredRowsAreDropped() = runTest {
        val wire = FakeComposerWire(); val store = MemoryOutbox()
        store.rows["active"] = submission("active", SendState.TRANSMITTING)
        store.rows["expired"] = submission("expired", expires = 100)
        val engine = OutboxEngine(wire, store, StandardTestDispatcher(testScheduler), now = { 100 })
        try { engine.awaitReady(); assertEquals(SendState.UNCERTAIN, engine.submissions.value.single().state); assertFalse("expired" in store.rows) } finally { engine.close() }
    }
    @Test fun cancellationOfOfflineLocalEchoRemovesPersistentIntent() = runTest {
        val wire = FakeComposerWire(); val store = MemoryOutbox()
        val engine = OutboxEngine(wire, store, StandardTestDispatcher(testScheduler))
        try { engine.accept(listOf(submission())); engine.cancel("message"); wire.attach(); runCurrent(); assertTrue(store.rows.isEmpty()); assertTrue(wire.calls.isEmpty()) } finally { engine.close() }
    }
    @Test fun uncertainCancellationReconnectSendsCancelInsteadOfResubmit() = runTest {
        val wire = FakeComposerWire(); val store = MemoryOutbox().apply { rows["message"] = submission(state = SendState.UNCERTAIN) }
        val engine = OutboxEngine(wire, store, StandardTestDispatcher(testScheduler))
        try {
            engine.awaitReady(); engine.cancel("message"); wire.attach(); runCurrent()
            assertEquals(Up.CANCEL, wire.calls.single().type)
            wire.calls.single().result.complete(JsonObject(emptyMap())); runCurrent()
            assertTrue(store.rows.isEmpty())
        } finally { engine.close() }
    }
    @Test fun slowReceiptDoesNotBlockAnotherRecipientOrFeedReconciliation() = runTest {
        val wire = FakeComposerWire().apply { attach() }; val store = MemoryOutbox()
        val engine = OutboxEngine(wire, store, StandardTestDispatcher(testScheduler))
        try { engine.accept(listOf(submission("first"), submission("second"))); runCurrent(); assertEquals(2, wire.calls.size); engine.reconcileLanded("c0", setOf("first")); assertEquals("second", engine.submissions.value.single().messageId) } finally { engine.close() }
    }
    @Test fun acceptedWithoutFeedBecomesDelayedAndWrongIdIsRejected() = runTest {
        val wire = FakeComposerWire().apply { attach() }; val store = MemoryOutbox()
        val engine = OutboxEngine(wire, store, StandardTestDispatcher(testScheduler), delayedAfterMs = 100)
        try {
            engine.accept(listOf(submission("first"))); runCurrent(); wire.calls.single().result.complete(JsonObject(emptyMap())); runCurrent()
            advanceTimeBy(100); runCurrent(); assertEquals(SendState.DELAYED, engine.submissions.value.single().state)
            engine.accept(listOf(submission("second"))); runCurrent(); wire.calls.last().result.complete(json("message_id" to text("wrong"))); runCurrent()
            assertEquals(SendState.REJECTED, engine.submissions.value.last().state)
        } finally { engine.close() }
    }
    @Test fun draftMetadataSurvivesSerializationWhileUnsentTextDoesNot() = runTest {
        val store = MemoryOutbox()
        store.putDraft("c0", ComposerDraft(text = "private draft text", recipients = listOf(AGENT), reply = ReplyTarget("answer", AGENT.id, "agent"), attachments = listOf(ComposerAttachment("c0", "daemon://device/c0/f", "f"))))
        val restored = store.restoreDrafts().getValue("c0")
        assertEquals("", restored.text); assertEquals(listOf(AGENT), restored.recipients); assertEquals("answer", restored.reply!!.sourceId)
        assertFalse(Json.encodeToString(restored).contains("private draft text"))
    }
    @Test fun offlineDeadlineDropsJournalWithoutWaitingForReconnect() = runTest {
        val wire = FakeComposerWire(); val store = MemoryOutbox(); var clock = 10L
        val engine = OutboxEngine(wire, store, StandardTestDispatcher(testScheduler), now = { clock })
        try { engine.accept(listOf(submission(expires = 20))); clock = 20; advanceTimeBy(10); runCurrent(); assertTrue(store.rows.isEmpty()); assertTrue(wire.calls.isEmpty()) } finally { engine.close() }
    }
    @Test fun restoredChannelsNeedCurrentPermissionBeforeTheyCanTransmit() = runTest {
        val wire = FakeComposerWire().apply { attach() }; val store = MemoryOutbox(); var allowed = false
        val engine = OutboxEngine(wire, store, StandardTestDispatcher(testScheduler), mayTransmit = { allowed })
        try { engine.accept(listOf(submission())); runCurrent(); assertTrue(wire.calls.isEmpty()); allowed = true; engine.refresh(); runCurrent(); assertEquals(1, wire.calls.size) } finally { engine.close() }
    }
    @Test fun diskFailureDoesNotTransmitAndLeavesEditorIntentUnaccepted() = runTest {
        val wire = FakeComposerWire().apply { attach() }; val store = MemoryOutbox().apply { rejectWrites = true }
        val engine = OutboxEngine(wire, store, StandardTestDispatcher(testScheduler))
        try { try { engine.accept(listOf(submission())); fail() } catch (_: IllegalStateException) { }; assertTrue(wire.calls.isEmpty()) } finally { engine.close() }
    }
}

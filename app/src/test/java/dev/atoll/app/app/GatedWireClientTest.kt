package dev.atoll.app.app

import dev.atoll.app.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GatedWireClientTest {
    @Test fun futureFramesWaitForTheCheckedAttachAndOldFramesAreDropped() = runBlocking {
        val gate = GatedWireClient(FakeWire())
        val frame = feed(2)
        val ready = async(start = CoroutineStart.UNDISPATCHED) { gate.awaitReady(frame) }
        assertFalse(ready.isCompleted)
        gate.connection(WireState.Attached(1, JsonObject(emptyMap())))
        assertFalse(ready.isCompleted)
        gate.connection(WireState.Attached(2, JsonObject(emptyMap())))
        assertTrue(ready.await())
        assertFalse(gate.awaitReady(feed(1)))
        assertFalse(gate.receive(feed(1)))
        val delivered = async(start = CoroutineStart.UNDISPATCHED) { gate.frames.first() }
        assertTrue(gate.receive(frame))
        assertEquals(frame, delivered.await())
    }
    @Test fun aFrameQueuedDuringAnotherAttachIsRecheckedAndCheckpointsUseTheSameFence() = runBlocking {
        val gate = GatedWireClient(FakeWire())
        gate.connection(WireState.Attached(2, JsonObject(emptyMap())))
        assertTrue(gate.awaitReady(feed(2)))
        gate.connection(WireState.Connecting)
        val checkpoint = DownFrame.Checkpoint("c", 1, 9, 3)
        val future = async(start = CoroutineStart.UNDISPATCHED) { gate.awaitReady(checkpoint) }
        assertFalse(future.isCompleted)
        gate.connection(WireState.Attached(3, JsonObject(emptyMap())))
        assertTrue(future.await())
        assertFalse(gate.receive(feed(2)))
        assertTrue(gate.receive(checkpoint))
    }
    private fun feed(generation: Long) = DownFrame.Feed("", Row("c", 1, Envelope(JsonObject(emptyMap()))), generation)
    private class FakeWire : WireClient {
        override val state = MutableStateFlow<WireState>(WireState.Connecting)
        override val frames = MutableSharedFlow<DownFrame>()
        override suspend fun request(type: String, payload: JsonObject) = payload
        override suspend fun history(payload: JsonObject) = HistoryPage(emptyList(), payload)
    }
}

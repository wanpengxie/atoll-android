package dev.atoll.app.net

import dev.atoll.app.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ActorRequestTest {
    @Test fun callerChosenHoldRequestIdIsUsedForSubmitAndTerminalCorrelation() = runBlocking<Unit> {
        var submitted: JsonObject? = null
        val wire = object : WireClient {
            override val state = MutableStateFlow<WireState>(WireState.Attached(1, Envelope.EMPTY))
            override val frames = MutableSharedFlow<DownFrame>()
            override suspend fun request(type: String, payload: JsonObject): JsonObject {
                submitted = payload
                val terminal = Envelope(buildJsonObject {
                    put("id", "hold-answer"); put("parent_id", "edit-hold"); put("type", "agent.hold"); put("kind", "response")
                    put("payload", buildJsonObject { put("body", buildJsonObject { put("status", "completed"); put("value", Envelope.EMPTY) }) })
                })
                frames.emit(DownFrame.Feed("", Row("c", 1, terminal)))
                return Envelope.EMPTY
            }
            override suspend fun history(payload: JsonObject) = HistoryPage(emptyList(), Envelope.EMPTY)
        }
        assertEquals(Envelope.EMPTY, wire.actorRequest("c", "agent:a", "agent.hold", Envelope.EMPTY, messageId = "edit-hold"))
        assertEquals("edit-hold", submitted!!.getValue("id").string())
        assertEquals(0, wire.frames.subscriptionCount.value)
    }
    @Test fun lostConnectionFailsAnActorCallAfterItsSubmitReceipt() = runBlocking<Unit> {
        val submitted = CompletableDeferred<Unit>()
        val wire = object : WireClient {
            override val state = MutableStateFlow<WireState>(WireState.Attached(1, Envelope.EMPTY))
            override val frames = MutableSharedFlow<DownFrame>()
            override suspend fun request(type: String, payload: JsonObject): JsonObject { submitted.complete(Unit); return Envelope.EMPTY }
            override suspend fun history(payload: JsonObject) = HistoryPage(emptyList(), Envelope.EMPTY)
        }
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { wire.actorRequest("c", "peer:storage", "storage.get_url", Envelope.EMPTY) }.exceptionOrNull()
        }
        submitted.await()
        wire.state.value = WireState.Disconnected("network gone", 500)
        assertEquals("closed", (withTimeout(1_000) { result.await() } as WireException).code)
        assertEquals(0, wire.frames.subscriptionCount.value)
    }
}

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package dev.atoll.app.compose

import dev.atoll.app.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AgentRequestsTest {
    @Test fun receiptIsNotParameterResultAndSubscriptionPrecedesSubmit() = runTest {
        val wire = FakeComposerWire().apply { attach() }
        val request = async { WireActorQueries(wire).request("c0", AGENT.id, "agent.options", Envelope.EMPTY) }
        runCurrent()
        val requestId = wire.calls.single().frame.text("id")
        wire.calls.single().result.complete(json("message_id" to text(requestId))); runCurrent()
        assertFalse(request.isCompleted)
        val envelope = Envelope(buildJsonObject { put("id", "answer"); put("parent_id", requestId); put("kind", "response"); put("type", "agent.options"); put("sender", json("id" to text(AGENT.id))); put("payload", json("body" to json("status" to text("completed"), "value" to json("models" to JsonArray(emptyList()))))) })
        wire.frames.emit(DownFrame.Feed("", Row("c0", 1, envelope)))
        assertEquals(JsonArray(emptyList()), request.await()["models"])
    }
    @Test fun catalogContextAndDescribeFallbackPreserveLegalModelEffortPairs() {
        val options = Json.parseToJsonElement("""{"models":[{"value":"model-a","label":"A","efforts":[{"value":"high","label":"高"},{"value":"low"}]},{"value":"model-b"}],"current":{"model":"model-a","effort":"high"},"client":{"version":"1.2"}}""").jsonObject
        val parsed = AgentParameterParser.options(AGENT.id, options)
        assertEquals(3, parsed.choices.size)
        val withContext = AgentParameterParser.context(parsed, Json.parseToJsonElement("""{"usage":{"model":"model-a","effort":"low","context_tokens":25,"context_window":100}}""").jsonObject)
        assertEquals(25, withContext.percent); assertEquals("low", withContext.current!!.effort)
        assertEquals(25L, AgentParameterParser.context(withContext, JsonObject(emptyMap())).contextTokens)
        val manifest = Json.parseToJsonElement("""{"words":{"agent.select":{"input_schema":{"oneOf":[{"properties":{"model":{"const":"fallback","title":"Fallback"},"effort":{"const":"max"}}}]}}}}""").jsonObject
        assertEquals(listOf(ModelChoice("fallback", "max", "Fallback", "max")), AgentParameterParser.options(AGENT.id, JsonObject(emptyMap()), manifest).choices)
    }
    @Test fun retiredWorldCannotReplayBeforeHostRecreatesViewModel() = runTest {
        val wire = FakeComposerWire(); val store = MemoryOutbox()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vm = ComposerViewModel("owner", wire, store, AttachmentUploader { _, _ -> error("unused") }, dispatcher, dispatcher,
            actorQueries = ActorQueries { _, _, _, _ -> Envelope.EMPTY }, serverBoot = "old-world")
        try {
            vm.setChannel("c0", HUMAN.id, listOf(HUMAN, AGENT)); vm.updateText("hello"); vm.send(); runCurrent()
            wire.state.value = WireState.Attached(1, json("boot" to text("old-world"))); runCurrent()
            assertEquals(1, wire.calls.size)
            wire.calls.single().result.completeExceptionally(WireException(Up.SUBMIT, "closed", "receipt lost")); runCurrent()
            assertEquals(SendState.UNCERTAIN, store.rows.values.single().state)
            wire.state.value = WireState.Attached(2, json("boot" to text("new-world"))); runCurrent()
            assertEquals(1, wire.calls.size)
        } finally { vm.close() }
    }
    @Test fun duplicateEnterIsAcceptedOnceAndDraftsStayPerChannel() = runTest {
        val wire = FakeComposerWire(); val store = MemoryOutbox()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val vm = ComposerViewModel("owner", wire, store, AttachmentUploader { _, _ -> error("unused") }, dispatcher, dispatcher)
        try {
            vm.setChannel("c0", HUMAN.id, listOf(HUMAN, AGENT)); vm.updateText("hello"); vm.send(); vm.send(); runCurrent()
            assertEquals(1, store.rows.size)
            vm.send("hello"); runCurrent(); assertEquals(1, store.rows.size)
            assertEquals("", vm.state.value.draft.text)
            vm.updateText("draft one"); vm.setChannel("c1", HUMAN.id, listOf(HUMAN, AGENT)); vm.updateText("draft two"); vm.setChannel("c0", HUMAN.id, listOf(HUMAN, AGENT))
            assertEquals("draft one", vm.state.value.draft.text)
            vm.setChannelWritable("c0", false)
            assertFalse(vm.state.value.writable)
            vm.send(); runCurrent(); assertEquals(1, store.rows.size)
        } finally { vm.close() }
    }
}

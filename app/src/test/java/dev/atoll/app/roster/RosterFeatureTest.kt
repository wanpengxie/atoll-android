package dev.atoll.app.roster

import dev.atoll.app.app.WorkspaceServices
import dev.atoll.app.core.*
import dev.atoll.app.net.ObsClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

private class FakeWire : WireClient {
    override val state = MutableStateFlow<WireState>(WireState.Attached(1, MemberConfig.empty))
    override val frames = MutableSharedFlow<DownFrame>()
    val writes = mutableListOf<JsonObject>()
    var resourceReplies = ArrayDeque<JsonObject>()
    var replies: (String, JsonObject) -> JsonElement = { _, _ -> MemberConfig.empty }
    override suspend fun request(type: String, payload: JsonObject): JsonObject {
        writes += payload
        if (type == Up.RESOURCE) return resourceReplies.removeFirst()
        val word = payload.text("msg_type")
        val value = replies(word, payload.obj("payload"))
        frames.emit(DownFrame.Feed("", Row("c", 1, Envelope(buildJsonObject {
            put("id", "reply-${writes.size}"); put("kind", "response"); put("parent_id", payload.text("id")); put("type", word)
            put("payload", buildJsonObject { put("body", buildJsonObject { put("status", "completed"); put("value", value) }) })
        }))))
        return MemberConfig.empty
    }
    override suspend fun history(payload: JsonObject) = HistoryPage(emptyList(), MemberConfig.empty)
}
private class FakePort : RosterPort {
    var reads = 0
    var observation = MemberConfig.parse("""{"items":[{"declared":{"id":"agent:a","kind":"agent","name":"A","body":"class agent"}},{"declared":{"id":"agent:b","kind":"agent","name":"B"}}],"complete":true}""")
    var observationGate: CompletableDeferred<JsonObject>? = null
    val calls = mutableListOf<Pair<String, JsonObject>>()
    var actorGate: CompletableDeferred<JsonObject>? = null
    var info = MemberConfig.parse("""{"class":"agent","body":{"class":"agent"},"params":{"keep":"已隐藏","x":1},"requires":["agent.ask"],"own_config":{"values":{"agent":"codex","keep":"已隐藏"},"desired_host":"mini"},"effective":{"api_key":"${'$'}required:key"},"sources":{"api_key":"actor"},"missing":[{"key":"service.api_key","hint":"测试值"}]}""")
    override suspend fun roster(): JsonObject { reads++; return observationGate?.await() ?: observation }
    override suspend fun actor(target: String, word: String, payload: JsonObject): JsonObject {
        calls += word to payload
        actorGate?.let { return it.await() }
        return when (word) {
            "system.member.get" -> info
            "actor.describe" -> MemberConfig.parse("""{"words":{"agent.demo":{"description":"演示","input_schema":{"type":"object"}}}}""")
            "system.class.list" -> MemberConfig.parse("""{"status":"completed","value":[{"class":"agent","kind":"agent"},{"class":"codex","kind":"agent"},{"class":"echo","kind":"tool"}]}""")
            else -> MemberConfig.parse("""{"revision":3,"description_revision":4,"echo":true}""")
        }
    }
    override suspend fun descriptions() = listOf(MemberConfig.parse("""{"id":"writer","version":2,"name":"writer"}"""))
    override suspend fun devices() = listOf(MemberConfig.parse("""{"id":"mini","name":"Mac mini"}"""))
    override suspend fun globalNames() = listOf("test_key")
}

@OptIn(ExperimentalCoroutinesApi::class)
class RosterFeatureTest {
    @Test fun productionPortReadsOnlyNamesAndEveryWriteGoesThroughFakeWireAndLedgerTerminal() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            val wire = FakeWire(); val http = OkHttpClient()
            val services = WorkspaceServices(server.url("/").toString(), "root", emptyMap(), wire, ObsClient(server.url("/").toString(), http), http)
            val port = RosterRepository(services, "c")
            server.enqueue(MockResponse().setBody("""{"items":[],"complete":true}"""))
            assertTrue(port.roster().bool("complete") == true)
            assertEquals("/obs/channel/c/actors", server.takeRequest().path)
            wire.replies = { _, _ -> MemberConfig.parse("""{"revision":5}""") }
            val body = MemberConfig.configChange("agent:a", MemberConfig.empty, "mini", "{\"agent\":\"codex\"}").payload
            assertEquals(5, port.actor("system", "system.member.config.set", body).number("revision"))
            assertEquals("system.member.config.set", wire.writes.last().text("msg_type"))
            assertEquals(JsonArray(listOf(JsonPrimitive("system"))), wire.writes.last()["audience"])
            assertEquals(body, wire.writes.last()["payload"])
            wire.resourceReplies.add(MemberConfig.parse("""{"items":[{"id":"global/b"},{"id":"global/BAD"}],"next":"page2"}"""))
            wire.resourceReplies.add(MemberConfig.parse("""{"items":[{"id":"global/a"}],"next":null}"""))
            assertEquals(listOf("a", "b"), port.globalNames())
            val resources = wire.writes.filter { it.text("op") == "list" }
            assertEquals(2, resources.size)
            assertTrue(resources.all { it.obj("query").text("prefix") == "global/" && "with_content" !in it })
            assertEquals("page2", resources[1].obj("query").text("cursor"))
            http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll()
        }
    }
    @Test fun capabilityAndConfigAreManualAndLateReplyCannotCrossSelectedActor() = runTest {
        val wire = FakeWire(); val port = FakePort(); val vm = RosterViewModel("c", wire, port, dispatcher = StandardTestDispatcher(testScheduler))
        try {
            runCurrent(); vm.select("agent:a"); runCurrent()
            assertTrue(port.calls.isEmpty())
            val gate = CompletableDeferred<JsonObject>(); port.actorGate = gate
            vm.readMember(); runCurrent(); assertTrue(vm.state.value.memberBusy)
            vm.select("agent:b"); runCurrent(); gate.complete(port.info); runCurrent()
            assertEquals("agent:b", vm.state.value.selected!!.id); assertNull(vm.state.value.member)
            assertFalse(vm.state.value.memberBusy)
        } finally { vm.close() }
    }
    @Test fun staleObservationCannotBecomeAuthorityAfterReconnect() = runTest {
        val wire = FakeWire(); val port = FakePort(); val gate = CompletableDeferred<JsonObject>(); port.observationGate = gate
        val vm = RosterViewModel("c", wire, port, "human:old", StandardTestDispatcher(testScheduler))
        try {
            runCurrent(); wire.state.value = WireState.Disconnected("test", 0); runCurrent()
            gate.complete(port.observation); runCurrent()
            assertFalse(vm.state.value.current); assertTrue(vm.state.value.rows.isEmpty()); assertEquals("", vm.state.value.selfId)
            port.observationGate = null
            wire.state.value = WireState.Attached(2, MemberConfig.parse("""{"memberships":[{"channel_id":"c","actor_id":"human:new"}]}""")); runCurrent()
            assertTrue(vm.state.value.current); assertEquals("human:new", vm.state.value.selfId)
        } finally { vm.close() }
    }
    @Test fun memberEventsCoalesceAndFailedWritesDoNotRefresh() = runTest {
        val wire = FakeWire(); val port = FakePort(); val vm = RosterViewModel("c", wire, port, dispatcher = StandardTestDispatcher(testScheduler))
        try {
            runCurrent(); assertEquals(1, port.reads)
            repeat(3) { wire.frames.emit(DownFrame.Feed("", Row("c", it.toLong(), Envelope(MemberConfig.parse("""{"type":"system.member.created","kind":"event"}"""))))) }
            runCurrent(); advanceTimeBy(299); runCurrent(); assertEquals(1, port.reads)
            advanceTimeBy(1); runCurrent(); assertEquals(2, port.reads)
            wire.frames.emit(DownFrame.Feed("", Row("c", 4, Envelope(MemberConfig.parse("""{"type":"system.member.set","kind":"response","payload":{"body":{"status":"failed"}}}""")))))
            runCurrent(); advanceTimeBy(301); runCurrent(); assertEquals(2, port.reads)
        } finally { vm.close() }
    }
    @Test fun entryAndConfigSavesEmitOnlyMinimalPatchesAndRefreshOnSuccess() = runTest {
        val wire = FakeWire(); val port = FakePort(); val vm = RosterViewModel("c", wire, port, dispatcher = StandardTestDispatcher(testScheduler))
        try {
            runCurrent(); vm.updateAccess(true, "human:self"); vm.select("agent:a"); vm.readMember(); runCurrent()
            vm.openEditor("entry"); runCurrent(); val entry = vm.state.value.editor!!
            vm.updateEditor(entry.copy(sourceMode = "actor", reference = "writer@2", text = "{\"keep\":\"已隐藏\",\"x\":2}", requires = "")); vm.saveEditor(); runCurrent()
            assertEquals(MemberConfig.parse("""{"member":"agent:a","body":{"actor":"writer@2"},"params":{"x":2},"requires":null}"""), port.calls.first { it.first == "system.member.set" }.second)
            assertNull(vm.state.value.editor); assertTrue(vm.state.value.notice.contains("第 4 版"))
            vm.openEditor("config"); runCurrent()
            assertEquals(listOf("codex"), vm.state.value.choices.agentClasses)
            val config = vm.state.value.editor!!
            vm.updateEditor(config.copy(host = "", text = "{\"agent\":\"codex\",\"keep\":\"已隐藏\",\"new\":1}")); vm.saveEditor(); runCurrent()
            assertEquals(MemberConfig.parse("""{"member":"agent:a","desired_host":"","values":{"new":1}}"""), port.calls.first { it.first == "system.member.config.set" }.second)
            assertTrue(port.calls.count { it.first == "system.member.get" } >= 3)
        } finally { vm.close() }
    }
    @Test fun placeholdersAndArbitraryInvocationRequireWritableAndDeclaredWord() = runTest {
        val wire = FakeWire(); val port = FakePort(); val vm = RosterViewModel("c", wire, port, dispatcher = StandardTestDispatcher(testScheduler))
        try {
            runCurrent(); vm.select("agent:a"); vm.readMember(); vm.describe(); runCurrent()
            vm.fillPlaceholder("service.api_key", "mock-only-value"); vm.invoke("agent.demo", "{}"); runCurrent()
            assertFalse(port.calls.any { it.first == "system.member.config.set" || it.first == "agent.demo" })
            vm.updateAccess(true, "self"); vm.fillPlaceholder("service.api_key", "mock-only-value"); runCurrent()
            assertEquals(MemberConfig.parse("""{"member":"agent:a","values":{"service":{"api_key":"mock-only-value"}}}"""), port.calls.first { it.first == "system.member.config.set" }.second)
            vm.invoke("not-declared", "{}"); vm.invoke("agent.demo", "[1]"); runCurrent()
            assertFalse(port.calls.any { it.first == "not-declared" || it.first == "agent.demo" })
            vm.invoke("agent.demo", "{\"depth\":1}"); runCurrent()
            assertEquals(MemberConfig.parse("{\"depth\":1}"), port.calls.first { it.first == "agent.demo" }.second)
            assertNotNull(vm.state.value.invocationResult)
        } finally { vm.close() }
    }
    @Test fun openingConfigLoadsGlobalNamesOnceAndReferenceInsertionRetainsSelection() = runTest {
        val wire = FakeWire(); val port = FakePort(); val vm = RosterViewModel("c", wire, port, dispatcher = StandardTestDispatcher(testScheduler))
        try {
            runCurrent(); vm.updateAccess(true, "self"); vm.select("agent:a"); vm.readMember(); runCurrent(); vm.openEditor("config"); runCurrent()
            val editor = vm.state.value.editor!!
            vm.updateEditor(editor.copy(text = "{\"key\": }", selectionStart = 8, selectionEnd = 8)); vm.insertReference("test_key"); runCurrent()
            val value = vm.state.value.editor!!
            assertEquals(JsonPrimitive("\$global.test_key"), MemberConfig.parse(value.text)["key"])
            assertEquals(8 + "\"\$global.test_key\"".length, value.selectionStart)
            vm.readMember(); runCurrent(); assertEquals(editor.info, vm.state.value.member)
        } finally { vm.close() }
    }
    @Test fun pendingWritePreventsDuplicateAndItsReplyCannotAffectANewSelectedMember() = runTest {
        val wire = FakeWire(); val port = FakePort(); val vm = RosterViewModel("c", wire, port, dispatcher = StandardTestDispatcher(testScheduler))
        try {
            runCurrent(); vm.updateAccess(true, "self"); vm.select("agent:a"); vm.readMember(); runCurrent(); vm.openEditor("entry"); runCurrent()
            vm.updateEditor(vm.state.value.editor!!.copy(requires = "")); runCurrent()
            val gate = CompletableDeferred<JsonObject>(); port.actorGate = gate
            vm.saveEditor(); vm.saveEditor(); runCurrent()
            assertEquals(1, port.calls.count { it.first == "system.member.set" })
            assertTrue(vm.state.value.writeBusy)
            vm.select("agent:b"); runCurrent()
            gate.complete(MemberConfig.parse("{\"description_revision\":99}")); runCurrent()
            assertEquals("agent:b", vm.state.value.selected!!.id)
            assertEquals("", vm.state.value.notice); assertNull(vm.state.value.member)
        } finally { vm.close() }
    }
    @Test fun failedSaveKeepsEditorAndUnsavedJSONAndDoesNotReadConfigurationAgain() = runTest {
        val wire = FakeWire(); val port = FakePort(); val vm = RosterViewModel("c", wire, port, dispatcher = StandardTestDispatcher(testScheduler))
        try {
            runCurrent(); vm.updateAccess(true, "self"); vm.select("agent:a"); vm.readMember(); runCurrent(); vm.openEditor("entry"); runCurrent()
            vm.updateEditor(vm.state.value.editor!!.copy(requires = "")); runCurrent()
            val reads = port.calls.count { it.first == "system.member.get" }
            val gate = CompletableDeferred<JsonObject>(); port.actorGate = gate
            vm.saveEditor(); runCurrent(); gate.completeExceptionally(WireException("submit", "forbidden", "test-only rejection")); runCurrent()
            assertNotNull(vm.state.value.editor); assertEquals("", vm.state.value.editor!!.requires)
            assertFalse(vm.state.value.writeBusy); assertTrue(vm.state.value.detailError.contains("forbidden"))
            assertEquals(reads, port.calls.count { it.first == "system.member.get" })
        } finally { vm.close() }
    }

}

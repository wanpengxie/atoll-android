package dev.atoll.app.governance

import dev.atoll.app.core.*
import dev.atoll.app.net.ObsClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

private fun obj(json: String) = Json.parseToJsonElement(json).jsonObject
private class FakeWire : WireClient {
    override val state = MutableStateFlow<WireState>(WireState.Attached(1, Envelope.EMPTY))
    override val frames = MutableSharedFlow<DownFrame>()
    val requests = mutableListOf<Pair<String, JsonObject>>()
    var failed = false
    var replies = ArrayDeque<JsonObject>()
    override suspend fun request(type: String, payload: JsonObject): JsonObject {
        requests += type to payload
        if (type == Up.SUBMIT) {
            val body = if (failed) obj("""{"status":"failed","error_code":"access_denied","detail":"denied"}""")
                else obj("""{"status":"completed","value":{"channel_id":"child"}}""")
            frames.emit(DownFrame.Feed("", Row("c", 1, Envelope(buildJsonObject {
                put("id", "answer"); put("parent_id", payload["id"]!!); put("kind", "response"); put("type", payload["msg_type"]!!)
                put("payload", buildJsonObject { put("body", body) })
            }))))
        }
        return replies.removeFirstOrNull() ?: Envelope.EMPTY
    }
    override suspend fun history(payload: JsonObject) = HistoryPage(emptyList(), Envelope.EMPTY)
}
class GovernanceProtocolTest {
    private fun mutations(): List<Pair<GovernanceCommand, JsonObject>> = listOf(
        GovernanceCommands.memberCreate("codex", true, "worker") to obj("""{"name":"worker","body":{"class":"codex"}}"""),
        GovernanceCommands.memberCreate("actor:research@2", false) to obj("""{"body":{"actor":"actor:research@2"}}"""),
        GovernanceCommands.admit("alice") to obj("""{"principal":"alice"}"""),
        GovernanceCommands.memberDelete("agent:a") to obj("""{"member":"agent:a"}"""),
        GovernanceCommands.memberRestart("agent:a") to obj("""{"member":"agent:a"}"""),
        GovernanceCommands.channelSet("c", "purpose", true) to obj("""{"channel_id":"c","description":"purpose","serving":1}"""),
        GovernanceCommands.channelDelete("c") to obj("""{"channel_id":"c"}"""),
        GovernanceCommands.channelCreate(ChannelCreateForm("child", "c", "root", setOf("alice","root"))) to obj("""{"name":"child","parent":"c","humans":["root","alice"]}"""),
        GovernanceCommands.deviceCreate("phone") to obj("""{"name":"phone"}"""),
        GovernanceCommands.deviceAttach("c", "mac", true) to obj("""{"channel_id":"c","device_id":"mac"}"""),
        GovernanceCommands.deviceAttach("c", "mac", false) to obj("""{"channel_id":"c","device_id":"mac"}"""),
        GovernanceCommands.deviceDelete("mac") to obj("""{"device_id":"mac"}"""),
        GovernanceCommands.actorDescriptionCreate("id", "research", "codex", "purpose", obj("""{"default":2}"""), false) to obj("""{"id":"id","name":"research","class":"codex","description":"purpose","params":{"default":2},"configurable":false}"""),
        GovernanceCommands.actorDescriptionRetire("id", 3) to obj("""{"id":"id","version":3}"""),
    )
    @Test fun everyMutationRoutesToCurrentChannelSystemAndWaitsForTerminal() = runBlocking {
        for ((command, payload) in mutations()) {
            assertEquals(command.word, payload, command.payload)
            val wire = FakeWire(); val repo = GovernanceRepository(wire, ObsClient("https://mock.invalid", OkHttpClient()))
            var accepted = false
            assertEquals("child", repo.execute("c", command, "chosen-id", { accepted = true }).text("channel_id"))
            assertTrue(accepted)
            val request = wire.requests.single()
            assertEquals(Up.SUBMIT, request.first)
            assertEquals("c", request.second.text("channel_id")); assertEquals("chosen-id", request.second.text("id"))
            assertEquals(JsonArray(listOf(JsonPrimitive("system"))), request.second["audience"])
            assertEquals(command.word, request.second.text("msg_type")); assertEquals(payload, request.second["payload"])
            assertEquals(0, wire.frames.subscriptionCount.value)
        }
    }
    @Test fun everyMutationPropagatesTerminalFailure() = runBlocking {
        for ((command, _) in mutations()) {
            val wire = FakeWire().apply { failed = true }
            val error = runCatching { GovernanceRepository(wire, ObsClient("https://mock.invalid", OkHttpClient())).execute("c", command) }.exceptionOrNull()
            assertEquals(command.word, "access_denied", (error as WireException).code)
            assertEquals(0, wire.frames.subscriptionCount.value)
        }
    }
    @Test fun channelStartingPointsRemoveOldEntryIdsAndAlwaysIncludeCaller() {
        val form = ChannelCreateForm("child", "c", "root", purpose = "hi", start = ChannelStart.Pick,
            members = listOf(obj("""{"id":"old","name":"worker","body":{"class":"codex"}}""")))
        assertEquals(obj("""{"name":"child","parent":"c","humans":["root"],"description":{"description":"hi","members":[{"name":"worker","body":{"class":"codex"}}]}}"""), GovernanceCommands.channelCreate(form).payload)
        assertEquals("source", GovernanceCommands.channelCreate(form.copy(start = ChannelStart.Copy, copyFrom = "source")).payload.text("copy_from"))
        assertFalse(GovernanceCommands.channelCreate(form.copy(start = ChannelStart.Copy, copyFrom = "source")).payload.containsKey("description"))
        assertTrue(runCatching { GovernanceCommands.channelCreate(form.copy(name = "INVALID")) }.isFailure)
        assertTrue(runCatching { GovernanceCommands.deviceDelete("local-device") }.isFailure)
        assertTrue(runCatching { GovernanceCommands.admit("guest") }.isFailure)
    }
    @Test fun globalKeysPageNamesOnlyAndWriteCreateDeletePayloads() = runBlocking {
        val wire = FakeWire().apply { replies.add(obj("""{"items":[{"id":"global/z","value":"never read"}],"next":"page2"}""")); replies.add(obj("""{"items":[{"id":"global/a"},{"id":"file"}]}""")) }
        val repo = GovernanceRepository(wire, ObsClient("https://mock.invalid", OkHttpClient())); val keys = GlobalKeys(repo, "c")
        assertEquals(listOf("a","z"), keys.names())
        assertEquals("page2", wire.requests.last().second.obj("query").text("cursor"))
        wire.requests.clear(); wire.replies.add(obj("""{"exists":false}"""))
        keys.save("key", "secret", false)
        assertEquals(listOf("stat","create"), wire.requests.map { it.second.text("op") })
        assertEquals(obj("""{"channel_id":"c","op":"create","resource_id":"global/key","args":"secret"}"""), wire.requests.last().second)
        keys.save("key", "changed", true); assertEquals("write", wire.requests.last().second.text("op"))
        keys.delete("key"); assertEquals(obj("""{"channel_id":"c","op":"delete","resource_id":"global/key"}"""), wire.requests.last().second)
        assertFalse(wire.requests.any { it.second.text("op") == "read" })
    }
    @Test fun rejectedResourceOperationsNeverLookSuccessfulAndCreateConflictFallsBackToWrite() = runBlocking {
        for (operation in listOf("create","write","delete")) {
            val wire = FakeWire(); val keys = GlobalKeys(GovernanceRepository(wire, ObsClient("https://mock.invalid", OkHttpClient())), "c")
            if (operation == "create") wire.replies.add(obj("""{"exists":false}"""))
            wire.replies.add(obj("""{"status":"rejected","detail":"access_denied"}"""))
            val failure = runCatching { if (operation == "delete") keys.delete("key") else keys.save("key", "value", operation == "write") }.exceptionOrNull()
            assertEquals("access_denied", (failure as WireException).code)
        }
        val wire = FakeWire().apply { replies.add(obj("""{"exists":false}""")); replies.add(obj("""{"status":"rejected","detail":"already_exists"}""")) }
        GlobalKeys(GovernanceRepository(wire, ObsClient("https://mock.invalid", OkHttpClient())), "c").save("key", "value", false)
        assertEquals(listOf("stat","create","write"), wire.requests.map { it.second.text("op") })
    }
    @Test fun creationReadinessRequiresAllFourStepsForTheReturnedId() {
        val start = ChannelCreation("req","c","child",0)
        val channels = listOf(obj("""{"declared":{"id":"child","open":true}}"""))
        assertFalse(start.converge(channels, setOf("child")).ready)
        val confirmed = start.copy(accepted = true).result(obj("""{"channel_id":"child"}"""))
        assertFalse(confirmed.converge(channels, emptySet()).ready)
        assertFalse(confirmed.converge(emptyList(), setOf("child")).ready)
        assertFalse(confirmed.converge(listOf(obj("""{"declared":{"id":"child","open":false}}""")), setOf("child")).ready)
        assertTrue(confirmed.converge(channels, setOf("child")).ready)
        assertFalse(confirmed.copy(failed=true).converge(channels, setOf("child")).ready)
        assertFalse(start.copy(accepted=true).result(Envelope.EMPTY).converge(channels, setOf("child")).ready)
    }
}

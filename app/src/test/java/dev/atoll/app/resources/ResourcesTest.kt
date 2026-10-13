package dev.atoll.app.resources

import dev.atoll.app.app.WorkspaceServices
import dev.atoll.app.core.*
import dev.atoll.app.net.ObsClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class,DelicateCoroutinesApi::class)
class ResourcesTest {
    @Test fun sixFormsMatchTheWebResourcePayloadContract() {
        for(op in resourceOperations.keys) {
            val payload=resourceFormPayload("c",op," kv:demo ",if(op in resourceWrites) "{\"value\":\"hello\"}" else "invalid unused JSON")
            assertEquals(JsonPrimitive(op),payload["op"]);assertEquals(JsonPrimitive("c"),payload["channel_id"])
            assertEquals(op!="list",payload.containsKey("resource_id"));assertEquals(op in setOf("create","write"),payload.containsKey("args"))
        }
        for(bad in listOf("{", "[]", "null", "12")) {
            try { resourceFormPayload("c","write","kv:demo",bad);fail("bad args accepted") } catch (_:IllegalArgumentException) { }
        }
        try { resourceFormPayload("c","read"," ","{}");fail("blank id") } catch (_:IllegalArgumentException) { }
    }
    @Test fun readonlyAndBusyGatesPreventWritesAndRejectedReceiptPreservesThePreviousResult()=runBlocking<Unit> {
        newSingleThreadContext("resource-test").use { dispatcher -> withContext(dispatcher) {
            val requests=mutableListOf<JsonObject>();var reply=buildJsonObject { put("exists",true) };var gate:CompletableDeferred<Unit>?=null
            val wire=object:WireClient {
                override val state=MutableStateFlow<WireState>(WireState.Attached(1,Envelope.EMPTY))
                override val frames=MutableSharedFlow<DownFrame>()
                override suspend fun request(type: String,payload: JsonObject):JsonObject { assertEquals(Up.RESOURCE,type);requests.add(payload);gate?.await();return reply }
                override suspend fun history(payload: JsonObject):HistoryPage=error("not history")
            }
            val http=OkHttpClient();val services=WorkspaceServices("http://localhost","root",mapOf("c" to "self"),wire,ObsClient("http://localhost",http),http)
            ResourcesViewModel(services,"c",dispatcher).use { vm ->
                vm.run("write","kv:demo","{}");assertTrue(requests.isEmpty());assertEquals("当前频道不可写",vm.state.value.error)
                vm.run("stat","kv:demo","{}").join();assertEquals(reply,vm.state.value.result)
                vm.context(true);gate=CompletableDeferred();val first=vm.run("write","kv:demo","{}")!!
                assertNull(vm.run("delete","kv:demo","{}"));assertEquals(1,requests.size)
                gate!!.complete(Unit);first.join();assertEquals(2,requests.size)
                reply=buildJsonObject { put("status","rejected");put("detail","denied") };gate=null
                vm.run("read","kv:demo","{}").join();assertEquals("denied",vm.state.value.error);assertEquals(JsonPrimitive(true),vm.state.value.result!!["exists"])
                wire.state.value=WireState.Disconnected("offline",400);yield();vm.run("read","kv:demo","{}");assertEquals(3,requests.size)
            }
        } }
    }
    private suspend fun Job?.join() { this?.join() ?: fail("operation did not start") }
}

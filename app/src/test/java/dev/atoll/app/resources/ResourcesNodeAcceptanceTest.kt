package dev.atoll.app.resources

import dev.atoll.app.app.WorkspaceServices
import dev.atoll.app.core.*
import dev.atoll.app.net.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.Proxy
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class,DelicateCoroutinesApi::class)
class ResourcesNodeAcceptanceTest {
    @Test fun sixKvOperationsOnOnlyTheIsolatedNode()=runBlocking<Unit> {
        val url=System.getenv("ATOLL_GOV_URL").orEmpty();val token=System.getenv("ATOLL_GOV_TOKEN").orEmpty();val channel=System.getenv("ATOLL_GOV_CHANNEL").orEmpty()
        assumeTrue(url.isNotEmpty() && token.isNotEmpty() && channel.isNotEmpty())
        require(url=="http://100.99.232.10:18080") { "Refusing writes outside the isolated node" }
        newSingleThreadContext("kv-acceptance").use { dispatcher -> withContext(dispatcher) {
            val http=OkHttpClient.Builder().proxy(Proxy.NO_PROXY).addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("Authorization","Bearer $token").build()) }.build()
            Wire(url,http,"KV isolated acceptance").use { wire ->
                wire.start();withTimeout(15000) { wire.state.filterIsInstance<WireState.Attached>().first() }
                val services=WorkspaceServices(url,"root",emptyMap(),wire,ObsClient(url,http),http)
                ResourcesViewModel(services,channel,dispatcher).use { vm ->
                    vm.context(true);val id="kv:android-w3-${UUID.randomUUID()}";var created=false
                    suspend fun run(op: String,args: String="{}"):JsonObject {
                        vm.run(op,id,args)!!.join();assertEquals(vm.state.value.error,"",vm.state.value.error);return vm.state.value.result!!
                    }
                    try {
                        run("create","{\"value\":\"hello\"}");created=true
                        assertTrue(run("read").toString().contains("hello"))
                        run("write","{\"value\":\"updated\"}");assertTrue(run("read").toString().contains("updated"))
                        assertEquals(JsonPrimitive(true),run("stat")["exists"])
                        assertTrue(run("list").toString().contains(id));run("delete");created=false
                        assertEquals(JsonPrimitive(false),run("stat")["exists"])
                        println("Isolated KV create/read/write/stat/list/delete passed")
                    } finally { if(created) runCatching { wire.request(Up.RESOURCE,resourceFormPayload(channel,"delete",id,"{}")) } }
                }
            }
        } }
    }
}

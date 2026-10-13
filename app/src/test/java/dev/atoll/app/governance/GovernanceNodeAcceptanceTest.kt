package dev.atoll.app.governance

import dev.atoll.app.app.WorkspaceServices
import dev.atoll.app.core.*
import dev.atoll.app.model.ChannelReplica
import dev.atoll.app.net.*
import dev.atoll.app.tasks.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

/** Opt-in writes are pinned to our disposable node; never the user's server. */
@OptIn(DelicateCoroutinesApi::class,ExperimentalCoroutinesApi::class)
class GovernanceNodeAcceptanceTest {
    @Test fun ownNodeGovernanceAndTasksAcceptance()=runBlocking<Unit> {
        val base=System.getenv("ATOLL_GOV_URL").orEmpty();val token=System.getenv("ATOLL_GOV_TOKEN").orEmpty()
        val parent=System.getenv("ATOLL_GOV_CHANNEL").orEmpty();val codex=System.getenv("ATOLL_GOV_CODEX").orEmpty()
        assumeTrue("Set ATOLL_GOV_* for isolated-node acceptance",base.isNotEmpty() && token.isNotEmpty() && parent.isNotEmpty() && codex.isNotEmpty())
        require(base=="http://100.99.232.10:18080") { "Refusing writes outside our own test node" }
        newSingleThreadContext("governance-acceptance").use { dispatcher -> withContext(dispatcher) {
            // The mbp JVM inherits a macOS HTTP proxy; route only this pinned tailnet test directly.
            val client=OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("Authorization","Bearer $token").build())
            }.build()
            Wire(base,client,"Governance isolated acceptance").use { wire ->
                wire.start();val attached=try { withTimeout(15000) { wire.state.filterIsInstance<WireState.Attached>().first() } } catch (failure:Exception) {
                    val state=wire.state.value
                    throw IllegalStateException("Attach failed: ${if (state is WireState.Disconnected) state.reason else state::class.simpleName}",failure)
                }
                fun selfIds(receipt:JsonObject)=(receipt["memberships"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().associate { it.text("channel_id") to it.text("actor_id") }
                var identities=selfIds(attached.receipt);require(identities[parent].orEmpty().isNotEmpty()) { "Missing parent membership" }
                val repo=GovernanceRepository(wire,ObsClient(base,client))
                val vm=GovernanceViewModel(repo,"root",wire.frames,dispatcher=dispatcher)
                vm.context(parent,identities[parent]!!,true,identities.keys)
                val membershipScope=CoroutineScope(SupervisorJob()+dispatcher)
                membershipScope.launch(start=CoroutineStart.UNDISPATCHED) { wire.frames.collect { f -> if (f is DownFrame.Memberships) { identities=selfIds(f.payload);vm.context(parent,identities[parent].orEmpty(),true,identities.keys) } } }
                val suffix=System.nanoTime().toString();val name="android-gov-$suffix";val key="android_gov_$suffix"
                var child="";var device="";var description="";var descriptionVersion=0;var creationRequest="";var childRetired=false
                val steps=mutableListOf<String>()
                suspend fun record(label:String,command:GovernanceCommand,channel:String=parent):JsonObject=repo.execute(channel,command).also { steps.add(label) }
                try {
                    vm.refresh();withTimeout(15000) { vm.state.first { !it.loading && it.directory.channels.isNotEmpty() } }
                    val requestId=vm.create(ChannelCreateForm(name,parent,"root",purpose="Native governance acceptance"))!!;creationRequest=requestId
                    val created=withTimeout(30000) { vm.creations.first { rows -> rows.any { it.requestId==requestId && (it.ready || it.failed) } }.first { it.requestId==requestId } }
                    child=created.targetId;assertFalse(created.error,created.failed);assertTrue(created.ready);steps.add("create: all four progress facts")
                    val entry=record("add workspace member",GovernanceCommands.memberCreate("workspace",true,"acceptance-worker"),child).obj("entry").text("id")
                    require(entry.isNotEmpty()) { "Missing created entry id" }
                    suspend fun member():JsonObject=withTimeout(15000) {
                        var found:JsonObject?=null
                        while(found==null) { found=repo.directory(child).actors.firstOrNull { it.declared().text("entry_id")==entry };if(found==null) delay(200) }
                        found
                    }
                    val before=member();record("restart workspace member",GovernanceCommands.memberRestart(before.directoryId()),child)
                    delay(400);record("remove workspace member",GovernanceCommands.memberDelete(member().directoryId()),child)
                    record("change description",GovernanceCommands.channelSet(child,"Updated native governance acceptance",true),child)
                    val read=repo.execute(child,GovernanceCommands.channelGet(child));assertEquals("Updated native governance acceptance",read.obj("description").obj("body").text("description"))
                    val keys=GlobalKeys(repo,child);keys.save(key,"dummy-one",false);assertTrue(key in keys.names());keys.save(key,"dummy-two",true);keys.delete(key);assertFalse(key in keys.names());steps.add("global key create/write/delete")
                    val actor=record("create actor description",GovernanceCommands.actorDescriptionCreate("","acceptance-$suffix","workspace","test",Envelope.EMPTY,true))
                    description=actor.text("id");descriptionVersion=actor.integer("version");require(description.isNotEmpty() && descriptionVersion>0) { "Missing description version" }
                    record("retire actor description",GovernanceCommands.actorDescriptionRetire(description,descriptionVersion));description=""
                    device=record("create device",GovernanceCommands.deviceCreate("acceptance-$suffix")).text("id");require(device.isNotEmpty()) { "Missing device id" }
                    record("attach device",GovernanceCommands.deviceAttach(child,device,true),child);record("detach device",GovernanceCommands.deviceAttach(child,device,false),child)
                    record("retire device",GovernanceCommands.deviceDelete(device));device=""
                    record("retire created child",GovernanceCommands.channelDelete(child));childRetired=true;child=""
                    val replica=ChannelReplica(parent,identities[parent].orEmpty())
                    val services=WorkspaceServices(base,"root",identities,wire,ObsClient(base,client),client)
                    val tasks=TasksViewModel(services,parent,store=MemoryKeyValueStore(),dispatcher=dispatcher)
                    val fold=membershipScope.launch(start=CoroutineStart.UNDISPATCHED) { wire.frames.collect { f -> if (f is DownFrame.Feed && f.row.channelId==parent) { replica.apply(RowBatch(parent,listOf(f.row),Origin.LIVE));tasks.update(replica.snapshot(),identities[parent].orEmpty(),writable=true) } } }
                    try {
                        tasks.update(replica.snapshot(),identities[parent].orEmpty(),writable=true)
                        val timer=tasks.schedule(300,"human.note",buildJsonObject { put("body",buildJsonObject { put("text","Native timer acceptance") }) })
                        withTimeout(15000) { tasks.state.first { it.timers.any { row -> row.timerId==timer && row.state=="fired" } } };steps.add("current-node human timer firing")
                        val actorRow=repo.directory(parent).actors.first { it.directoryId()==codex };assertEquals("class codex",actorRow.declared().text("body"))
                        val askId=UUID.randomUUID().toString()
                        wire.actorRequest(parent,codex,"agent.ask",buildJsonObject { put("text","Android native client acceptance test. Reply only OK, without tools.") },timeoutMs=90000,messageId=askId)
                        val fact=withTimeout(15000) { tasks.state.first { it.items.any { row -> row.id==askId && row.state=="completed" } }.items.first { it.id==askId } }
                        tasks.select(fact.key);assertEquals(askId,tasks.state.value.selectedItem?.requestId);steps.add("codex task list + detail fact")
                    } finally { fold.cancel();tasks.close() }
                    println("Isolated-node acceptance passed: ${steps.joinToString("; ")}")
                } finally {
                    if (!childRetired) { val target=child.ifEmpty { vm.creations.value.firstOrNull { it.requestId==creationRequest }?.targetId.orEmpty() };if (target.isNotEmpty()) runCatching { repo.execute(parent,GovernanceCommands.channelDelete(target)) } }
                    if (device.isNotEmpty()) runCatching { repo.execute(parent,GovernanceCommands.deviceDelete(device)) }
                    if (description.isNotEmpty() && descriptionVersion>0) runCatching { repo.execute(parent,GovernanceCommands.actorDescriptionRetire(description,descriptionVersion)) }
                    runCatching { GlobalKeys(repo,parent).delete(key) }
                    membershipScope.cancel();vm.close()
                }
            }
        } }
    }
}

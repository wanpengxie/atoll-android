package dev.atoll.app.governance

import dev.atoll.app.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GovernanceViewModelTest {
    private fun json(text: String)=Json.parseToJsonElement(text).jsonObject
    private class Port:GovernancePort {
        val commands=mutableListOf<GovernanceCommand>()
        var directory=GovernanceDirectory()
        var directoryCalls=0
        var channels=emptyList<JsonObject>()
        var channelsCalls=0
        val channelParents=mutableListOf<String>()
        var reply: suspend (GovernanceCommand,()->Unit)->JsonObject={ _,accepted -> accepted();Envelope.EMPTY }
        override suspend fun execute(channel: String,command: GovernanceCommand,id: String,accepted: ()->Unit):JsonObject { commands.add(command);return reply(command,accepted) }
        override suspend fun directory(channel: String):GovernanceDirectory { directoryCalls++;return directory }
        override suspend fun channels(parent: String):List<JsonObject> { channelsCalls++;channelParents.add(parent);return channels }
        override suspend fun resource(channel: String,payload: JsonObject)=Envelope.EMPTY
    }
    private fun model(port:Port,frames:MutableSharedFlow<DownFrame>,dispatcher:CoroutineDispatcher)=GovernanceViewModel(port,"root",frames,now={ 10L },dispatcher=dispatcher).also { it.context("c","self",true,setOf("c")) }
    private fun form(name: String="child")=ChannelCreateForm(name,"c","root")
    private fun terminal(id: String,status: String="completed",channel: String="c")=DownFrame.Feed("",Row(channel,2,Envelope(buildJsonObject {
        put("id","answer:$id");put("parent_id",id);put("kind","response");put("type","system.channel.create")
        put("payload",buildJsonObject { put("body",buildJsonObject { put("status",status);if (status=="completed") put("value",buildJsonObject { put("channel_id","child-id") }) else put("detail","denied") }) })
    })))
    @Test fun entryIdIsDistinctFromActorIdAndPreviouslyAddedPrincipalCannotConfirmAnotherEntry()=runTest {
        val port=Port();val frames=MutableSharedFlow<DownFrame>();val vm=model(port,frames,StandardTestDispatcher(testScheduler))
        try {
            port.directory=GovernanceDirectory(actors=listOf(json("""{"declared":{"id":"human:alice","principal":"alice"}}""")))
            vm.execute(GovernanceCommands.admit("alice"));runCurrent();assertTrue(vm.state.value.memberReady)
            port.reply={ _,accepted -> accepted();json("""{"entry":{"id":"entry-7"}}""") }
            vm.execute(GovernanceCommands.memberCreate("codex",true));runCurrent();assertFalse(vm.state.value.memberReady)
            assertEquals("entry-7",vm.state.value.introducedEntry);assertEquals("",vm.state.value.introducedPrincipal)
            port.directory=port.directory.copy(actors=listOf(json("""{"declared":{"id":"agent:runtime:123","entry_id":"entry-7"}}""")))
            vm.refresh();runCurrent();assertTrue(vm.state.value.memberReady)
            vm.execute(GovernanceCommands.admit("bob"));runCurrent();assertFalse(vm.state.value.memberReady);assertEquals("",vm.state.value.introducedEntry)
        } finally { vm.close() }
    }
    @Test fun acceptedTimeoutBlocksDuplicateCreationAndLateTerminalNeedsDirectoryAndMembership()=runTest {
        val port=Port();port.reply={ _,accepted -> accepted();withTimeout(1) { awaitCancellation() } }
        val frames=MutableSharedFlow<DownFrame>();val vm=model(port,frames,StandardTestDispatcher(testScheduler))
        try {
            vm.create(form());advanceTimeBy(2);runCurrent()
            val pending=vm.creations.value.single();assertTrue(pending.accepted);assertTrue(pending.uncertain);assertFalse(pending.failed)
            vm.create(form());runCurrent();assertEquals(1,port.commands.size)
            frames.emit(terminal(pending.requestId,channel="other"));runCurrent();assertEquals("",vm.creations.value.single().targetId)
            port.channels=listOf(json("""{"declared":{"id":"child-id"},"actual":{"measures":[{"name":"open","value":true}]}}"""))
            frames.emit(terminal(pending.requestId));runCurrent();assertFalse(vm.creations.value.single().ready)
            vm.context("c","self",true,setOf("c","child-id"));runCurrent()
            assertTrue(port.channelParents.isNotEmpty());assertEquals(setOf("c"),port.channelParents.toSet())
            val complete=vm.creations.value.single();assertTrue(complete.ready);assertFalse(complete.uncertain);assertEquals("",complete.error)
        } finally { vm.close() }
    }
    @Test fun explicitFailedTerminalPermitsNewAttemptAndFailureIsNotUncertain()=runTest {
        val port=Port();port.reply={ _,accepted -> accepted();throw WireException("submit","access_denied","denied") }
        val vm=model(port,MutableSharedFlow(),StandardTestDispatcher(testScheduler))
        try {
            vm.create(form());runCurrent();val failed=vm.creations.value.single();assertTrue(failed.failed);assertFalse(failed.uncertain)
            port.reply={ _,accepted -> accepted();json("""{"channel_id":"child-id"}""") }
            vm.create(form());runCurrent();assertEquals(2,port.commands.size);assertEquals(2,vm.creations.value.size)
        } finally { vm.close() }
    }
    @Test fun lateCanonicalTerminalBeforeTransportTimeoutIsNeverDowngraded()=runTest {
        val port=Port();port.reply={ _,accepted -> accepted();withTimeout(10) { awaitCancellation() } }
        val frames=MutableSharedFlow<DownFrame>();val vm=model(port,frames,StandardTestDispatcher(testScheduler))
        try {
            vm.create(form());runCurrent();val id=vm.creations.value.single().requestId
            frames.emit(terminal(id));runCurrent();advanceTimeBy(11);runCurrent()
            val operation=vm.creations.value.single();assertEquals("child-id",operation.targetId);assertFalse(operation.failed);assertFalse(operation.uncertain);assertEquals("",operation.error)
        } finally { vm.close() }
    }
    @Test fun transportLossWithoutReceiptIsUncertainAndReadonlyOrOtherParentNeverWrites()=runTest {
        val port=Port();port.reply={ _,_ -> throw WireException("submit","closed","socket closed") }
        val vm=model(port,MutableSharedFlow(),StandardTestDispatcher(testScheduler))
        try {
            vm.create(form().copy(parent="other"));vm.create(form().copy(selfPrincipal="other"));runCurrent();assertTrue(port.commands.isEmpty())
            vm.context("c","self",false,setOf("c"));vm.execute(GovernanceCommands.deviceCreate("phone"));vm.create(form());runCurrent();assertTrue(port.commands.isEmpty())
            vm.context("c","self",true,setOf("c"));vm.create(form());runCurrent();assertTrue(vm.creations.value.single().uncertain);assertFalse(vm.creations.value.single().failed)
            vm.create(form());runCurrent();assertEquals(1,port.commands.size)
        } finally { vm.close() }
    }
    @Test fun activityPortKeepsCreationAfterContextSwitchAndUnrelatedFeedDoesNotRestartPolling()=runTest {
        val port=Port();port.reply={ _,accepted -> accepted();json("""{"channel_id":"child-id"}""") }
        val frames=MutableSharedFlow<DownFrame>();val vm=model(port,frames,StandardTestDispatcher(testScheduler))
        try {
            val id=vm.create(form())!!;runCurrent()
            assertEquals(id,vm.activityOperations.value.single().source.text("requestId"))
            vm.context("next","next-self",true,setOf("c","next"));runCurrent()
            assertEquals("c",vm.activityOperations.value.single().channelId);assertEquals(1,vm.creations.value.size)
            advanceTimeBy(60_001);runCurrent();val count=port.channelsCalls;assertEquals(30,count)
            frames.emit(terminal("unrelated"));runCurrent();assertEquals(count,port.channelsCalls);assertEquals(id,vm.creations.value.single().requestId)
        } finally { vm.close() }
    }
    @Test fun contextSwitchClearsMemberReadAndCancelsOldDirectoryWithoutPublishingIt()=runTest {
        val port=Port();port.reply={ _,_ -> delay(100);json("""{"member":"old"}""") }
        val vm=model(port,MutableSharedFlow(),StandardTestDispatcher(testScheduler))
        try { vm.readMember("a");runCurrent();vm.context("next","next-self",true,setOf("next"));advanceTimeBy(101);runCurrent();assertNull(vm.state.value.memberView);assertFalse(vm.state.value.busy);assertEquals("next",vm.state.value.channel) } finally { vm.close() }
    }
}

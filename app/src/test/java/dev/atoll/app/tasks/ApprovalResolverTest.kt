package dev.atoll.app.tasks

import dev.atoll.app.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ApprovalResolverTest {
    private val self = ApprovalAuthority("me", true)
    private fun fact(channel: String = "c", id: String = "request") = TaskFact("approval:$channel:$id", id, channel,
        "approval", "允许发布？", state = "waiting", assigneeActorIds = listOf("me"),
        diagnostic = buildJsonObject { put("requestType", "human.approve") })

    @Test fun approvalsUseTheirOwnChannelAndShareOneClaimAcrossBothDecisions() = runBlocking {
        val wire = TaskTestWire()
        val started = CompletableDeferred<Unit>(); val released = CompletableDeferred<Unit>()
        wire.handle = { _, _ -> started.complete(Unit); released.await(); Envelope.EMPTY }
        val resolver = ApprovalResolver(wire)
        val item = fact("background")
        val first = launch { resolver.resolve(item, TaskAction.APPROVE, self, note = "已核对") }
        started.await()
        resolver.resolve(item, TaskAction.REJECT, self)
        assertEquals(1, wire.requests.size)
        assertEquals("submitting", resolver.state.value[item.key]?.state)
        val payload = wire.requests.single().second
        assertEquals(Up.RESOLVE, wire.requests.single().first)
        assertEquals("background", payload.text("channel_id"))
        assertEquals("request", payload.text("req_id"))
        assertEquals("approve", payload.text("decision"))
        assertEquals("已核对", payload.text("note"))
        released.complete(Unit); first.join()
        resolver.resolve(item, TaskAction.REJECT, self)
        assertEquals(1, wire.requests.size)
        assertEquals("已处理", resolver.state.value[item.key]?.reason)
    }

    @Test fun otherRequestsCanResolveWhileOneRequestIsWaitingForTheNetwork() = runBlocking {
        val wire = TaskTestWire(); val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        wire.handle = { _, payload -> if (payload.text("req_id") == "slow") { started.complete(Unit); release.await() }; Envelope.EMPTY }
        val resolver = ApprovalResolver(wire)
        val slow = launch { resolver.resolve(fact(id = "slow"), TaskAction.APPROVE, self) }
        started.await()
        withTimeout(1000) { resolver.resolve(fact(id = "fast"), TaskAction.REJECT, self) }
        assertEquals(2, wire.requests.size)
        release.complete(Unit); slow.join()
    }

    @Test fun membershipAudienceConnectionAndExpiryAreRequired() = runBlocking {
        val wire = TaskTestWire(); val resolver = ApprovalResolver(wire, now = { 100 })
        for ((item, authority) in listOf(fact() to self.copy(writable = false), fact() to self.copy(selfId = "other"),
            fact() to self.copy(selfId = ""), fact().copy(dueAt = "100") to self, fact().copy(state = "expired") to self)) {
            try { resolver.resolve(item, TaskAction.APPROVE, authority); fail("answer must be refused") } catch (_: IllegalArgumentException) { }
        }
        wire.state.value = WireState.Disconnected("offline", 0)
        try { resolver.resolve(fact(), TaskAction.APPROVE, self); fail("offline") } catch (_: IllegalArgumentException) { }
        assertTrue(wire.requests.isEmpty())
    }

    @Test fun alreadyProcessedFactsAndServerClosureAreDisplayedAsHandled() = runBlocking {
        val wire = TaskTestWire(); val resolver = ApprovalResolver(wire)
        val closed = fact(id = "closed").copy(state = "completed")
        resolver.resolve(closed, TaskAction.APPROVE, self)
        assertTrue(wire.requests.isEmpty()); assertEquals("已处理", resolver.state.value[closed.key]?.reason)
        wire.handle = { _, _ -> throw WireException(Up.RESOLVE, "already_closed", "request already closed") }
        resolver.resolve(fact(), TaskAction.REJECT, self)
        assertEquals("已处理", resolver.state.value[fact().key]?.reason)
        assertEquals("", resolver.state.value[fact().key]?.error)
    }

    @Test fun otherFailuresStayVisibleAndAllowRetry() = runBlocking {
        val wire = TaskTestWire(); val resolver = ApprovalResolver(wire)
        wire.handle = { _, _ -> throw WireException(Up.RESOLVE, "unavailable", "测试失败") }
        try { resolver.resolve(fact(), TaskAction.APPROVE, self); fail("must report error") } catch (_: WireException) { }
        assertEquals("failed", resolver.state.value[fact().key]?.state)
        assertEquals("测试失败", resolver.state.value[fact().key]?.error)
        wire.handle = { _, _ -> Envelope.EMPTY }
        resolver.resolve(fact(), TaskAction.REJECT, self)
        assertEquals("reject", wire.requests.last().second.text("decision"))
    }

    @Test fun closingATaskSurfaceReleasesItsInFlightClaim() = runBlocking {
        val wire = TaskTestWire(); val started = CompletableDeferred<Unit>()
        wire.handle = { _, _ -> started.complete(Unit); awaitCancellation() }
        val resolver = ApprovalResolver(wire)
        val action = launch { resolver.resolve(fact(), TaskAction.APPROVE, self) }
        started.await(); action.cancelAndJoin()
        assertEquals("ready", resolver.state.value[fact().key]?.state)
    }
}

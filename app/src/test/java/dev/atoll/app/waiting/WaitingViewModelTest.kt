package dev.atoll.app.waiting

import dev.atoll.app.compose.*
import dev.atoll.app.core.*
import dev.atoll.app.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class WaitingViewModelTest {
    private class Wire : WireClient {
        override val state = MutableStateFlow<WireState>(WireState.Attached(1, Envelope.EMPTY))
        override val frames = MutableSharedFlow<DownFrame>()
        override suspend fun request(type: String, payload: JsonObject) = Envelope.EMPTY
        override suspend fun history(payload: JsonObject) = HistoryPage(emptyList(), Envelope.EMPTY)
    }
    private class Port(val wire: Wire) : WaitingPort {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        var owner = ""
        var submitted: JsonObject? = null
        var deferHold: CompletableDeferred<Unit>? = null
        var holdAdmitted = CompletableDeferred<Unit>()
        var acceptReturned = CompletableDeferred<Unit>()
        var localAcceptance = CompletableDeferred<Unit>()
        var signalBeforeAccept = false
        var leaseUntil = System.currentTimeMillis() + 60_000
        var failCancel = false
        override suspend fun actor(actorId: String, type: String, payload: JsonObject, messageId: String): JsonObject {
            calls += type to payload
            return when (type) {
                "actor.describe" -> WaitingRulesTest().manifest()
                "agent.hold" -> { owner = messageId; holdAdmitted.complete(Unit); deferHold?.await(); Envelope.EMPTY }
                "agent.context" -> if (owner.isEmpty()) Envelope.EMPTY else buildJsonObject { put("frozen", buildJsonObject { put("held_by", owner); put("until", leaseUntil) }) }
                "agent.unhold" -> { if (owner == payload.text("expected_hold_id")) owner = ""; buildJsonObject { put("released", true) } }
                else -> Envelope.EMPTY
            }
        }
        override suspend fun cancel(targetId: String) { calls += "wire.cancel" to payload("target" to targetId); if (failCancel) error("test cancellation failure") }
        override suspend fun cancelLocal(targetId: String) { calls += "outbox.cancel" to payload("target" to targetId) }
        override suspend fun submit(frame: JsonObject, text: String, targetLabel: String): String {
            submitted = frame
            if (signalBeforeAccept) signal("queued")
            localAcceptance.await()
            acceptReturned.complete(Unit)
            return frame.text("id")
        }
        suspend fun signal(status: String) {
            val request = submitted ?: error("no replacement submitted")
            wire.frames.emit(DownFrame.Feed("", Row("c", 5, Envelope(buildJsonObject {
                put("kind", "response"); put("id", "replacement-progress"); put("parent_id", request.text("id")); put("type", "agent.replace")
                put("sender", payload("id" to "agent:a")); put("payload", buildJsonObject { put("body", payload("status" to status)) })
            }))))
        }
    }
    private fun snapshot(turns: List<Turn> = listOf(WaitingRulesTest().turn())) = TimelineSnapshot("c", 1, emptyList(), turns, emptyList(), 1, 2)
    private fun vm(wire: Wire, port: Port, snapshot: TimelineSnapshot = snapshot()) = WaitingViewModel("c", wire, port, snapshot, "self", Dispatchers.Unconfined).also {
        it.updateAccess(true, "self", mapOf("agent:a" to "A"))
    }
    private suspend fun WaitingViewModel.editing() { requestEdit("target"); withTimeout(2_000) { state.first { it.edit.phase == EditPhase.EDITING } } }

    @Test fun pureLocalQueueCanBeCancelledOfflineWithoutWireCancel() = runBlocking<Unit> {
        val wire = Wire(); val port = Port(wire); val viewModel = vm(wire, port, snapshot(emptyList()))
        try {
            viewModel.updateSnapshot(snapshot(emptyList()), busyActorIds = setOf("agent:a"))
            wire.state.value = WireState.Disconnected("offline", 0)
            val row = LocalSubmission("local-only", "c", buildJsonObject { put("msg_type", "agent.ask"); put("audience", buildJsonArray { add("agent:a") }); put("payload", payload("text" to "offline text")) }, "offline text", "A", createdAt = 1)
            viewModel.updateSubmissions(listOf(row))
            assertEquals("等待连接后发出", viewModel.state.value.groups.single().rows.single().labels.first())
            assertEquals("local-only", viewModel.state.value.groups.single().rows.single().localSubmission!!.messageId)
            viewModel.cancel("local-only")
            assertEquals(1, port.calls.count { it.first == "outbox.cancel" })
            assertEquals(0, port.calls.count { it.first == "wire.cancel" })
            viewModel.updateSnapshot(snapshot(emptyList()), newerUnloaded = true)
            assertTrue(viewModel.state.value.groups.isEmpty())
            assertTrue(viewModel.state.value.localEchoes.isEmpty())
        } finally { viewModel.close() }
    }

    @Test fun timelineStopUsesDeclaredActorRequestAndDetachedWindowCannotStop() = runBlocking<Unit> {
        val wire = Wire(); val port = Port(wire)
        val base = WaitingRulesTest().turn(status = "processing", controls = buildJsonArray { add(buildJsonObject { put("word", "agent.interrupt") }) })
        val running = base.copy(request = Envelope(JsonObject(base.request.json + ("type" to JsonPrimitive("agent.ask")))))
        val item = TimelineEntry("turn", 1, turn = running)
        val snapshot = TimelineSnapshot("c", 1, listOf(PresentationItem("turn", item, 1, 2, "agent:a", 0, "", 0, false, "turn", false, "1")), emptyList(), emptyList(), 1, 2)
        val viewModel = vm(wire, port, snapshot)
        try {
            viewModel.stop("target")
            assertEquals(Envelope.EMPTY, port.calls.single { it.first == "agent.interrupt" }.second)
            viewModel.updateSnapshot(snapshot, newerUnloaded = true)
            viewModel.stop("target")
            assertEquals(1, port.calls.count { it.first == "agent.interrupt" })
            viewModel.updateSnapshot(snapshot.copy(items = listOf(snapshot.items.single().copy(entry = item.copy(turn = running.copy(terminal = running.request))))))
            viewModel.stop("target")
            assertEquals(1, port.calls.count { it.first == "agent.interrupt" })
        } finally { viewModel.close() }
    }

    @Test fun receiverAcceptanceBeforeLocalJournalReturnReleasesExactlyOnceWithoutReopening() = runBlocking<Unit> {
        val wire = Wire(); val port = Port(wire); port.signalBeforeAccept = true
        val viewModel = vm(wire, port)
        try {
            viewModel.editing()
            val hold = viewModel.state.value.edit.session!!.holdId
            viewModel.submitEdit("new")
            withTimeout(2_000) { viewModel.state.first { it.edit.phase == EditPhase.IDLE } }
            assertFalse(port.acceptReturned.isCompleted)
            assertEquals("agent.replace", port.submitted!!.text("msg_type"))
            assertEquals(hold, port.submitted!!.getValue("payload").jsonObject.text("expected_hold_id"))
            assertEquals("old", port.submitted!!.getValue("payload").jsonObject.text("old_text"))
            assertEquals("new", port.submitted!!.getValue("payload").jsonObject.text("new_text"))
            assertEquals(hold, port.calls.single { it.first == "agent.unhold" }.second.text("expected_hold_id"))
            port.localAcceptance.complete(Unit); port.acceptReturned.await(); yield()
            assertEquals(EditPhase.IDLE, viewModel.state.value.edit.phase)
            assertEquals(1, port.calls.count { it.first == "agent.unhold" })
        } finally { viewModel.close() }
    }
    @Test fun disconnectBeforeReceiverAcceptanceExitsAndReconnectReleasesCapturedLease() = runBlocking<Unit> {
        val wire = Wire(); val port = Port(wire)
        val viewModel = vm(wire, port)
        try {
            viewModel.editing(); val hold = viewModel.state.value.edit.session!!.holdId
            viewModel.submitEdit("new")
            assertEquals(EditPhase.SUBMITTING, viewModel.state.value.edit.phase)
            wire.state.value = WireState.Disconnected("test", 0); yield()
            assertEquals(EditPhase.IDLE, viewModel.state.value.edit.phase)
            assertTrue(viewModel.state.value.edit.notice.contains("修改结果待确认"))
            assertEquals(0, port.calls.count { it.first == "agent.unhold" })
            wire.state.value = WireState.Attached(2, Envelope.EMPTY); yield()
            assertEquals(hold, port.calls.single { it.first == "agent.unhold" }.second.text("expected_hold_id"))
            port.localAcceptance.complete(Unit); port.acceptReturned.await(); port.signal("queued"); yield()
            assertEquals(EditPhase.IDLE, viewModel.state.value.edit.phase)
        } finally { viewModel.close() }
    }
    @Test fun cancelDuringPendingHoldLateAckCannotReenterEditor() = runBlocking<Unit> {
        val wire = Wire(); val port = Port(wire); val holdGate = CompletableDeferred<Unit>(); port.deferHold = holdGate
        val viewModel = vm(wire, port)
        try {
            viewModel.requestEdit("target"); port.holdAdmitted.await()
            assertEquals(EditPhase.HOLDING, viewModel.state.value.edit.phase)
            viewModel.cancelEdit(); holdGate.complete(Unit); yield()
            assertEquals(EditPhase.IDLE, viewModel.state.value.edit.phase)
            assertEquals("", port.owner)
            assertEquals(0, port.calls.count { it.first == "agent.context" && it.second.containsKey("target") })
        } finally { viewModel.close() }
    }
    @Test fun cancelAllHoldsThenUsesCallerCancelOrDismissThenExactUnhold() = runBlocking<Unit> {
        val wire = Wire(); val port = Port(wire)
        val other = WaitingRulesTest().turn("other").let { it.copy(request = Envelope(JsonObject(it.request.json + ("id" to JsonPrimitive("other-target"))))) }
        val viewModel = vm(wire, port, snapshot(listOf(WaitingRulesTest().turn(), other)))
        try {
            viewModel.cancelAll("agent:a"); yield()
            val actions = port.calls.filter { it.first !in setOf("actor.describe", "agent.context") }
            assertEquals(listOf("agent.hold", "wire.cancel", "agent.dismiss", "agent.unhold"), actions.map { it.first })
            assertEquals("target", actions[1].second.text("target"))
            assertEquals("other-target", actions[2].second.text("target"))
            assertTrue(actions[3].second.text("expected_hold_id").isNotEmpty())
        } finally { viewModel.close() }
    }
    @Test fun waitingMembershipOnlyComesFromSnapshotAndDetachedHistoryHidesIt() = runBlocking<Unit> {
        val wire = Wire(); val port = Port(wire); val viewModel = vm(wire, port, snapshot(emptyList()))
        try {
            viewModel.updateSubmissions(listOf(LocalSubmission("local", "c", Envelope.EMPTY, "local", "A", createdAt = 0)))
            assertTrue(viewModel.state.value.groups.isEmpty())
            viewModel.updateSnapshot(snapshot()); assertEquals(1, viewModel.state.value.groups.single().rows.size)
            viewModel.updateSnapshot(snapshot(), newerUnloaded = true); assertTrue(viewModel.state.value.groups.isEmpty())
        } finally { viewModel.close() }
    }
    @Test fun warningUsesExistingRunningTasksAndDecliningSendsNoHold() = runBlocking<Unit> {
        val wire = Wire(); val port = Port(wire)
        val tasks = listOf(BackgroundTask("t", "t", "agent:a", title = "测试后台任务"))
        val viewModel = vm(wire, port, snapshot().copy(runningTasks = tasks))
        try {
            viewModel.requestEdit("target")
            assertNotNull(viewModel.state.value.warningTarget)
            assertTrue(viewModel.state.value.warning.contains("测试后台任务"))
            viewModel.confirmEdit(false)
            assertEquals(0, port.calls.count { it.first == "agent.hold" })
        } finally { viewModel.close() }
    }
    @Test fun expiredLeaseAndLeavingWritableSurfaceBothExitWithExactRelease() = runBlocking<Unit> {
        val wire = Wire(); val port = Port(wire); port.leaseUntil = System.currentTimeMillis() - 1
        val viewModel = vm(wire, port)
        try {
            viewModel.requestEdit("target"); yield()
            assertEquals(EditPhase.IDLE, viewModel.state.value.edit.phase)
            assertTrue(viewModel.state.value.edit.notice.contains("到期"))
            assertEquals(1, port.calls.count { it.first == "agent.unhold" })
            port.leaseUntil = System.currentTimeMillis() + 60_000
            viewModel.editing(); val hold = viewModel.state.value.edit.session!!.holdId
            viewModel.updateAccess(false, "self", mapOf("agent:a" to "A")); yield()
            assertEquals(EditPhase.IDLE, viewModel.state.value.edit.phase)
            assertEquals(hold, port.calls.last { it.first == "agent.unhold" }.second.text("expected_hold_id"))
        } finally { viewModel.close() }
    }
    @Test fun bulkCancellationFailureStillReleasesAndDoesNotSkipOtherTargets() = runBlocking<Unit> {
        val wire = Wire(); val port = Port(wire); port.failCancel = true
        val other = WaitingRulesTest().turn("other").let { it.copy(request = Envelope(JsonObject(it.request.json + ("id" to JsonPrimitive("other-target"))))) }
        val viewModel = vm(wire, port, snapshot(listOf(WaitingRulesTest().turn(), other)))
        try {
            viewModel.cancelAll("agent:a"); yield()
            assertEquals(1, port.calls.count { it.first == "agent.dismiss" })
            assertEquals(1, port.calls.count { it.first == "agent.unhold" })
            assertTrue(viewModel.state.value.error.contains("test cancellation failure"))
        } finally { viewModel.close() }
    }
    @Test fun successfulInterruptTerminatesEditorWithoutReopeningIt() = runBlocking<Unit> {
        val wire = Wire(); val port = Port(wire); val viewModel = vm(wire, port)
        try {
            viewModel.editing()
            wire.frames.emit(DownFrame.Feed("", Row("c", 6, Envelope(buildJsonObject {
                put("id", "interrupt-terminal"); put("kind", "response"); put("type", "agent.interrupt"); put("parent_id", "interrupt-request")
                put("sender", payload("id" to "agent:a")); put("payload", buildJsonObject { put("body", payload("status" to "completed")) })
            }))))
            yield()
            assertEquals(EditPhase.IDLE, viewModel.state.value.edit.phase)
            assertTrue(viewModel.state.value.edit.notice.contains("已停止这个 Agent"))
        } finally { viewModel.close() }
    }

}

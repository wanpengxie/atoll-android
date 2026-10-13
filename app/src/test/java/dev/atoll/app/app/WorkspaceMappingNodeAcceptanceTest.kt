package dev.atoll.app.app

import dev.atoll.app.core.*
import dev.atoll.app.model.*
import dev.atoll.app.net.*
import dev.atoll.app.ui.screens.TimelineEntry as UiEntry
import dev.atoll.app.ui.screens.MessageStatus
import dev.atoll.app.ui.screens.MessageViewState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Explicit opt-in; authenticated writes are permitted only on the disposable test node. */
@OptIn(DelicateCoroutinesApi::class,ExperimentalCoroutinesApi::class)
class WorkspaceMappingNodeAcceptanceTest {
    @Test fun realCodexProgressAndTerminalMapThroughTheSameStableProjection()=runBlocking<Unit> {
        val url=System.getenv("ATOLL_MAPPER_URL").orEmpty()
        val token=System.getenv("ATOLL_MAPPER_TOKEN").orEmpty()
        val channel=System.getenv("ATOLL_MAPPER_CHANNEL").orEmpty()
        val codex=System.getenv("ATOLL_MAPPER_CODEX").orEmpty()
        assumeTrue("Set ATOLL_MAPPER_* for isolated-node acceptance",listOf(url,token,channel,codex).all(String::isNotEmpty))
        require(url=="http://100.99.232.10:18080") { "Refusing writes outside the isolated node" }
        newSingleThreadContext("mapper-acceptance").use { dispatcher -> withContext(dispatcher) {
            // Only this JVM test bypasses the host's system HTTP proxy for the pinned tailnet node.
            val client=OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).addInterceptor {
                it.proceed(it.request().newBuilder().header("Authorization","Bearer "+token).build())
            }.build()
            Wire(url,client,"Mapper isolated acceptance").use { wire ->
                val replica=ChannelReplica(channel);val mapper=TimelineMapper();val id=UUID.randomUUID().toString()
                var self="";var processing=false;var terminalTurn:Turn?=null
                val done=CompletableDeferred<MessageViewState>()
                val fold=launch(start=CoroutineStart.UNDISPATCHED) {
                    wire.frames.collect { frame ->
                        if(frame !is DownFrame.Feed || frame.row.channelId!=channel) return@collect
                        replica.apply(RowBatch(channel,listOf(frame.row),Origin.LIVE))
                        val source=replica.snapshot()
                        val snapshot=replica.timeline(TimelineOptions(scope=ConversationScope.ALL,selfId=self,showNarration=true))
                        val messages=mapper.map(snapshot,self,listOf(ActorRecord(codex,"test-codex","agent")),source=source)
                            .filterIsInstance<UiEntry.Message>().map { it.value }
                        val answer=messages.lastOrNull { it.id.contains(id) && it.id.endsWith(":answer") }
                        if(answer?.status==MessageStatus.Processing) processing=true
                        val turn=source.timeline.firstOrNull { it.turn?.requestId==id }?.turn
                        if(turn?.terminal!=null && answer!=null) { terminalTurn=turn;done.complete(answer) }
                    }
                }
                try {
                    wire.start()
                    val receipt=withTimeout(15000) { wire.state.filterIsInstance<WireState.Attached>().first() }.receipt
                    self=(receipt["memberships"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
                        .first { it["channel_id"].string()==channel }["actor_id"].string()
                    assertTrue(self.isNotEmpty())
                    wire.actorRequest(channel,codex,"agent.ask",buildJsonObject {
                        put("text","Native TimelineMapper acceptance: give one short progress sentence. Use the built-in shell once to run printf MAPPER_OK; if no shell is available, reply MAPPER_OK directly. Final answer must contain MAPPER_OK. Do not call other actors or spawn tasks.")
                    },timeoutMs=120000,messageId=id)
                    val answer=withTimeout(10000) { done.await() }
                    val turn=terminalTurn!!
                    assertTrue("Live processing must have reached the mapper",processing)
                    assertTrue(answer.text.contains("MAPPER_OK"));assertEquals(MessageStatus.Completed,answer.status);assertTrue(answer.latest)
                    val stages=turn.provisional.mapNotNull { row ->
                        (row.envelope.body["process"] as? JsonObject)?.takeIf { it["kind"].string()=="stage" && it["stage"].string()=="text" }?.get("text").string().takeIf(String::isNotBlank)
                    }
                    val finalText=turn.terminal!!.body["text"].string().trim()
                    val tail=stages.lastOrNull()?.trim()?.removeSuffix("…[truncated]").orEmpty()
                    val preserved=if(finalText.isNotEmpty() && tail.isNotEmpty() && finalText.startsWith(tail)) stages.dropLast(1) else stages
                    assertTrue("Non-echo progress prose survives the terminal",preserved.all { answer.text.contains(it) })
                    assertFalse(answer.trail?.running==true)
                    val settledSource=replica.snapshot()
                    val settledSnapshot=replica.timeline(TimelineOptions(scope=ConversationScope.ALL,selfId=self,showNarration=true))
                    fun settled()=mapper.map(settledSnapshot,self,listOf(ActorRecord(codex,"test-codex","agent")),source=settledSource)
                        .filterIsInstance<UiEntry.Message>().map { it.value }.first { it.id==answer.id }
                    val firstSteady=settled();val repeated=settled()
                    assertSame("The settled answer is not rebuilt on an unchanged projection",firstSteady,repeated)
                    val report=buildJsonObject {
                        put("request_id",id);put("live_processing",processing);put("provisional_frames",turn.provisional.size)
                        put("text_stages",stages.size);put("preserved_text_stages",preserved.size)
                        put("trail_records",answer.trail?.records?.size ?: 0);put("latest",answer.latest)
                        put("tool_records",answer.trail?.records?.count { it.kind==dev.atoll.app.ui.screens.ProcessKind.Tool } ?: 0)
                        put("settled_identity_reused",firstSteady===repeated);put("marker_present",answer.text.contains("MAPPER_OK"))
                    }
                    File("build/reports/mapper-real.json").apply { parentFile?.mkdirs();writeText(report.toString()) }
                    println("Isolated-node mapper acceptance passed; report build/reports/mapper-real.json")
                } finally { fold.cancel() }
            }
            client.dispatcher.executorService.shutdown();client.connectionPool.evictAll()
        } }
    }
}

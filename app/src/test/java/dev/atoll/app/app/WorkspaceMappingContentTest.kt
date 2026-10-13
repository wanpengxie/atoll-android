package dev.atoll.app.app

import dev.atoll.app.core.*
import dev.atoll.app.model.*
import dev.atoll.app.ui.screens.TimelineEntry as UiEntry
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class WorkspaceMappingContentTest {
    private fun json(value: String) = Json.parseToJsonElement(value).jsonObject
    private fun row(seq: Long,id: String,kind: String,body: JsonObject,type: String="agent.ask",parent: String="",sender: String=if(kind=="request") "human:self" else "agent:a") = Row("c",seq,Envelope(buildJsonObject {
        put("id",id);put("kind",kind);put("type",type);put("parent_id",parent);put("ts",seq*1000)
        put("sender",buildJsonObject { put("id",sender);put("kind",sender.substringBefore(':')) })
        put("audience",buildJsonArray { add(if(kind=="request") "agent:a" else "human:self") })
        put("payload",buildJsonObject { put("body",body) })
    }))
    private fun answer(body: String, type: String="agent.ask") = mapped(
        row(1,"q","request",json("""{"text":"question"}"""),type),
        row(2,"a","response",json(body),type,"q")).last()
    private fun mapped(vararg rows: Row):List<dev.atoll.app.ui.screens.MessageViewState> {
        val replica=ChannelReplica("c","human:self");replica.apply(RowBatch("c",rows.toList(),Origin.LIVE))
        return TimelineMapper().map(replica.timeline(TimelineOptions(scope=ConversationScope.ALL,selfId="human:self")),"human:self",emptyList())
            .filterIsInstance<UiEntry.Message>().map { it.value }
    }
    @Test fun structuredJsonAndBusinessResultsDoNotBecomeMarkdownCodeFences() {
        val objectText=answer(buildJsonObject { put("status","completed");put("text",json("""{"count":2}""").toString()) }.toString())
        assertEquals("",objectText.text);assertEquals("JSON 结果",objectText.result!!.title);assertEquals(json("""{"count":2}"""),objectText.result!!.value)
        val scalar=answer("""{"status":"completed","text":"42"}""");assertEquals("42",scalar.text);assertNull(scalar.result)
        val data=answer("""{"status":"completed","value":[1,2],"usage":{"tokens":7}}""")
        assertEquals(json("""{"value":[1,2]}"""),data.result!!.value);assertEquals("",data.text)
        val capability=answer("""{"status":"completed","class":"codex","words":{}}""","actor.describe")
        assertEquals("codex 的能力",capability.result!!.title)
    }
    @Test fun errorKeepsCodeDetailAndBusinessDataButOmitsTransportMetadata() {
        val failed=answer("""{"status":"failed","error_code":"receiver_unavailable","reason":"dead","detail":"process exited","data":{"request":"q"},"closed_by":"system"}""")
        assertEquals("",failed.text);assertEquals("receiver_unavailable",failed.result!!.errorCode)
        assertEquals("process exited",failed.result!!.detail);assertEquals(json("""{"data":{"request":"q"}}"""),failed.result!!.errorData)
        assertEquals("cancelled",answer("""{"status":"failed","cancelled":true,"reason":"interrupted"}""").result!!.errorCode)
    }
    @Test fun emptyTextAndBareAcknowledgementsStayDistinctFromReadableContentBlocks() {
        assertTrue(answer("""{"status":"completed","text":""}""").result!!.emptyText)
        assertTrue(answer("""{"status":"completed"}""").result!!.acknowledgement)
        val content=answer("""{"status":"completed","content":[{"type":"text","text":"hello"},{"type":"resource_link","uri":"file:test","name":"test"}]}""")
        assertEquals("hello",content.text);assertNull(content.result)
    }
}

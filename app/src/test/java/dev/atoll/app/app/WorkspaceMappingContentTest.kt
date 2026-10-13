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
    @Test fun attachmentsCombineListedFilesAndResourceLinksWithoutDuplicateCards() {
        val body=json("""{"attachments":[{"resource_id":"daemon://d/c/a.pdf","name":"Chosen PDF","mime":"application/pdf","size":42}],"files":[{"id":"ignored"}],"content":[{"type":"resource_link","uri":"daemon://d/c/a.pdf","name":"duplicate"},{"type":"resource_link","uri":" oss://s/c/image.png ","mimeType":"image/png","size":-1},{"type":"resource_link","uri":"  "}]}""")
        val cards=messageAttachments(body)
        assertEquals(2,cards.size);assertEquals("Chosen PDF",cards[0].name)
        assertEquals("application/pdf",cards[0].mime);assertEquals(42L,cards[0].size)
        assertEquals("oss://s/c/image.png",cards[1].resourceId);assertEquals(cards[1].resourceId,cards[1].uri)
        assertEquals("image.png",cards[1].name);assertEquals("image/png",cards[1].mime);assertNull(cards[1].size)
        assertTrue(messageAttachments(json("""{"attachments":[],"files":[{"id":"ignored"}]}""")).isEmpty())
        assertEquals("file:x",messageAttachments(json("""{"files":[{"id":"file:x","name":"X","media_type":"text/plain","size":"7"}]}""")).single().resourceId)
    }
    @Test fun requestAndAnswerAttachIndependentCardsAndFileOnlyAnswersAvoidJsonOrEmptyAck() {
        val request=row(1,"q","request",json("""{"files":[{"id":"daemon://d/c/input.txt","name":"input"}]}"""))
        val reply=row(2,"a","response",json("""{"status":"completed","attachments":[{"resource_id":"oss://s/c/out.png","name":"output"}]}"""),parent="q")
        val messages=mapped(request,reply)
        assertEquals("input",messages.first().attachments.single().name);assertEquals("",messages.first().text)
        assertEquals("output",messages.last().attachments.single().name);assertEquals("",messages.last().text);assertNull(messages.last().result)
        assertNull(answer("""{"status":"completed","text":"","files":[{"id":"file:x"}]}""").result)
        val linked=answer("""{"status":"completed","content":[{"type":"text","text":"Here"},{"type":"resource_link","uri":"daemon://d/c/x","title":"X"}]}""")
        assertEquals("Here",linked.text);assertEquals("X",linked.attachments.single().name);assertNull(linked.result)
        val structured=answer("""{"status":"completed","value":42,"attachments":[{"id":"file:x"}]}""")
        assertEquals(json("""{"value":42}"""),structured.result!!.value);assertEquals(1,structured.attachments.size)
    }
    @Test fun aFailedEntryLeavesNeighborsVisibleAndRetriesWhenItsContentChanges() {
        val replica=ChannelReplica("c","human:self")
        val notice=row(2,"notice","event",json("""{"member":"agent:a","body":"codex"}"""),"system.member.created",sender="system")
        val system=notice.copy(envelope=Envelope(JsonObject(notice.envelope.json+("visibility" to JsonPrimitive("system")))))
        replica.apply(RowBatch("c",listOf(row(1,"one","request",json("""{"text":"before"}"""),"human.message",sender="human:self"),system,
            row(3,"two","request",json("""{"text":"after"}"""),"human.message",sender="human:self")),Origin.LIVE))
        val canonical=replica.snapshot()
        var failing=true
        val source=object: TimelineSource by canonical {
            override val narration: Collection<Row> get() { if(failing) error("private diagnostics");return canonical.narration }
        }
        val snapshot=replica.timeline(TimelineOptions(scope=ConversationScope.ALL,selfId="human:self",showNarration=true))
        val mapper=TimelineMapper()
        val initial=mapper.map(snapshot,"human:self",emptyList(),source=source)
        assertEquals(listOf("before","after"),initial.filterIsInstance<UiEntry.Message>().map { it.value.text })
        val error=initial.filterIsInstance<UiEntry.Notice>().single()
        assertEquals("这条消息暂时无法显示。内容更新后会自动重试。",error.text)
        failing=false
        assertSame(error,mapper.map(snapshot,"human:self",emptyList(),source=source).filterIsInstance<UiEntry.Notice>().single())
        val updated=snapshot.copy(items=snapshot.items.map { if(it.entry.kind=="narration") it.copy(contentRevision=it.contentRevision+":changed") else it })
        val recovered=mapper.map(updated,"human:self",emptyList(),source=source)
        assertEquals("agent:a 已加入频道",recovered.filterIsInstance<UiEntry.Notice>().single().text)
        assertSame(initial.filterIsInstance<UiEntry.Message>().first().value,recovered.filterIsInstance<UiEntry.Message>().first().value)
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
        assertEquals("cancelled",answer("""{"status":"failed","cancelled":true,"reason":"cancelled"}""").result!!.errorCode)
    }
    @Test fun emptyTextAndBareAcknowledgementsStayDistinctFromReadableContentBlocks() {
        assertTrue(answer("""{"status":"completed","text":""}""").result!!.emptyText)
        assertTrue(answer("""{"status":"completed"}""").result!!.acknowledgement)
        val content=answer("""{"status":"completed","content":[{"type":"text","text":"hello"},{"type":"resource_link","uri":"file:test","name":"test"}]}""")
        assertEquals("hello",content.text);assertNull(content.result)
    }
    @Test fun completedTurnsKeepStreamingTextAndRemoveOnlyTheFinalEcho() {
        fun stage(seq: Long,text: String)=row(seq,"p$seq","response",buildJsonObject { put("status","processing");put("process",buildJsonObject { put("kind","stage");put("stage","text");put("text",text) }) },parent="q")
        val request=row(1,"q","request",json("""{"text":"question"}"""))
        val result=mapped(request,stage(2,"First explanation"),stage(3,"Final…[truncated]"),
            row(4,"a","response",json("""{"status":"completed","text":"Final answer"}"""),parent="q")).last()
        assertEquals("First explanation\n\nFinal answer",result.text)
        assertNull(result.status)
    }
    @Test fun supersededEmptyAnswersDisappearButProducedStreamingTextSurvives() {
        for (field in listOf("merged_into","preempted_by")) {
            val request=row(1,"q","request",json("""{"text":"question"}"""))
            val terminal=row(3,"a","response",buildJsonObject { put("status","completed");put(field,"successor") },parent="q")
            assertEquals(1,mapped(request,terminal).size)
            val stage=row(2,"p","response",json("""{"status":"processing","process":{"kind":"stage","stage":"text","text":"keep this"}}"""),parent="q")
            val answer=mapped(request,stage,terminal).last()
            assertEquals("keep this",answer.text);assertNull(answer.result);assertNull(answer.status)
        }
    }
    @Test fun interruptedTurnKeepsAlreadyProducedTextWithoutAnErrorAckBubble() {
        val answer=mapped(row(1,"q","request",json("""{"text":"question"}""")),
            row(2,"p","response",json("""{"status":"processing","process":{"kind":"stage","stage":"text","text":"partial answer"}}"""),parent="q"),
            row(3,"a","response",json("""{"status":"failed","reason":"interrupted"}"""),parent="q")).last()
        assertEquals("partial answer\n\n✗ 已停止 · 发消息即继续",answer.text);assertNull(answer.result)
    }

    @Test fun newestTurnDoesNotFoldAndAppendReusesOlderMappedResults() {
        val replica=ChannelReplica("c","human:self")
        fun pair(start: Long,id: String)=listOf(row(start,id,"request",json("""{"text":"question"}""")),
            row(start+1,"a$id","response",json("""{"status":"completed","text":"answer"}"""),parent=id))
        replica.apply(RowBatch("c",pair(1,"one")+pair(3,"two"),Origin.LIVE))
        val mapper=TimelineMapper();val options=TimelineOptions(scope=ConversationScope.ALL,selfId="human:self")
        fun project()=mapper.map(replica.timeline(options),"human:self",emptyList()).filterIsInstance<UiEntry.Message>().map { it.value }
        val first=project();assertEquals(listOf(false,false,true,true),first.map { it.latest })
        replica.apply(RowBatch("c",pair(5,"three"),Origin.LIVE));val next=project()
        assertSame(first.first(),next.first());assertSame(first[1],next[1])
        assertEquals(listOf(false,false,false,false,true,true),next.map { it.latest })
        assertSame(next.first(),project().first())
    }
    @Test fun cacheTracksHistoryAvailabilityAndEffectiveSelfIdentity() {
        val replica=ChannelReplica("c","human:self")
        replica.apply(RowBatch("c",listOf(row(1,"q","request",json("""{"text":"question"}""")),
            row(2,"p","response",json("""{"status":"processing","process":{"kind":"stage","stage":"thinking"}}"""),parent="q")),Origin.LIVE))
        val snapshot=replica.timeline(TimelineOptions(scope=ConversationScope.ALL,selfId="human:self"))
        val mapper=TimelineMapper()
        assertTrue(mapper.map(snapshot,"human:self",emptyList()).filterIsInstance<UiEntry.Message>().any { it.value.status!=null })
        assertTrue(mapper.map(snapshot,"human:self",emptyList(),true).filterIsInstance<UiEntry.Message>().all { it.value.status==null })
        assertFalse(mapper.map(snapshot,"someone-else",emptyList()).filterIsInstance<UiEntry.Message>().first().value.self)
    }

    @Test fun expiryChangesInvalidateOnlyTheAffectedCachedAnswer() {
        var now=100L
        val mapper=TimelineMapper { now };val replica=ChannelReplica("c","human:self")
        val request=row(1,"q","request",json("""{"text":"question"}"""))
        val expiry=request.copy(envelope=Envelope(JsonObject(request.envelope.json+("expires_at" to JsonPrimitive(150)))))
        replica.apply(RowBatch("c",listOf(expiry,row(2,"p","response",json("""{"status":"processing"}"""),parent="q")),Origin.LIVE))
        val snapshot=replica.timeline(TimelineOptions(scope=ConversationScope.ALL,selfId="human:self",now=now))
        fun project()=mapper.map(snapshot,"human:self",emptyList(),source=replica.snapshot()).filterIsInstance<UiEntry.Message>().map { it.value }
        assertNotNull(project().last().status);now=151
        val expired=project().last();assertEquals("未完成 · 已过期",expired.text);assertNull(expired.status)
        assertSame(expired,project().last())
    }
    @Test fun restartIsReadFromCanonicalSourceEvenWhenTheRestartTurnIsFilteredOut() {
        val replica=ChannelReplica("c","human:self")
        val restart=row(3,"restart","request",json("""{"member":"agent:a"}"""),"system.member.restart",sender="agent:other")
        val command=restart.copy(envelope=Envelope(JsonObject(restart.envelope.json+("audience" to buildJsonArray { add("system") }))))
        val reply=row(4,"r","response",json("""{"status":"completed"}"""),"system.member.restart","restart","system")
        val hiddenReply=reply.copy(envelope=Envelope(JsonObject(reply.envelope.json+("audience" to buildJsonArray { add("agent:other") }))))
        replica.apply(RowBatch("c",listOf(row(1,"q","request",json("""{"text":"question"}""")),
            row(2,"p","response",json("""{"status":"processing"}"""),parent="q"),command,hiddenReply),Origin.LIVE))
        val snapshot=replica.timeline(TimelineOptions(selfId="human:self"))
        assertFalse(snapshot.items.any { it.entry.id=="restart" })
        val answer=TimelineMapper().map(snapshot,"human:self",emptyList(),source=replica.snapshot()).filterIsInstance<UiEntry.Message>().last().value
        assertEquals("未完成 · Agent 已重启",answer.text);assertNull(answer.status)
    }
    @Test fun narrationUsesDocumentedFactsAndWeakNoticesNeverExposeRawJsonOrWireTypes() {
        val replica=ChannelReplica("c","human:self")
        fun system(seq:Long,id:String,type:String,body:JsonObject):Row {
            val row=row(seq,id,"event",body,type,sender="system")
            return row.copy(envelope=Envelope(JsonObject(row.envelope.json+("visibility" to JsonPrimitive("system")))))
        }
        replica.apply(RowBatch("c",listOf(
            system(1,"joined","system.member.created",json("""{"member":"agent:a","body":"class codex"}""")),
            system(2,"generated","system.member.created",json("""{"member":"internal","body":"generated:service"}""")),
            system(3,"build","system.build.finished",json("""{"object":{"kind":"member","name":"Agent A"},"attempt":2,"result":"ok","state":"ready"}""")),
            system(4,"unknown","system.future.event",json("""{"password":"do-not-print","detail":"raw detail"}""")),
            row(5,"ready","event",json("{}"),"actor.ready"),
            row(6,"notice","event",buildJsonObject { put("text","First line\nSecond line") },"provider.future")
        ),Origin.LIVE))
        val source=replica.snapshot();val snapshot=replica.timeline(TimelineOptions(scope=ConversationScope.ALL,selfId="human:self",showNarration=true))
        val notices=TimelineMapper().map(snapshot,"human:self",listOf(ActorRecord("agent:a","Agent A","agent")),source=source).filterIsInstance<UiEntry.Notice>().map { it.text }
        assertTrue("Agent A 已加入频道" in notices);assertTrue("成员 Agent A构建成功，已就绪" in notices)
        assertTrue(notices.any { it.contains("Agent A 已就绪") });assertTrue(notices.any { it.contains("First line") && !it.contains("Second line") })
        assertFalse(notices.any { it.contains("generated") || it.contains("password") || it.contains("do-not-print") || it.contains("future") })
    }

    @Test fun toolStartsAndEndsShareOneTrailRecordAndTextStagesRemainProse() {
        val answer=mapped(row(1,"q","request",json("""{"text":"question"}""")),
            row(2,"thinking","response",json("""{"status":"processing","process":{"kind":"stage","stage":"thinking"}}"""),parent="q"),
            row(3,"started","response",json("""{"status":"processing","process":{"kind":"tool","tool":"workspace.bash","tool_call_id":"call","phase":"started","input":{"command":"ls"}}}"""),parent="q"),
            row(4,"words","response",json("""{"status":"processing","process":{"kind":"stage","stage":"text","text":"explanation"}}"""),parent="q"),
            row(5,"ended","response",json("""{"status":"processing","process":{"kind":"tool","tool":"workspace.bash","tool_call_id":"call","phase":"ended","outcome":"ok","input":{},"output":"files"}}"""),parent="q"),
            row(6,"a","response",json("""{"status":"completed","text":"done"}"""),parent="q")).last()
        val records=answer.trail!!.records
        assertEquals(2,records.size);assertEquals("tool:call",records.last().id)
        assertEquals(json("""{"command":"ls"}"""),records.last().input);assertEquals(JsonPrimitive("files"),records.last().output)
        assertEquals("files",records.last().outputText);assertFalse(answer.trail!!.running)
        assertEquals("explanation\n\ndone",answer.text)
    }
    @Test fun toolDataIsBoundedWithoutTraversingOtherRawProcessFields() {
        val body=buildJsonObject { put("status","processing");put("process",buildJsonObject {
            put("kind","tool");put("tool","workspace.read");put("phase","ended");put("tool_call_id","large")
            put("output","界".repeat(100_000));put("unrelated",JsonObject(mapOf("password" to JsonPrimitive("never-project-me"))))
        }) }
        val answer=mapped(row(1,"q","request",json("""{"text":"question"}""")),row(2,"p","response",body,parent="q")).last()
        val record=answer.trail!!.records.single()
        assertTrue(record.outputText.toByteArray().size<=4096);assertFalse(record.toString().contains("never-project-me"))
        assertTrue(answer.trail!!.running)
    }
    @Test fun nestedToolRequestsFoldIntoTheAnswerTrailInsteadOfFullMessageBubbles() {
        val request=row(2,"call","request",json("""{"command":"ls"}"""),"workspace.bash","q","agent:a")
        val tool=request.copy(envelope=Envelope(JsonObject(request.envelope.json+("audience" to buildJsonArray { add("tool:w") }))))
        val messages=mapped(row(1,"q","request",json("""{"text":"question"}""")),tool,
            row(3,"tool-reply","response",json("""{"status":"completed","output":"files"}"""),"workspace.bash","call","tool:w"),
            row(4,"a","response",json("""{"status":"completed","text":"done"}"""),parent="q"))
        assertEquals(2,messages.size);assertEquals("call:call",messages.last().trail!!.records.single().id)
        assertEquals(json("""{"command":"ls"}"""),messages.last().trail!!.records.single().input)
    }

    @Test fun attachedAndOrphanTasksUseStableAgentIdentityAndRetainReturnMessages() {
        fun task(seq: Long,id: String,body: JsonObject,parent: String="q")=row(seq,id,"event",body,"agent.task",parent)
        val messages=mapped(row(1,"q","request",json("""{"text":"question"}""")),
            task(2,"s",json("""{"call_id":"call-1","task_id":"worker","kind":"agent","phase":"started","title":"Research"}""")),
            task(3,"m",json("""{"call_id":"call-2","task_id":"worker","phase":"message","text":"returned"}""")),
            task(4,"e",json("""{"call_id":"call-2","task_id":"worker","phase":"completed","text":"returned"}""")),
            row(5,"a","response",json("""{"status":"completed","text":"done"}"""),parent="q"))
        val leaf=messages.last().subTasks.single()
        assertEquals("agent:a"+'\u0000'+"worker",leaf.id);assertEquals("q",leaf.requestId)
        assertEquals("returned",leaf.result);assertTrue(leaf.messages.isEmpty())
        assertEquals(dev.atoll.app.ui.screens.SubTaskPhase.Completed,leaf.phase)
        val orphan=mapped(task(1,"orphan",json("""{"call_id":"shell","kind":"shell","phase":"started","title":"Build"}"""),"")).single()
        assertEquals("",orphan.text);assertEquals(dev.atoll.app.ui.screens.SubTaskKind.Shell,orphan.subTasks.single().kind)
    }
    @Test fun fourBackgroundTasksPopulateTheExistingFoldedTreeAsOneLogicalRow() {
        val rows=(1L..4).map { seq -> row(seq,"task$seq","event",buildJsonObject {
            put("task_id","worker$seq");put("call_id","call$seq");put("kind","agent");put("phase","started");put("title","Worker $seq")
        },"agent.task") }
        val messages=mapped(*rows.toTypedArray())
        assertEquals(1,messages.size);assertEquals(4,messages.single().subTasks.size)
    }
    @Test fun providerReturnSummaryIsACompletedTaskLeafRatherThanRawJson() {
        val message=mapped(row(1,"run","event",json("""{"text":"Summary reply","task_summary":"### Research returned"}"""),"agent.provider.run")).single()
        assertEquals("Summary reply",message.text)
        assertEquals("Research returned",message.subTasks.single().title)
        assertEquals("### Research returned",message.subTasks.single().result)
        assertEquals(dev.atoll.app.ui.screens.SubTaskPhase.Completed,message.subTasks.single().phase)
    }

    @Test fun settledApprovalsShowTheResolverAndStructuredReceipt() {
        val request=row(1,"q","request",json("""{"title":"Release","detail":"Review","impact":"Production"}"""),"human.approve",sender="agent:a")
        val addressed=request.copy(envelope=Envelope(JsonObject(request.envelope.json+("audience" to buildJsonArray { add("human:self") }))))
        val messages=mapped(addressed,row(2,"a","response",json("""{"status":"completed","decision":"approve","note":"Reviewed"}"""),"human.approve","q","human:self"))
        assertEquals("Release\n\nReview\n\n影响：Production",messages.first().text)
        assertEquals("已回执 · 处理者：human:self · approve",messages.last().text)
        assertEquals(json("""{"decision":"approve","note":"Reviewed"}"""),messages.last().result!!.value)
        assertTrue(messages.last().self)
    }
    @Test fun settledHumanAskKeepsTheTextAnswerAlongsideTheReceiptLabel() {
        val request=row(1,"q","request",json("""{"text":"Question"}"""),"human.ask",sender="agent:a")
        val addressed=request.copy(envelope=Envelope(JsonObject(request.envelope.json+("audience" to buildJsonArray { add("human:self") }))))
        val answer=mapped(addressed,row(2,"a","response",json("""{"status":"completed","text":"Answer"}"""),"human.ask","q","human:self")).last()
        assertEquals("已回执 · 处理者：human:self\n\nAnswer",answer.text);assertNull(answer.result)
    }

}

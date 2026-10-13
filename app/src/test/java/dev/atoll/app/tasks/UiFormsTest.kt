package dev.atoll.app.tasks

import dev.atoll.app.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class UiFormsTest {
    private fun obj(text: String)=Json.parseToJsonElement(text).jsonObject
    private fun request(body: JsonObject)=Envelope(buildJsonObject { put("id","f");put("channel_id","c");put("kind","request");put("type","ui.form");put("sender",buildJsonObject { put("id","agent") });put("audience",JsonArray(listOf(JsonPrimitive("me"))));put("payload",buildJsonObject { put("body",body) }) })
    private fun form()=parseUiForm(request(obj("""{"session":"screen","title":" 设置连接 ","schema":{"type":"object","properties":{"name":{"type":"string","minLength":2},"count":{"type":"integer","minimum":1,"maximum":9},"mode":{"enum":[1,"1",true]},"enabled":{"type":"boolean"},"settings":{"type":"object"},"key":{"type":"string"}},"required":["name","count","key"]},"secret":{"key":"global/demo_key"}}""")))
    @Test fun schemaInfersControlsAndKeepsPrefillAndEnumTypesDistinct() {
        val form=form();assertEquals("设置连接",form.title)
        assertEquals(listOf("text","number","select","checkbox","json","password"),form.fields.map { it.control })
        val initial=initialFormValues(form.fields,obj("""{"name":"pre","mode":"1","enabled":true,"settings":{"a":1}}"""))
        assertEquals(JsonPrimitive("\"1\""),initial["mode"]);assertEquals(JsonPrimitive(true),initial["enabled"]);assertEquals(JsonPrimitive("{\"a\":1}"),initial["settings"])
    }
    @Test fun validationRestoresNumbersBooleansJsonAndPreservesSecretWhitespace() {
        val form=form();val validation=validateFormValues(form.fields,mapOf("name" to JsonPrimitive(" name "),"count" to JsonPrimitive("3"),"mode" to JsonPrimitive("1"),"enabled" to JsonPrimitive(false),"settings" to JsonPrimitive("{\"a\":1}"),"key" to JsonPrimitive(" secret ")))
        assertTrue(validation.valid);assertEquals(JsonPrimitive("name"),validation.values["name"]);assertEquals(JsonPrimitive(3),validation.values["count"])
        assertEquals(JsonPrimitive(1),validation.values["mode"]);assertEquals(obj("""{"a":1}"""),validation.values["settings"]);assertEquals(JsonPrimitive(" secret "),validation.values["key"])
        assertEquals(JsonPrimitive(false),validation.values["enabled"])
    }
    @Test fun validatesRequiredLengthPatternIntegerBoundsJsonKindAndEnums() {
        val fields=schemaFields(obj("""{"properties":{"required":{"type":"string"},"number":{"type":"integer","minimum":2,"maximum":5},"pattern":{"type":"string","pattern":"^[a-z]+$","minLength":2,"maxLength":4},"json":{"type":"array"},"choice":{"enum":["a","b"]}},"required":["required"]}"""))
        val bad=validateFormValues(fields,mapOf("number" to JsonPrimitive("2.5"),"pattern" to JsonPrimitive("12"),"json" to JsonPrimitive("{}"),"choice" to JsonPrimitive("\"c\"")))
        assertEquals(setOf("required","number","pattern","json","choice"),bad.errors.keys)
        assertEquals("请输入整数",bad.errors["number"]);assertEquals("必须是 JSON 数组",bad.errors["json"])
        assertEquals("不能小于 2.0",validateFormValues(fields,mapOf("number" to JsonPrimitive("1"))).errors["number"])
        val optional=validateFormValues(schemaFields(obj("""{"properties":{"x":{"type":"string"},"flag":{"type":"boolean"}}}""")),emptyMap())
        assertEquals(obj("""{"flag":false}"""),optional.values)
    }
    @Test fun rejectsMalformedSecretMappingsInsteadOfDrawingPlaintext() {
        listOf("[]","{\"missing\":\"global/x\"}","{\"key\":\"global/UPPER\"}","{\"key\":\"file/x\"}").forEach { secret ->
            val base=form().envelope.body
            try { parseUiForm(request(JsonObject(base+("secret" to Json.parseToJsonElement(secret)))));fail("must reject $secret") } catch (_:InvalidForm) { }
        }
        try { parseUiForm(request(obj("""{"schema":{"properties":{"x":{"type":"number"}}},"secret":{"x":"global/x"}}""")));fail("numeric secret") } catch (_:InvalidForm) { }
    }
    @Test fun answersOnlyNamedScreenAndRepliesWithMaskNotOriginalSecret() {
        val form=form();assertEquals("mine",uiRequestAddressing(form.envelope,"screen"));assertEquals("other",uiRequestAddressing(form.envelope,"other"))
        assertEquals("unaddressed",uiRequestAddressing(request(obj("{}")),"screen"))
        val result=uiFormResult(form,obj("""{"name":"a","key":"dummy-value-1234"}"""))
        assertEquals(obj("""{"name":"a"}"""),result["values"]);assertEquals("****1234",result.objectAt("secret").objectAt("key").text("masked"))
        assertFalse(result.toString().contains("dummy-value-1234"));assertEquals("****",uiFormResult(form,obj("""{"key":"1234"}""")).objectAt("secret").objectAt("key").text("masked"))
    }
    @Test fun writesAllKeysBeforeResolveAndNeverResolvesWhenAWriteFails()=runBlocking<Unit> {
        val form=form();val events=mutableListOf<String>()
        answerUiForm(form,obj("""{"name":"a","key":"dummy"}"""),writeSecret={ resource,value -> assertEquals("dummy",value);events.add(resource) },resolve={ frame -> assertEquals("f",frame.text("req_id"));events.add("resolve") })
        assertEquals(listOf("global/demo_key","resolve"),events)
        events.clear()
        try { answerUiForm(form,obj("""{"key":"dummy"}"""),writeSecret={ _,_ -> throw IllegalStateException("write rejected") },resolve={ events.add("resolve") });fail("write failure") } catch (_:IllegalStateException) { }
        assertTrue(events.isEmpty())
    }
    @Test fun typedHumanAskAndApproveUseTheDifferentNodeContracts() {
        val ask=TaskFact("a","r","c","approval","Question",diagnostic=obj("""{"requestType":"human.ask"}"""))
        assertEquals(obj("""{"channel_id":"c","req_id":"r","text":"answer"}"""),approvalResolvePayload(ask,"approve",note="ignored",text="answer"))
        val approve=ask.copy(diagnostic=obj("""{"requestType":"human.approve"}"""))
        assertEquals(obj("""{"channel_id":"c","req_id":"r","decision":"reject","note":"why"}"""),approvalResolvePayload(approve,"reject",note="why"))
    }
}

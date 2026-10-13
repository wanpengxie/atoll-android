package dev.atoll.app.tasks

import dev.atoll.app.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class,DelicateCoroutinesApi::class)
class UiFormControllerTest {
    private fun obj(text: String)=Json.parseToJsonElement(text).jsonObject
    private fun request(id: String="f",session: String="screen")=Envelope(buildJsonObject {
        put("id",id);put("channel_id","c");put("kind","request");put("type","ui.form");put("audience",JsonArray(listOf(JsonPrimitive("me"))));put("sender",buildJsonObject { put("id","agent") })
        put("payload",buildJsonObject { put("body",obj("""{"session":"$session","schema":{"properties":{"name":{"type":"string"},"key":{"type":"string"}}},"secret":{"key":"global/test"}}""")) })
    })
    @Test fun sessionAddressingChecksExistenceWithoutReadingSecretAndWritesBeforeMaskedResolve()=runBlocking<Unit> {
        newSingleThreadContext("form-test").use { dispatcher -> withContext(dispatcher) {
            val wire=TaskTestWire();wire.handle={ type,payload ->
                if (type==Up.RESOURCE) when (payload.text("op")) { "read" -> obj("""{"value":"old-dummy"}""");"stat" -> obj("""{"exists":true}""");else -> obj("{}") } else obj("{}")
            }
            val controller=UiFormController(wire,{"me"},dispatcher=dispatcher)
            try {
                wire.frames.emit(DownFrame.Feed("",Row("c",1,request("other","other-screen"))))
                assertTrue(controller.state.value.forms.isEmpty())
                val envelope=request();wire.frames.emit(DownFrame.Feed("",Row("c",2,envelope)));wire.frames.emit(DownFrame.Feed("",Row("c",2,envelope)))
                withTimeout(5000) { controller.state.first { it.existing["key"]?.exists==true } }
                assertEquals(1,controller.state.value.forms.size);assertFalse(controller.state.value.toString().contains("old-dummy"))
                controller.submit(controller.state.value.current!!,obj("""{"name":"Name","key":"new-dummy-1234"}"""))
                assertTrue(controller.state.value.forms.isEmpty())
                assertEquals(listOf("stat","stat","write"),wire.requests.filter { it.first==Up.RESOURCE }.map { it.second.text("op") })
                val resolved=wire.requests.last().second
                assertEquals(Up.RESOLVE,wire.requests.last().first);assertFalse(resolved.toString().contains("new-dummy-1234"));assertEquals("****1234",resolved.objectAt("result").objectAt("secret").objectAt("key").text("masked"))
            } finally { controller.close() }
        } }
    }
    @Test fun writeFailureKeepsFormAndAlreadyClosedReportsWhichKeysWereWritten()=runBlocking<Unit> {
        newSingleThreadContext("form-close-test").use { dispatcher -> withContext(dispatcher) {
            val wire=TaskTestWire();var failWrite=true
            wire.handle={ type,payload ->
                when {
                    type==Up.RESOLVE -> throw WireException(type,"already_closed","closed")
                    payload.text("op")=="stat" -> obj("""{"exists":true}""")
                    payload.text("op")=="write" && failWrite -> obj("""{"status":"rejected","detail":"access_denied"}""")
                    else -> obj("{}")
                }
            }
            val controller=UiFormController(wire,{"me"},dispatcher=dispatcher)
            try {
                wire.frames.emit(DownFrame.Feed("",Row("c",1,request())));val form=withTimeout(5000) { controller.state.first { it.current!=null }.current!! }
                try { controller.submit(form,obj("""{"key":"dummy-1234"}"""));fail("write failure") } catch (_:WireException) { }
                assertEquals("f",controller.state.value.current!!.id);assertTrue(wire.requests.none { it.first==Up.RESOLVE })
                failWrite=false;controller.submit(form,obj("""{"key":"dummy-1234"}"""))
                assertNull(controller.state.value.current);assertTrue(controller.state.value.notice.contains("global/test"));assertFalse(controller.state.value.notice.contains("dummy-1234"))
            } finally { controller.close() }
        } }
    }
    @Test fun cancellationUsesUiErrorAndExplicitTerminalClosesTheModal()=runBlocking<Unit> {
        newSingleThreadContext("form-cancel-test").use { dispatcher -> withContext(dispatcher) {
            val wire=TaskTestWire();wire.handle={ _,_ -> obj("{}") };val controller=UiFormController(wire,{"me"},dispatcher=dispatcher)
            try {
                wire.frames.emit(DownFrame.Feed("",Row("c",1,request())));val form=withTimeout(5000) { controller.state.first { it.current!=null }.current!! }
                controller.cancel(form);assertNull(controller.state.value.current)
                val cancel=wire.requests.last { it.first==Up.RESOLVE }.second;assertEquals("cancelled",cancel.objectAt("error").text("code"));assertFalse(cancel.containsKey("decision"))
                wire.frames.emit(DownFrame.Feed("",Row("c",2,request("next"))))
                withTimeout(5000) { controller.state.first { it.current?.id=="next" } }
                val terminal=Envelope(obj("""{"id":"t","channel_id":"c","kind":"response","type":"ui.form","parent_id":"next","payload":{"body":{"status":"completed"}}}"""))
                wire.frames.emit(DownFrame.Feed("",Row("c",3,terminal)))
                withTimeout(5000) { controller.state.first { it.current==null } }
            } finally { controller.close() }
        } }
    }
}

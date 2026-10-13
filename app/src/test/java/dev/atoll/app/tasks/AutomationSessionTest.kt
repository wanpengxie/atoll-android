package dev.atoll.app.tasks

import dev.atoll.app.core.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AutomationSessionTest {
    private fun attached(boot: String)=WireState.Attached(1,buildJsonObject { put("boot",boot) })
    private fun event(channel: String,id: String)=Row(channel,1,Envelope(buildJsonObject {
        put("id","timer:$id");put("correlation_id","timer:$id");put("kind","event");put("ts",20)
        put("sender",buildJsonObject { put("kind","human");put("id","self") });put("audience",buildJsonArray { add("self") })
    }))
    @Test fun reconnectPreservesRecordsButBootAndSignOutDiscardThem() {
        val session=AutomationSession("boot-a");session.add(AutomationRecord("a","c",100,"human.message",createdAt=1))
        session.observe(WireState.Disconnected("offline",400));session.observe(attached("boot-a"));assertEquals(1,session.records.value.size)
        session.observe(attached("boot-b"));assertTrue(session.records.value.isEmpty())
        session.add(AutomationRecord("b","c",100,"human.message",createdAt=1));session.observe(WireState.SignedOut);assertTrue(session.records.value.isEmpty())
        assertTrue(AutomationSession("boot-b").records.value.isEmpty())
    }
    @Test fun backgroundChannelsShareRecordsAndImmediateFireBeforeReceiptIsRemembered() {
        val session=AutomationSession("boot")
        session.add(AutomationRecord("a","one",10,"human.message",createdAt=1))
        session.add(AutomationRecord("b","two",10,"human.message",createdAt=1))
        session.observeFiring(event("two","b"),"self")
        assertEquals(listOf("scheduled","fired"),session.records.value.map { it.state })
        session.observeFiring(event("one","early"),"self");session.add(AutomationRecord("early","one",10,"human.message",createdAt=1))
        assertEquals("fired",session.records.value.last().state)
        session.observe(attached("new-boot"));session.add(AutomationRecord("early","one",10,"human.message",createdAt=1))
        assertEquals("scheduled",session.records.value.single().state)
    }
}

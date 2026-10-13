package dev.atoll.app.tasks

import dev.atoll.app.core.Envelope
import dev.atoll.app.model.Turn
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ApprovalReceiptTest {
    private fun json(value: String) = Json.parseToJsonElement(value).jsonObject
    private fun envelope(type: String, body: JsonObject, sender: String = "agent:resolver") = Envelope(buildJsonObject {
        put("type",type);put("kind","response");put("sender",buildJsonObject { put("id",sender);put("kind","agent") })
        put("payload",buildJsonObject { put("body",body) })
    })
    private fun turn(body: String, type: String = "human.approve", unavailable: Boolean = false) = Turn(
        envelope(type,json("""{"title":"Decision"}""")),1,terminal=envelope(type,json(body)),terminalSeq=2,terminalClosureOnly=unavailable)
    @Test fun completedApprovalKeepsResolverDecisionAndBusinessResponse() {
        val receipt=approvalReceipt(turn("""{"status":"completed","decision":"reject","note":"Please revise","usage":{"tokens":3}}"""))!!
        assertEquals("agent:resolver",receipt.resolverId);assertEquals("reject",receipt.decision);assertEquals("completed",receipt.status)
        assertEquals(json("""{"decision":"reject","note":"Please revise"}"""),receipt.result!!.value)
        assertFalse(receipt.unavailable)
    }
    @Test fun askResponseUsesTheSameProseAndJsonMappingAsTheTimeline() {
        val prose=approvalReceipt(turn("""{"status":"completed","text":"The answer"}""","human.ask"))!!
        assertEquals("The answer",prose.text);assertNull(prose.result)
        val structured=approvalReceipt(turn(buildJsonObject { put("status","completed");put("text","{\"count\":2}") }.toString(),"human.ask"))!!
        assertEquals(json("""{"count":2}"""),structured.result!!.value);assertEquals("",structured.text)
    }
    @Test fun failedReceiptShowsTypedFailureAndOmitsTransportBookkeeping() {
        val receipt=approvalReceipt(turn("""{"status":"failed","error_code":"receiver_unavailable","detail":"Process ended","data":{"id":"q"},"closed_by":"system"}"""))!!
        assertEquals("failed",receipt.status);assertEquals("receiver_unavailable",receipt.result!!.errorCode)
        assertEquals("Process ended",receipt.result!!.detail);assertEquals(json("""{"data":{"id":"q"}}"""),receipt.result!!.errorData)
    }
    @Test fun compactClosureCannotInventAResponseOrResolverAndOpenRequestsHaveNoReceipt() {
        val receipt=approvalReceipt(turn("""{"status":"completed"}""",unavailable=true))!!
        assertTrue(receipt.unavailable);assertEquals("",receipt.resolverId);assertEquals("",receipt.text);assertNull(receipt.result)
        assertNull(approvalReceipt(turn("""{"status":"completed"}""").copy(terminal=null)))
    }
}

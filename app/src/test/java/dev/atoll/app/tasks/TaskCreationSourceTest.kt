package dev.atoll.app.tasks

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TaskCreationSourceTest {
    @Test fun messageAndTurnKeepTheRealLedgerSequenceThroughTaskSubmission() {
        val source=taskCreationSource("c","request-7",731,"request-7")
        val submitted=createTaskSubmission("c","agent","work",source=source)
        assertEquals(source,submitted.payload["source"])
        assertEquals(JsonPrimitive(731),source["seq"]);assertEquals(JsonPrimitive("turn"),source["objectType"])
        val message=taskCreationSource("c","message",732)
        assertEquals(JsonPrimitive("message"),message["objectType"]);assertFalse(message.containsKey("requestId"))
    }
    @Test fun missingLedgerSequenceCannotBeReplacedByAnInventedPosition() {
        try { taskCreationSource("c","r",0);fail("missing seq") } catch (_:IllegalArgumentException) { }
    }
}

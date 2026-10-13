package dev.atoll.app.net

import dev.atoll.app.core.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class FrameCodecTest {
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject
    @Test fun encodesVersionRefAndExactPayload() {
        val payload = obj("""{"channel_id":"c","msg_type":"agent.ask","payload":{"text":"hello"}}""")
        val encoded = obj(FrameCodec.encode(Up.SUBMIT, "submit-7", payload))
        assertEquals(JsonPrimitive(5), encoded["v"])
        assertEquals(JsonPrimitive("submit-7"), encoded["ref"])
        assertEquals(payload, encoded["payload"])
    }
    @Test fun rejectsUnknownFieldsAndMissingRequiredFields() {
        invalid(Up.CANCEL, """{"channel_id":"c","req_id":"r","extra":1}""")
        invalid(Up.CANCEL, """{"channel_id":"c"}""")
        invalid(Up.CANCEL, """{"channel_id":"c","req_id":null}""")
        invalid("unknown", "{}")
    }
    @Test fun validatesHistoryBoundsAndVocabulary() {
        val page = obj("""{"channel_id":"c","generation":1,"purpose":"hydrate","priority":"background"}""")
        FrameCodec.validate(Up.HISTORY_BEFORE, page)
        FrameCodec.validate(Up.HISTORY_BEFORE, JsonObject(page + ("generation" to JsonPrimitive(1.0))))
        for ((key, value) in listOf("before_seq" to JsonPrimitive(-1), "limit" to JsonPrimitive(201), "limit" to JsonPrimitive(1.5), "byte_limit" to JsonPrimitive(4_194_305), "generation" to JsonPrimitive(0), "purpose" to JsonPrimitive("other"), "priority" to JsonPrimitive("other"))) {
            assertThrows(FrameValidationException::class.java) { FrameCodec.validate(Up.HISTORY_BEFORE, JsonObject(page + (key to value))) }
        }
        invalid(Up.HISTORY_CANCEL, """{"channel_id":"c","generation":1,"target_ref":""}""")
        invalid(Up.CHANNEL_META, """{"channel_id":"c","generation":"1"}""")
    }
    @Test fun validatesAttachDurationAndResourceRules() {
        invalid(Up.ATTACH, """{"focus":"","history_protocol":4,"generation":1}""")
        invalid(Up.ATTACH, """{"focus":"","history_protocol":5,"generation":0}""")
        invalid(Up.AFTER, """{"channel_id":"c","duration_ms":0,"msg_type":"a"}""")
        invalid(Up.AFTER, """{"channel_id":"c","duration_ms":9007199254740992,"msg_type":"a"}""")
        invalid(Up.RESOURCE, """{"channel_id":"c","op":"read"}""")
        invalid(Up.RESOURCE, """{"channel_id":"c","op":"create"}""")
        invalid(Up.RESOURCE, """{"channel_id":"c","op":"stat","resource_id":"r","with_content":"true"}""")
        invalid(Up.RESOURCE, """{"channel_id":"c","op":"create","address":"a","node_type":"directory","with_content":true}""")
        invalid(Up.RESOURCE, """{"channel_id":"c","op":"read","resource_id":"r","node_type":"regular"}""")
        FrameCodec.validate(Up.RESOURCE, obj("""{"channel_id":"c","op":"create","address":"a","node_type":"directory"}"""))
    }
    @Test fun appliesUtf8ByteLimit() {
        invalid(Up.SUBMIT, """{"channel_id":"c","msg_type":"agent.ask","payload":{"text":"${"中".repeat(180_000)}"}}""")
    }
    @Test fun decodesEveryDownstreamTypeAndPreservesEnvelope() {
        assertTrue(FrameCodec.decode("""{"v":5,"frame_type":"receipt","ref":"r","payload":{"id":"m"}}""") is DownFrame.Receipt)
        val error = FrameCodec.decode("""{"v":5,"frame_type":"error","ref":"r","payload":{"frame":"submit","code":"forbidden","detail":"no"}}""") as DownFrame.Error
        assertEquals("forbidden", error.code)
        val feed = FrameCodec.decode("""{"v":5,"frame_type":"feed","ref":"r","payload":{"channel_id":"c","seq":9,"envelope":{"id":"m","unknown":true,"payload":{"body":{"text":"x"}}}}}""") as DownFrame.Feed
        assertEquals(9L, feed.row.seq)
        assertEquals(JsonPrimitive(true), feed.row.envelope.json["unknown"])
        assertEquals("x", feed.row.envelope.body["text"].string())
        val checkpoint = FrameCodec.decode("""{"v":5,"frame_type":"checkpoint","payload":{"channel_id":"c","scan_low_seq":2,"scanned_seq":9,"generation":3}}""") as DownFrame.Checkpoint
        assertEquals(3L, checkpoint.generation)
        assertTrue(FrameCodec.decode("""{"v":5,"frame_type":"page_end","ref":"r","payload":{"channel_id":"c"}}""") is DownFrame.PageEnd)
        assertTrue(FrameCodec.decode("""{"v":5,"frame_type":"observe_ended","payload":{"channel_id":"c","reason":"now_member"}}""") is DownFrame.ObserveEnded)
        assertTrue(FrameCodec.decode("""{"v":5,"frame_type":"memberships","payload":{}}""") is DownFrame.Memberships)
    }
    @Test fun dropsUnknownTypesButRejectsWrongVersionAndMalformedJson() {
        assertNull(FrameCodec.decode("""{"v":5,"frame_type":"future"}"""))
        assertThrows(FrameVersionException::class.java) { FrameCodec.decode("""{"v":4,"frame_type":"future"}""") }
        assertThrows(Exception::class.java) { FrameCodec.decode("not-json") }
    }
    private fun invalid(type: String, payload: String) {
        assertThrows(FrameValidationException::class.java) { FrameCodec.encode(type, "ref", obj(payload)) }
    }
}

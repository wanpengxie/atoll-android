package dev.atoll.app.compose

import dev.atoll.app.files.FileReference
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AttachmentUploaderTest {
    @Test fun sharedFileReferencesAreTransmittedExactlyWithoutTicketsOrRendererHandles() {
        val reference = FileReference("c0", "daemon://local-device/c0/a.txt", "a.txt", "text/plain", 5, line = 12)
        val frame = ComposerDelivery.messageFrames("c0", ComposerDraft(text = "file", attachments = listOf(reference)), Delivery(listOf(AGENT), "only"), { "id" }).single()
        assertEquals(JsonArray(listOf(reference.attachment())), frame.getValue("payload").jsonObject["attachments"])
        val serialized = Json.encodeToString(ComposerDraft(attachments = listOf(reference)))
        assertFalse(serialized.contains("ticket"))
        assertEquals(12, Json.decodeFromString<ComposerDraft>(serialized).attachments.single().line)
    }
}

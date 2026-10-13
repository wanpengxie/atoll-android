package dev.atoll.app.app

import dev.atoll.app.ui.screens.MessageAttachmentViewState
import org.junit.Assert.*
import org.junit.Test

class AttachmentActionsTest {
    @Test fun usesResourceIdentityAndMetadataRatherThanDisplayUri() {
        val reference = attachmentReference("channel", MessageAttachmentViewState("opaque-resource", "report.pdf",
            "application/pdf", 2048, "daemon://device/channel/other.pdf"))!!
        assertEquals("channel", reference.channelId)
        assertEquals("opaque-resource", reference.resourceId)
        assertEquals("report.pdf", reference.name)
        assertEquals("application/pdf", reference.mediaType)
        assertEquals(2048L, reference.size)
    }

    @Test fun legacyLinkUsesExistingFilePathParsing() {
        val reference = attachmentReference("channel", MessageAttachmentViewState("", "", uri = "daemon://device/channel/report%20one.txt:12"))!!
        assertEquals("daemon://device/channel/report%20one.txt", reference.resourceId)
        assertEquals("report one.txt", reference.name)
        assertEquals(12, reference.line)
        assertEquals(-1L, reference.size)
    }

    @Test fun missingResourceDoesNotOpenOrDownload() {
        assertNull(attachmentReference("channel", MessageAttachmentViewState("", "label only")))
    }
}

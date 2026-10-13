package dev.atoll.app.ui

import dev.atoll.app.ui.components.*
import dev.atoll.app.ui.screens.MessageAttachmentViewState
import org.junit.Assert.*
import org.junit.Test

class MessageAttachmentTest {
    @Test fun missingMetadataDoesNotInventANameOrSize() {
        assertEquals("resource-1", attachmentName(MessageAttachmentViewState("resource-1", "")))
        assertEquals("附件", attachmentName(MessageAttachmentViewState("", "")))
        assertNull(attachmentSize(null)); assertNull(attachmentSize(-1))
        assertEquals("0 B", attachmentSize(0))
        assertEquals("文件", attachmentType(""))
    }
    @Test fun mediaTagsMatchWebAndSizesUseBytes() {
        assertEquals("PDF", attachmentType("application/pdf"))
        assertEquals("图片", attachmentType("image/png"))
        assertEquals("音频", attachmentType("audio/ogg"))
        assertEquals("视频", attachmentType("video/mp4"))
        assertEquals("文本", attachmentType("text/markdown"))
        assertEquals("文件", attachmentType("application/octet-stream"))
        assertEquals("1023 B", attachmentSize(1023))
        assertEquals("1.0 KB", attachmentSize(1024))
        assertEquals("1.5 MB", attachmentSize(1572864))
    }
}

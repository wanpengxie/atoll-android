package dev.atoll.app.app

import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.atoll.app.files.FileReference
import dev.atoll.app.ui.screens.*
import dev.atoll.app.ui.theme.AtollTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Fake attachment state exercises the same channel callback as the workspace. */
@RunWith(AndroidJUnit4::class)
class AttachmentActionsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun channelAttachmentOpensPreviewAndDownloadsOnceWhileBusy() {
        val previews = mutableListOf<FileReference>()
        val downloads = mutableListOf<Pair<FileReference, Uri?>>()
        val busy = mutableStateOf(false)
        val attachment = MessageAttachmentViewState("opaque-resource", "integration-report.pdf", "application/pdf", 2048)
        compose.setContent {
            val handler = rememberAttachmentHandler("test-channel", previews::add,
                onDownload = { reference, uri -> downloads += reference to uri; busy.value = true },
                downloading = { busy.value })
            AtollTheme {
                ChannelScreen(ConversationViewState(ChannelViewState("test-channel", "test"),
                    entries = listOf(TimelineEntry.Message(MessageViewState("m", "root", "", "12:00", self = true,
                        attachments = listOf(attachment))))), ComposerViewState(), ChannelTab.Conversation,
                    onBack = {}, onTab = {}, onScope = {}, onActor = {}, onText = {}, onSend = {}, onOlder = {},
                    composerVisible = false, onAttachment = handler)
            }
        }
        compose.onNodeWithText(attachment.name).performClick()
        compose.onNodeWithContentDescription("下载 ${attachment.name}").performClick()
        compose.onNodeWithContentDescription("下载 ${attachment.name}").performClick()
        compose.runOnIdle {
            val expected = FileReference("test-channel", "opaque-resource", attachment.name, "application/pdf", 2048)
            assertEquals(listOf(expected), previews)
            assertEquals(listOf(expected to null), downloads)
        }
    }
}

package dev.atoll.app.compose

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atoll.app.core.Envelope
import dev.atoll.app.ui.components.*
import dev.atoll.app.ui.screens.*
import dev.atoll.app.ui.theme.AtollTheme
import dev.atoll.app.ui.theme.AtollColors
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TurnStatesUiTest {
    @get:Rule val compose = createComposeRule()

    private fun envelope(id: String) = Envelope(buildJsonObject {
        put("id",id); put("channel_id","fixture"); put("kind","request"); put("type","agent.ask")
        put("sender",buildJsonObject { put("id","self"); put("kind","human") })
        put("audience",buildJsonArray { add("agent:builder") })
    })
    @Composable private fun Fixture(content: @Composable () -> Unit) {
        AtollTheme {
            val cache = remember { MarkdownCache() }
            CompositionLocalProvider(LocalMarkdownCache provides cache) {
                Surface(color = AtollColors.Ground) { Column(Modifier.fillMaxWidth().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) { content() } }
            }
        }
    }
    @Test fun mergedRowLinksToDestinationWithoutDisplayingExpandedContent() {
        val opened = mutableListOf<String>()
        val message = MessageViewState("merged", "我", "补充截图", "10:50", self = true, recipient = "builder",
            turnState = TurnViewState(TurnKind.Merged,"merged","destination"))
        compose.setContent { Fixture {
            TimelineMessageActions(message,envelope("merged"),null,"self",emptyList(),true,true,false,emptySet(),
                onReply={},onEdit={},onOpenProcess={opened += it}) { Text("expanded process should remain hidden") }
        } }
        compose.onNodeWithText("expanded process should remain hidden").assertDoesNotExist()
        compose.onNodeWithText("查看 ›").performClick()
        compose.runOnIdle { assertEquals(listOf("destination"),opened) }
        screenshot("merged")
    }
    @Test fun failedAndLostRowsOfferResendAndKeepDisabledActionsInert() {
        var sent = 0
        val enabled = mutableStateOf(true)
        val failure = MessageViewState("failed","builder","","10:51",agent=true,status=MessageStatus.Failed,
            result=ResultViewState(errorCode="receiver_unavailable",detail="接收方进程已结束"),part=MessagePart.Answer,
            turnState=TurnViewState(TurnKind.Failed,"failed",resendable=true))
        val lost = MessageViewState("lost","我","整理发布清单","10:52",self=true,recipient="planner",
            turnState=TurnViewState(TurnKind.Lost,"lost",lostReason="restart"))
        compose.setContent { Fixture {
            for (message in listOf(failure,lost)) TimelineMessageActions(message,envelope(message.id),null,"self",emptyList(),
                true,true,false,emptySet(),onReply={},onEdit={},onOpenProcess={},onResend={sent++},resendEnabled=enabled.value) {
                MessageFrame(message)
            }
        } }
        screenshot("failed-lost-ready")
        compose.onNodeWithText("重新发送").performClick()
        compose.onNodeWithText("重发").performClick()
        compose.onAllNodesWithText("查看过程", substring = true).assertCountEquals(1)
        compose.runOnIdle { assertEquals(2,sent); enabled.value=false }
        compose.onNodeWithText("重新发送").assertIsNotEnabled()
        compose.onNodeWithText("重发").assertIsNotEnabled()
        screenshot("failed-lost")
    }
    @Test fun acceptedEditsHaveAHeaderTagRatherThanAnEditorSessionLabel() {
        val message = MessageViewState("edited","我","测试通过后上传 APK","10:53",self=true,recipient="builder",part=MessagePart.Request,
            turnState=TurnViewState(requestId="edited",edited=true))
        compose.setContent { Fixture { MessageFrame(message) } }
        compose.onNodeWithText("已编辑").assertIsDisplayed()
        compose.onNodeWithText("正在输入框中编辑").assertDoesNotExist()
        screenshot("edited")
    }
    private fun screenshot(name: String) {
        val path=InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?: "/sdcard/Android/media/dev.atoll.app/additional_test_output"
        require(path=="/sdcard/Android/media/dev.atoll.app/additional_test_output")
        val folder=File(path,"w5-turnstates").also { it.mkdirs() }
        val bitmap=compose.onRoot().captureToImage().asAndroidBitmap()
        File(folder,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
    }
}

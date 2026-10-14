package dev.atoll.app.files

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atoll.app.core.*
import dev.atoll.app.net.MemoryKeyValueStore
import dev.atoll.app.ui.screens.*
import dev.atoll.app.ui.theme.AtollTheme
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** In-memory HTTP and Wire replies only: no node access, credentials or remote mutations. */
@RunWith(AndroidJUnit4::class)
class FilesDesignUiTest {
    @get:Rule val compose = createComposeRule()
    private val wire = FakeFilesWire()
    private lateinit var vm: FilesViewModel
    private var resources = 0
    private var closes = 0

    private fun mount() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val text = when {
                request.url.encodedPath.endsWith("/profile") -> """{"items":[{"declared":{"qualified_name":"atoll-web"}}]}"""
                request.url.encodedPath.endsWith("/devices") -> """{"items":[{"declared":{"device_id":"local-device","name":"local-device"}}]}"""
                else -> "FILE_PREVIEW_CONTENT\n"
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Type", "text/plain").header("Content-Length", text.toByteArray().size.toString())
                .body(text.toResponseBody("text/plain".toMediaType())).build()
        }.build()
        compose.runOnUiThread {
            vm = FilesViewModel(wire, http, "http://files.invalid", "test", File(context.cacheDir, "files-design"),
                FileHistory(MemoryKeyValueStore(), "fake", "self", "boot"))
        }
        compose.setContent { AtollTheme {
            ChannelScreen(ConversationViewState(ChannelViewState("test", "atoll-web"), filesOpen = true), ComposerViewState(""),
                ChannelTab.Conversation, {}, {}, {}, {}, {}, {}, {}, composerVisible = false,
                contentOverride = { FilesScreen(vm, onClose = { closes++ }, onResources = { resources++ }) })
        } }
        compose.waitUntil(10_000) { !vm.state.value.busy && vm.state.value.entries.isNotEmpty() }
    }

    @Test fun compactToolbarKeepsKvRefreshAndCloseInsideDeviceMenu() {
        mount()
        compose.onNodeWithText("Files").assertDoesNotExist()
        compose.onNodeWithText("KV").assertDoesNotExist()
        listOf("日期", "大小", "种类", "最近查看").forEach { compose.onNodeWithText(it).assertDoesNotExist() }
        compose.onNodeWithText("名称").assertIsDisplayed()
        compose.onNodeWithText("最近阅读").assertIsDisplayed()
        val list = compose.onNodeWithTag("files-list").fetchSemanticsNode().boundsInRoot
        val tools = compose.onNodeWithContentDescription("文件设备与操作").fetchSemanticsNode().boundsInRoot
        assertTrue("Only toolbar and breadcrumbs may precede the list", list.top - tools.bottom < tools.height * 2)
        screenshot("files-compact")
        compose.onNodeWithContentDescription("文件设备与操作").performClick()
        compose.onNodeWithText("KV 资源").performClick()
        compose.runOnIdle { assertEquals(1, resources) }
        compose.onNodeWithContentDescription("文件设备与操作").performClick()
        compose.onNodeWithText("刷新").performClick()
        compose.waitUntil(10_000) { !vm.state.value.busy }
        compose.onNodeWithContentDescription("文件设备与操作").performClick()
        compose.onNodeWithText("关闭文件").performClick()
        compose.runOnIdle { assertEquals(1, closes) }
    }

    @Test fun nameToggleFoldersPreviewRecentAndConfirmedDeletionRetainTheirPaths() {
        mount()
        compose.onNodeWithText("名称").performClick()
        compose.waitUntil(10_000) { vm.state.value.descending }
        compose.onNodeWithContentDescription("名称排序：降序").assertIsDisplayed()
        compose.onNodeWithText("名称").performClick()
        compose.waitUntil(10_000) { !vm.state.value.descending }
        compose.onNodeWithText("mapping").performClick()
        compose.waitUntil(10_000) { vm.state.value.directory == "mapping/" && !vm.state.value.busy }
        compose.onNodeWithText("根目录").performClick()
        compose.waitUntil(10_000) { vm.state.value.directory.isEmpty() && !vm.state.value.busy }
        compose.onNodeWithText("CHANGELOG.txt").performClick()
        compose.waitUntil(10_000) { vm.state.value.preview?.text?.contains("FILE_PREVIEW_CONTENT") == true }
        compose.onNodeWithText("FILE_PREVIEW_CONTENT", substring = true).assertIsDisplayed()
        screenshot("files-preview")
        compose.onNodeWithText("‹ 返回").performClick()
        compose.onNodeWithText("最近阅读").performClick()
        compose.onAllNodesWithText("CHANGELOG.txt", useUnmergedTree = true).assertCountEquals(2)
        compose.onAllNodesWithText("最近阅读", useUnmergedTree = true).assertCountEquals(2)
        compose.onNode(hasText("CHANGELOG.txt") and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(10_000) { vm.state.value.selected != null && !vm.state.value.previewLoading }
        compose.onNodeWithText("‹ 返回").performClick()
        compose.onNode(hasText("CHANGELOG.txt") and hasAnyAncestor(hasTestTag("files-list"))).performTouchInput { longClick() }
        compose.onNodeWithText("确定删除“CHANGELOG.txt”吗？文件夹必须为空。").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        assertFalse(wire.ops.contains("delete"))
        compose.onNode(hasText("CHANGELOG.txt") and hasAnyAncestor(hasTestTag("files-list"))).performTouchInput { longClick() }
        compose.onNodeWithText("删除", substring = false).performClick()
        compose.waitUntil(10_000) { wire.ops.contains("delete") }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val directory = File(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?: "/sdcard/Android/media/dev.atoll.app/additional_test_output", "files-design")
        check(directory.mkdirs() || directory.isDirectory)
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private class FakeFilesWire : WireClient {
        override val state = MutableStateFlow<WireState>(WireState.Attached(1, JsonObject(emptyMap())))
        override val frames = MutableSharedFlow<DownFrame>()
        val ops = java.util.concurrent.CopyOnWriteArrayList<String>()
        override suspend fun request(type: String, payload: JsonObject): JsonObject {
            val op = payload["op"]?.jsonPrimitive?.content.orEmpty(); ops.add(op)
            return when (op) {
                "list" -> buildJsonObject { put("items", buildJsonArray {
                    val prefix = payload["query"]!!.jsonObject["prefix"]!!.jsonPrimitive.content
                    for ((name, folder) in listOf("mapping" to true, "atoll-release.apk" to false, "CHANGELOG.txt" to false, "ReleaseGate.kt" to false))
                        add(buildJsonObject { put("id", prefix + name); put("meta", buildJsonObject { put("node_type", if (folder) "directory" else "regular"); put("size", 3400) }) })
                }) }
                "read" -> buildJsonObject { put("ticket", "fake-local-ticket"); put("resource_id", payload["resource_id"]!!) }
                "delete" -> JsonObject(emptyMap())
                else -> error("Unexpected fake operation: $op")
            }
        }
        override suspend fun history(payload: JsonObject): HistoryPage = error("No history in file UI test")
    }
}

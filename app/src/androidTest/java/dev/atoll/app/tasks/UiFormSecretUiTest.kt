package dev.atoll.app.tasks

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atoll.app.core.*
import dev.atoll.app.ui.theme.AtollTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** An agent's ui.form with a secret field: typed masked, written only to its global resource, answered with a mask. */
@RunWith(AndroidJUnit4::class)
class UiFormSecretUiTest {
    @get:Rule val compose = createComposeRule()

    private class FakeWire : WireClient {
        override val state = MutableStateFlow<WireState>(WireState.Attached(1, buildJsonObject { put("session", "s1") }))
        override val frames = MutableSharedFlow<DownFrame>(replay = 1)
        val sent = java.util.concurrent.CopyOnWriteArrayList<Pair<String, JsonObject>>()
        override suspend fun request(type: String, payload: JsonObject): JsonObject {
            sent += type to payload
            return if (type == Up.RESOURCE && payload["op"]?.jsonPrimitive?.content == "stat") buildJsonObject { put("exists", false) } else JsonObject(emptyMap())
        }
        override suspend fun history(payload: JsonObject) = error("unused")
    }

    private val form = Envelope(buildJsonObject {
        put("id", "form-1"); put("channel_id", "c"); put("kind", "request"); put("type", "ui.form")
        put("sender", buildJsonObject { put("id", "agent:builder"); put("kind", "agent") })
        put("audience", buildJsonArray { add("human:me") })
        put("payload", buildJsonObject { put("body", buildJsonObject {
            put("session", "s1"); put("title", "部署参数")
            put("schema", buildJsonObject {
                put("type", "object")
                put("required", buildJsonArray { add("env"); add("key") })
                put("properties", buildJsonObject {
                    put("env", buildJsonObject { put("type", "string"); put("title", "环境"); put("enum", buildJsonArray { add("staging"); add("prod") }) })
                    put("key", buildJsonObject { put("type", "string"); put("title", "签名密钥") })
                })
            })
            put("secret", buildJsonObject { put("key", "global/sign_key") })
        }) })
    })

    @Test fun secretIsMaskedWrittenToItsResourceAndAnsweredWithAMask() {
        val wire = FakeWire()
        val controller = UiFormController(wire, { "human:me" })
        runBlocking { wire.frames.emit(DownFrame.Feed("f", Row("c", 1, form))) }
        compose.setContent { AtollTheme { UiFormsHost(controller, mapOf("agent:builder" to "builder")) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("builder 请你填写 · 部署参数").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("必填 · 密钥 · 回复中只留掩码").assertExists()
        compose.onNodeWithText("这个 key 现在还没有值。").assertExists()
        compose.onNodeWithText("请选择").performClick()
        compose.onNodeWithText("staging").performClick()
        compose.onNodeWithContentDescription("签名密钥").performTextInput("hunter2-secret")
        compose.onNodeWithText("hunter2-secret").assertDoesNotExist()
        screenshot("form-secret")
        compose.onNodeWithText("提交").performClick()
        compose.waitUntil(5_000) { wire.sent.any { it.first == Up.RESOLVE } }
        val write = wire.sent.first { it.first == Up.RESOURCE && it.second["op"]?.jsonPrimitive?.content in setOf("create", "write") }.second
        assertEquals("global/sign_key", write["resource_id"]!!.jsonPrimitive.content)
        assertEquals("hunter2-secret", write["args"]!!.jsonPrimitive.content)
        val resolve = wire.sent.first { it.first == Up.RESOLVE }.second.toString()
        assertFalse("the reply must not carry the secret", "hunter2-secret" in resolve)
        assertTrue(resolve, "****cret" in resolve && "staging" in resolve)
    }

    private fun screenshot(name: String) {
        val directory = File(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?: "/sdcard/Android/media/dev.atoll.app/additional_test_output", "chat-v2")
        check(directory.mkdirs() || directory.isDirectory)
        compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

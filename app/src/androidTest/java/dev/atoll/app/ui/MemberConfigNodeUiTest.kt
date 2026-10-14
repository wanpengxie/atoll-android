package dev.atoll.app.ui

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atoll.app.AtollApp
import dev.atoll.app.MainActivity
import dev.atoll.app.app.*
import dev.atoll.app.net.actorRequest
import dev.atoll.app.ui.screens.LoginViewState
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The only write is one explicitly confirmed restart of the isolated node's test-codex. */
@RunWith(AndroidJUnit4::class)
class MemberConfigNodeUiTest {
    private val args = InstrumentationRegistry.getArguments()
    private val server = args.getString("atollUrl").also { require(it == "http://100.99.232.10:18080") }!!
    private val channel = args.getString("atollChannelId").orEmpty().also { require(it.matches(Regex("[a-f0-9-]{36}"))) }
    private val actor = args.getString("atollCodexId").orEmpty().also { require(it.startsWith("agent:test-codex:")) }
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun waitNode(matcher: SemanticsMatcher, timeout: Long = 40_000): SemanticsNodeInteraction {
        compose.waitUntil(timeout) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        return compose.onAllNodes(matcher).onFirst()
    }
    private val output by lazy { File(args.getString("additionalTestOutputDir")
        ?: "/sdcard/Android/media/dev.atoll.app/additional_test_output", "member-config-node").also { it.mkdirs() } }
    private fun capture(name: String) {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    @Test fun confirmedRestartCompletesAndTheMemberBuildsAndReturnsBound() {
        val graph = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AtollApp).graph
        compose.runOnIdle { graph.login(LoginViewState(server, "root", args.getString("atollPassword").orEmpty())) }
        compose.waitUntil(40_000) { graph.services.value?.server == server && graph.state.value.identityConfirmed }
        compose.runOnIdle { graph.openChannel(channel) }
        compose.waitUntil(40_000) { graph.state.value.route?.channel == channel && graph.services.value?.selfIds?.get(channel) != null && graph.state.value.roster.any { it.id == actor && it.name == "test-codex" } }
        val services = graph.services.value!!
        require(services.server == server)
        val body = buildJsonObject { put("member", actor) }
        fun read() = runBlocking { withTimeout(30_000) { services.wire.actorRequest(channel, "system", "system.member.get", body) } }
        val before = read()["build"]?.jsonObject?.get("attempt")?.jsonPrimitive?.longOrNull ?: 0L
        compose.runOnIdle { graph.route(focusedChannelLink(channel, FocusKind.Participant, actor)) }
        waitNode(hasText("成员详情"))
        waitNode(hasText("读取配置") and hasClickAction()).performClick()
        waitNode(hasText("编辑配置") and hasClickAction())
        waitNode(hasText("重启") and hasClickAction() and isEnabled()).performClick()
        waitNode(hasText("重启 test-codex？")).assertIsDisplayed()
        capture("restart-confirmation")
        waitNode(hasText("确认重启") and hasClickAction()).performClick()
        waitNode(hasText("重启请求已完成；正在刷新成员状态。")).assertExists()
        val built = runBlocking { withTimeout(120_000) {
            var value: JsonObject
            do {
                delay(1_000)
                value = services.wire.actorRequest(channel, "system", "system.member.get", body)["build"]?.jsonObject ?: buildJsonObject {}
            } while ((value["attempt"]?.jsonPrimitive?.longOrNull ?: 0L) <= before || value["result"]?.jsonPrimitive?.content != "ok")
            value
        } }
        val actual = runBlocking { withTimeout(60_000) {
            var measures: JsonArray
            do {
                val rows = services.obs.channelActors(channel)["items"]?.jsonArray.orEmpty()
                val row = rows.filterIsInstance<JsonObject>().firstOrNull { (it["declared"] as? JsonObject)?.get("id")?.jsonPrimitive?.content == actor }
                measures = (row?.get("actual") as? JsonObject)?.get("measures") as? JsonArray ?: JsonArray(emptyList())
                if (!bound(measures)) delay(1_000)
            } while (!bound(measures))
            measures
        } }
        waitNode(hasContentDescription("刷新配置") and isEnabled()).performClick()
        waitNode(hasText("构建成功", substring = true)).assertIsDisplayed()
        capture("restart-rebuilt-online")
        File(output, "restart-result.json").writeText(buildJsonObject {
            put("server", server); put("channel", channel); put("actor", actor)
            put("before_attempt", before); put("after_attempt", built["attempt"] ?: JsonNull)
            put("result", "ok"); put("bound", bound(actual)); put("confirmed_in_ui", true)
            put("device_online", actual.filterIsInstance<JsonObject>().firstOrNull { it["name"]?.jsonPrimitive?.content == "device_online" }?.get("value") ?: JsonNull)
        }.toString())
    }
    private fun bound(measures: JsonArray): Boolean = listOf("bound").all { name ->
        measures.filterIsInstance<JsonObject>().any { it["name"]?.jsonPrimitive?.content == name && it["value"]?.jsonPrimitive?.booleanOrNull == true }
    }
}

package dev.atoll.app.e2e

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.atoll.app.AtollApp
import dev.atoll.app.MainActivity
import dev.atoll.app.app.*
import dev.atoll.app.core.DownFrame
import dev.atoll.app.files.FileRepository
import dev.atoll.app.terminal.PtyStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.*
import okhttp3.Request
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID

private const val TEST_URL = "http://100.99.232.10:18080"
private val STEPS = listOf("login", "channel-list", "open-channel", "codex-reply", "waiting-queue", "waiting-cancel",
    "waiting-edit", "waiting-insert", "files", "preview", "terminal-ls", "tasks", "members", "channel-details",
    "create-child", "retire-child", "logout")

/** URL validation happens while constructing the test, before any Activity or account restore. */
private class Configuration {
    private val args = InstrumentationRegistry.getArguments()
    val url = args.getString("atollUrl").also { require(it == TEST_URL) { "Only the isolated test node is allowed" } }!!
    val password = args.getString("atollPassword").orEmpty().also { require(it.matches(Regex("[A-Za-z0-9]{32}"))) { "Isolated password argument is missing" } }
    val token = args.getString("atollToken").orEmpty()
    val channel = args.getString("atollChannelId").orEmpty().also { require(it.matches(Regex("[a-f0-9-]{36}"))) { "Test channel ID is missing" } }
    val codex = args.getString("atollCodexId").orEmpty().also { require(it.startsWith("agent:test-codex:")) { "Test codex ID is missing" } }
    fun redact(text: String): String = text.replace(password, "[redacted]").let { if (token.isEmpty()) it else it.replace(token, "[redacted]") }
}

@RunWith(AndroidJUnit4::class)
class IsolatedNodeSmokeTest {
    private val config = Configuration()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val runId = UUID.randomUUID().toString().replace("-", "").take(10)
    private val marker = "E2E_$runId"
    private val filename = "android-e2e-$runId.txt"
    private val child = "e2e-$runId"
    private val records = mutableListOf<JsonObject>()
    private val output by lazy {
        val path = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?: "/sdcard/Android/media/dev.atoll.app/additional_test_output"
        require(path == "/sdcard/Android/media/dev.atoll.app/additional_test_output") { "Unexpected artifact path" }
        File(path, runId).also { check(it.mkdirs() || it.isDirectory) }
    }
    private val compose = createAndroidComposeRule<MainActivity>()
    private val prepare = object : ExternalResource() {
        override fun before() = runBlocking<Unit> {
            // Only this node's preferences/cookie. Never launch with the user's default server.
            val preferences = AppPreferences(context)
            preferences.saveAccount(config.url, "root")
            preferences.forgetBootstrap(config.url, "root")
            preferences.put(accountNamespace(config.url, "root"), "last-channel", "")
            preferences.put(accountNamespace(config.url, "root"), "${config.channel}.surface", "")
            preferences.saveOptions(accountNamespace(config.url, "root"), config.channel, ChannelViewOptions())
            context.getSharedPreferences("identity_${accountNamespace(config.url, "root")}", Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
    @get:Rule val rules: RuleChain = RuleChain.outerRule(prepare).around(compose)
    private val graph get() = (context.applicationContext as AtollApp).graph
    private val editor = hasTestTag("atoll.composer.editor")

    private fun nodes(matcher: SemanticsMatcher, unmerged: Boolean = false) =
        compose.onAllNodes(matcher, useUnmergedTree = unmerged).fetchSemanticsNodes(atLeastOneRootRequired = false)
    private fun waitFor(matcher: SemanticsMatcher, timeout: Long = 40_000, unmerged: Boolean = false): SemanticsNodeInteraction {
        compose.waitUntil(timeoutMillis = timeout) { nodes(matcher, unmerged).isNotEmpty() }
        return compose.onAllNodes(matcher, useUnmergedTree = unmerged).onFirst()
    }
    private fun click(text: String) = waitFor(hasText(text) and hasClickAction() and isEnabled()).performClick()
    private fun replace(label: String, value: String, private: Boolean = false) {
        val field = waitFor(hasSetTextAction() and hasText(label))
        try { field.performTextReplacement(value) } catch (failure: Throwable) {
            if (private) throw AssertionError("Password replacement action failed") else throw failure
        }
        compose.waitUntil(timeoutMillis = 10_000) {
            val actual = field.fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text
            if (private) actual?.length == value.length else actual == value
        }
    }
    private fun menu(label: String) { waitFor(hasContentDescription("频道菜单") and hasClickAction()).performClick(); click(label) }
    private fun services(): WorkspaceServices = graph.services.value?.also { check(it.server == TEST_URL) { "Session origin is not the isolated node" } }
        ?: error("Authenticated services unavailable")
    private fun send(text: String) {
        waitFor(editor).performTextReplacement(text)
        compose.waitUntil(timeoutMillis = 10_000) { nodes(editor).singleOrNull()?.config?.getOrNull(SemanticsProperties.EditableText)?.text == text }
        waitFor(hasContentDescription("发送") and hasClickAction() and isEnabled()).performClick()
        compose.waitUntil(timeoutMillis = 20_000) { nodes(editor).singleOrNull()?.config?.getOrNull(SemanticsProperties.EditableText)?.text == "" }
    }
    private fun sendRunning(text: String) {
        val processing = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var requestId = ""
            services().wire.frames.filterIsInstance<DownFrame.Feed>().map { it.row.envelope }.first { envelope ->
                if (envelope.kind == "request" && envelope.type == "agent.ask" &&
                    (envelope.body["text"] as? JsonPrimitive)?.content == text) requestId = envelope.id
                requestId.isNotEmpty() && envelope.senderId == config.codex && envelope.status == "processing" &&
                    (envelope.parentId == requestId || envelope.correlationId == requestId)
            }
            processing.complete(Unit)
        }
        try {
            send(text)
            runBlocking { withTimeout(30_000) { processing.await() } }
        } finally { scope.cancel() }
    }
    private fun waitingRow(text: String): SemanticsMatcher = SemanticsMatcher("waiting row") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("atoll.waiting.") == true
    } and hasAnyDescendant(hasText(text, substring = true))
    private fun waitingAction(marker: String, action: String) = waitFor(
        hasText(action) and hasClickAction() and isEnabled() and hasAnyAncestor(waitingRow(marker))).performClick()
    private fun answer(value: String) = waitFor(hasText(value), timeout = 180_000, unmerged = true).assertExists()

    private fun capture(name: String) {
        val screenshot = instrumentation.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
        File(output, "$name.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
        // No editable values: a password semantics node may expose its unmasked value.
        val captions = nodes(SemanticsMatcher("any node") { true }, true).filterNot { it.config.contains(SemanticsProperties.Password) }
            .mapNotNull { it.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { t -> t.text } }.distinct()
        File(output, "$name-ui.json").writeText(JsonArray(captions.map { JsonPrimitive(config.redact(it)) }).toString())
    }
    private fun summary() = File(output, "summary.json").writeText(buildJsonObject {
        put("server", TEST_URL); put("run", runId); put("channel_id", config.channel)
        put("steps", JsonArray(records)); put("pending", JsonArray(STEPS.filter { name -> records.none { it["step"] == JsonPrimitive(name) } }.map(::JsonPrimitive)))
    }.toString())
    private fun step(name: String, action: () -> Unit) {
        val started = System.nanoTime()
        try {
            action(); capture(name)
            records += buildJsonObject { put("step", name); put("status", "passed"); put("millis", (System.nanoTime() - started) / 1_000_000) }
            println("PASS $name")
        } catch (failure: Throwable) {
            runCatching { capture("$name-failed") }
            File(output, "$name-failure.txt").writeText(config.redact(failure.stackTraceToString()))
            println(config.redact(failure.stackTraceToString()))
            records += buildJsonObject { put("step", name); put("status", "failed"); put("error", failure.javaClass.simpleName) }
            throw AssertionError("Smoke failed at $name (${failure.javaClass.simpleName}); artifacts: ${output.absolutePath}")
        } finally { summary() }
    }

    @Test fun allSeventeenStepsOnOurNode() {
        step("login") {
            waitFor(hasText("进入 Atoll"))
            replace("服务器地址", config.url); replace("账号", "root"); replace("密码", config.password, private = true)
            // The login screen now owns local fields and only publishes them at submit.
            // Read the rendered server field at that boundary; password semantics are masked.
            val serverField = waitFor(hasSetTextAction() and hasText("服务器地址"))
            check(serverField.fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text == TEST_URL)
            click("进入 Atoll")
            compose.waitUntil(40_000) { graph.services.value?.server == TEST_URL && graph.state.value.identityConfirmed &&
                graph.state.value.navigation.connection == dev.atoll.app.ui.screens.ConnectionPhase.OPEN }
        }
        step("channel-list") { waitFor(hasText("OPEN")); waitFor(hasText("android-test", substring = true)) }
        step("open-channel") {
            waitFor(hasText("android-test", substring = true) and hasClickAction()).performClick()
            waitFor(editor)
            compose.waitUntil(40_000) { graph.state.value.route?.channel == config.channel && services().selfIds[config.channel] != null }
            compose.waitUntil(40_000) { graph.state.value.roster.any { it.id == config.codex } }
            check(graph.state.value.roster.filter { it.kind == "agent" }.map { it.id } == listOf(config.codex)) { "Test fixture must have only its codex agent" }
        }
        step("codex-reply") {
            send("Reply only ${marker}_REPLY. Isolated Android UI smoke test; do not change files.")
            answer("${marker}_REPLY")
        }
        step("waiting-queue") {
            // New agent.ask admissions clear a hold (agentLoop.admitBufferedAt), so
            // form a real FIFO queue behind a running turn instead of pre-holding it.
            sendRunning("Run the shell command sleep 60, then reply only ${marker}_SLOW. Do not change files.")
            send("Ignore this extra isolated test instruction; keep your current reply. ${marker}_Q2")
            send("Discard this isolated UI test request. ${marker}_Q3")
            waitFor(waitingRow("${marker}_Q2")); waitFor(waitingRow("${marker}_Q3"))
        }
        step("waiting-cancel") {
            waitingAction("${marker}_Q3", "取消")
            compose.waitUntil(30_000) { nodes(waitingRow("${marker}_Q3")).isEmpty() }
        }
        step("waiting-edit") {
            waitingAction("${marker}_Q2", "编辑")
            // A running shell command can trigger the documented background-task warning.
            val continueEditing = hasText("继续编辑") and hasClickAction()
            val submitEditing = hasText("提交编辑") and hasClickAction() and isEnabled()
            compose.waitUntil(40_000) { nodes(continueEditing).isNotEmpty() || nodes(submitEditing).isNotEmpty() }
            if (nodes(continueEditing).isNotEmpty()) click("继续编辑")
            waitFor(submitEditing)
            waitFor(editor).performTextReplacement("Ignore this extra isolated test instruction; keep your current reply. ${marker}_EDITED")
            click("提交编辑"); waitFor(hasText("编辑已提交")); waitFor(waitingRow("${marker}_EDITED"))
        }
        step("waiting-insert") {
            waitingAction("${marker}_EDITED", "插入")
            compose.waitUntil(40_000) { nodes(waitingRow("${marker}_EDITED")).isEmpty() }
            answer("${marker}_SLOW")
        }
        step("files") {
            val s = services()
            runBlocking { withTimeout(40_000) {
                val repo = FileRepository(config.channel, s.wire, s.httpClient, s.server, context.cacheDir)
                val device = repo.initialize().first { it.id == "local-device" }
                val bytes = "${marker}_PREVIEW\n".toByteArray()
                repo.upload(device, "", filename, emptyList(), { ByteArrayInputStream(bytes) }, bytes.size.toLong(), "text/plain")
            } }
            menu("打开文件"); waitFor(hasContentDescription("关闭文件"))
            // The unique fixture is alphabetically near the top; use the browser's semantic scroll.
            runCatching { compose.onNode(hasScrollAction()).performScrollToNode(hasText(filename)) }
            waitFor(hasText(filename))
        }
        step("preview") {
            click(filename); waitFor(hasText("${marker}_PREVIEW")); click("‹ 返回")
            waitFor(hasContentDescription("关闭文件") and hasClickAction()).performClick()
        }
        step("terminal-ls") {
            menu("打开终端"); waitFor(hasText("已连接"))
            val s = services(); val pty = s.pty ?: error("PTY service unavailable")
            val transcript = StringBuffer()
            runBlocking { withTimeout(40_000) {
                val stream = pty.attach(config.channel, "local-device", onData = { bytes -> transcript.append(bytes.toString(Charsets.UTF_8)) })
                try {
                    stream.status.first { it is PtyStatus.Ready }
                    stream.write("ls; echo ${marker}_TERMINAL\n".toByteArray())
                    while (!transcript.contains(filename) || !Regex("(?m)^${marker}_TERMINAL\\r?$").containsMatchIn(transcript)) delay(100)
                    File(output, "terminal-output.txt").writeText(config.redact(transcript.toString()))
                } finally { stream.detach() }
            } }
            menu("关闭终端")
        }
        step("tasks") { click("任务"); waitFor(SemanticsMatcher("work item count") { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text.startsWith("工作项 ") } }); click("动态") }
        step("members") { click("成员"); waitFor(hasText("刷新名册")); waitFor(hasText("test-codex", substring = true)); click("关闭") }
        step("channel-details") { menu("频道详情"); waitFor(hasText("危险操作")); click("关闭") }
        step("create-child") {
            val parentName = graph.state.value.navigation.channels.first { it.id == config.channel }.name
            val qualifiedName = "$parentName.$child"
            menu("新建子频道"); replace("频道名称", child)
            runCatching { compose.onNode(hasScrollAction()).performScrollToNode(hasText("创建频道")) }
            click("创建频道"); click("进入新频道")
            waitFor(hasText(qualifiedName)); compose.waitUntil(40_000) { graph.state.value.route?.channel != config.channel }
        }
        step("retire-child") {
            val retiredId = graph.state.value.route?.channel ?: error("Created child is not open")
            val parentName = graph.state.value.navigation.channels.first { it.id == config.channel }.name
            check(retiredId != config.channel && graph.state.value.navigation.channels.first { it.id == retiredId }.name == "$parentName.$child")
            val s = services()
            menu("频道详情"); click("危险操作")
            val field = waitFor(hasSetTextAction() and SemanticsMatcher("retire confirmation") {
                it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text.startsWith("输入 ") && text.text.endsWith(" 确认") }
            })
            val label = field.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text).orEmpty().first { it.text.startsWith("输入 ") }.text
            field.performTextReplacement(label.removePrefix("输入 ").removeSuffix(" 确认"))
            click("退役当前频道"); click("确认")
            // Retirement ends membership and removes the local details dialog before
            // its success notice can render. Assert navigation and directory facts.
            compose.waitUntil(40_000) { graph.state.value.route == null }
            waitFor(hasText("我的频道")); waitFor(hasText("频道成员关系已结束"))
            runBlocking { withTimeout(30_000) {
                while (channelRecords(s.obs.spaceChannels(config.channel)).any { it.id == retiredId }) delay(250)
            } }
            File(output, "retire-observer.json").writeText(buildJsonObject { put("channel_id", retiredId); put("absent", true) }.toString())
        }
        step("logout") {
            // Retirement already returned us to the channel list; keep the Activity
            // owned by the Compose rule instead of launching an unnecessary deep link.
            click("退出"); waitFor(hasText("进入 Atoll"))
            compose.waitUntil(20_000) { graph.services.value == null }
            if (config.token.isNotEmpty()) {
                // Assert our new cookie session's logout did not revoke the shared node token.
                okhttp3.OkHttpClient().newCall(Request.Builder().url("$TEST_URL/api/identity/session")
                    .header("Authorization", "Bearer ${config.token}").build()).execute().use { check(it.code == 200) { "Shared test session was revoked" } }
            }
        }
        check(records.size == STEPS.size)
        println("E2E artifacts: ${output.absolutePath}")
    }
}

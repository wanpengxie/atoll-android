package dev.atoll.app.roster

import androidx.compose.foundation.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.text.font.FontWeight
import dev.atoll.app.ui.components.*
import dev.atoll.app.ui.theme.AtollColors
import kotlinx.serialization.json.*


@Composable
fun ActorDetail(viewModel: RosterViewModel, modifier: Modifier = Modifier, onClose: (() -> Unit)? = null) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actor = state.selected ?: return
    var selectedWord by remember(actor.id) { mutableStateOf("") }
    var arguments by remember(actor.id) { mutableStateOf("{}") }
    Column(modifier.fillMaxSize().background(AtollColors.Ground)) {
        Row(Modifier.fillMaxWidth().background(AtollColors.Surface).heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("成员详情", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = viewModel::describe, enabled = state.online && !state.describeBusy) { Text(if (state.describeBusy) "读取中…" else "刷新能力", color = AtollColors.Lagoon) }
            if (onClose != null) TextButton(onClick = onClose) { Text("关闭", color = AtollColors.Muted) }
        }
        Row(Modifier.fillMaxWidth().background(AtollColors.Surface).padding(start = 20.dp, end = 20.dp, bottom = 16.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ActorAvatar(actor.name, actor.id, agent = actor.kind != "human", size = 56.dp, self = actor.id == state.selfId)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(actor.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${actor.kind.ifEmpty { "actor" }} · ${MemberConfig.rosterBodyLabel(actor.body).ifEmpty { "channel member" }}", fontSize = 13.sp, color = AtollColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                StatusPill(actor.presence, if (actor.presence == "在线") Tone.Lagoon else Tone.Neutral)
            }
        }
        HorizontalDivider(color = AtollColors.Line)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Fact("Actor ID", actor.id); Fact("类型", actor.kind.ifEmpty { "未知" }); Fact("来源", MemberConfig.rosterBodyLabel(actor.body).ifEmpty { "—" })
            Fact("绑定", when (actor.bound) { true -> "已绑定"; false -> "未绑定"; null -> "未知" }); Fact("说明", actor.description.ifEmpty { "—" })
            if (state.detailError.isNotEmpty()) Text(state.detailError, color = AtollColors.Warning, fontSize = 12.sp)
            if (state.notice.isNotEmpty()) Text(state.notice, color = AtollColors.Online, fontSize = 12.sp)
            if (actor.kind != "human") MemberConfiguration(state, viewModel)
            if (state.capabilities.isNotEmpty()) Text("能力说明", style = MaterialTheme.typography.titleSmall)
            state.capabilities.forEach { capability ->
                Column(Modifier.fillMaxWidth().background(AtollColors.MutedSurface).padding(8.dp)) {
                    Text(capability.type, fontSize = 13.sp); Text(capability.description.ifEmpty { "无描述" }, color = AtollColors.Muted, fontSize = 12.sp)
                    if (capability.errors.isNotEmpty()) Text("可能的错误：${capability.errors.joinToString("、")}", fontSize = 11.sp)
                }
            }
            Text("调用能力", style = MaterialTheme.typography.titleSmall)
            if (state.capabilities.isEmpty()) Text("该 Actor 没有公布可调用能力。", color = AtollColors.Muted, fontSize = 12.sp)
            else {
                Choice("能力", selectedWord, state.capabilities.map { ReferenceOption(it.type, it.type) }, state.canWrite) { selectedWord = it }
                OutlinedTextField(arguments, { arguments = it }, label = { Text("参数 JSON") }, modifier = Modifier.fillMaxWidth(), minLines = 3, enabled = !state.writeBusy)
                TextButton(onClick = { viewModel.invoke(selectedWord, arguments) }, enabled = state.canWrite && selectedWord.isNotEmpty()) { Text(if (state.writeBusy) "调用中…" else "提交调用") }
                state.invocationResult?.let { JsonValue("调用结果", it) }
            }
        }
    }
}

@Composable
private fun MemberConfiguration(state: RosterUiState, viewModel: RosterViewModel) {
    val info = state.member
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("描述与配置", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        TextButton(onClick = viewModel::readMember, enabled = state.online && !state.memberBusy && state.editor == null && !state.writeBusy) { Text(if (state.memberBusy) "读取中…" else if (info == null) "读取配置" else "刷新") }
    }
    if (info == null) { Text("按需读取：点「读取配置」向本频道 system 发一条 system.member.get。", color = AtollColors.Muted, fontSize = 12.sp); return }
    Fact("Class", info.text("class").ifEmpty { "—" }); Fact("来源", if (info.bool("generated") == true) "运行时生成（不在频道描述里）" else MemberConfig.bodyLabel(info["body"]).ifEmpty { "—" })
    Fact("运行设备", info.text("desired_host").ifEmpty { "local-device" })
    if (info.text("note").isNotEmpty()) Text(info.text("note"), fontSize = 12.sp)
    if (info.obj("build").isNotEmpty()) BuildSummary(info.obj("build"))
    val editor = state.editor
    if (editor != null) MemberConfigEditor(state, viewModel, editor)
    else {
        if (state.editableMember) MemberConfig.missing(info).forEach { missing ->
            var value by remember(state.selected?.id, missing.key) { mutableStateOf("") }
            Text("${missing.key} · ${missing.hint.ifEmpty { "还没填的占位（\$required）" }}", fontSize = 12.sp)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value, { value = it }, label = { Text("填写 ${missing.key}") }, modifier = Modifier.weight(1f), enabled = state.canWrite, singleLine = true)
                TextButton(onClick = { viewModel.fillPlaceholder(missing.key, value) }, enabled = state.canWrite && value.isNotEmpty()) { Text("填入") }
            }
        }
        if (info.bool("generated") != true && info["body"] is JsonObject) {
            JsonValue("描述条目 · 当前 params", info["params"] ?: MemberConfig.empty)
            if (info.array("requires").isNotEmpty()) Fact("requires", info.array("requires").joinToString("、") { (it as? JsonPrimitive)?.content.orEmpty() })
            if (state.editableMember) TextButton(onClick = { viewModel.openEditor("entry") }, enabled = state.canWrite) { Text("编辑条目") }
            JsonValue("这一台的配置", info.obj("own_config")["values"] ?: MemberConfig.empty)
            if (info.obj("own_config").number("revision") > 0) Fact("配置版本", "第 ${info.obj("own_config").number("revision")} 版")
            if (state.editableMember) TextButton(onClick = { viewModel.openEditor("config") }, enabled = state.canWrite) { Text("编辑配置") }
        }
    }
    if (!state.editableMember) Text("只有 Agent 和工具成员有可编辑的描述和配置。", color = AtollColors.Muted, fontSize = 12.sp)
    Text("合成后的值", style = MaterialTheme.typography.titleSmall)
    val sources = MemberConfig.sourceRows(info)
    if (sources.isEmpty()) JsonValue("合成配置", info["effective"] ?: MemberConfig.empty)
    else Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Row { Text("键", Modifier.width(140.dp)); Text("值", Modifier.width(180.dp)); Text("来自", Modifier.width(100.dp)) }
        sources.forEach { source -> Row(Modifier.padding(vertical = 4.dp)) { Text(source.path, Modifier.width(140.dp), fontSize = 12.sp); Text(valueLabel(source.value), Modifier.width(180.dp), fontSize = 12.sp); Text(source.label, Modifier.width(100.dp), fontSize = 12.sp) } }
    }
    if (MemberConfig.globalReferences(info["effective"]).isNotEmpty()) Text("以 \$global. 开头的是对 global/<名称> 的引用，不是值。", fontSize = 11.sp, color = AtollColors.Muted)
}

@Composable
private fun MemberConfigEditor(state: RosterUiState, viewModel: RosterViewModel, editor: MemberEditor) {
    val enabled = state.canWrite
    if (editor.kind == "entry") {
        Choice("从什么造", editor.sourceMode, listOf(ReferenceOption("class", "Class"), ReferenceOption("actor", "Actor 描述")), enabled) { viewModel.updateEditor(editor.copy(sourceMode = it)) }
        if (editor.sourceMode == "actor") Choice("Actor 描述", editor.reference, RosterModel.descriptionOptions(state.choices.descriptions, editor.info.obj("body").text("actor")), enabled) { viewModel.updateEditor(editor.copy(reference = it)) }
        else OutlinedTextField(editor.reference, { viewModel.updateEditor(editor.copy(reference = it)) }, label = { Text("成员 Class") }, enabled = enabled, modifier = Modifier.fillMaxWidth())
    } else {
        val hosts = (listOf(ReferenceOption("", "local-device（默认）")) + state.choices.devices.filter { it.text("id") != "local-device" }.map { ReferenceOption(it.text("id"), it.text("name").ifEmpty { it.text("id") }) } + listOfNotNull(editor.host.takeIf(String::isNotEmpty)?.let { ReferenceOption(it, it) })).distinctBy { it.value }
        Choice("运行设备", editor.host, hosts, enabled) { viewModel.updateEditor(editor.copy(host = it)) }
        if (editor.info.text("class") == "agent") {
            val selected = runCatching { MemberConfig.parse(editor.text).text("agent") }.getOrDefault("")
            Choice("跑哪个 agent", selected, (listOf(selected).filter(String::isNotEmpty) + state.choices.agentClasses).distinct().map { ReferenceOption(it, it) }, enabled) { viewModel.chooseAgentClass(it) }
            Text("那个类自己的配置写在 values 的 config 里。", fontSize = 11.sp, color = AtollColors.Muted)
        }
    }
    val parsed = runCatching { MemberConfig.parse(editor.text) }
    val value = TextFieldValue(editor.text, TextRange(editor.selectionStart.coerceIn(0, editor.text.length), editor.selectionEnd.coerceIn(0, editor.text.length)))
    OutlinedTextField(value, { viewModel.updateEditor(editor.copy(text = it.text, selectionStart = it.selection.start, selectionEnd = it.selection.end)) }, label = { Text(if (editor.kind == "entry") "成员 params JSON" else "成员配置 JSON") }, modifier = Modifier.fillMaxWidth(), minLines = 4, enabled = enabled, isError = parsed.isFailure)
    if (parsed.isFailure) Text(parsed.exceptionOrNull()?.message.orEmpty(), color = AtollColors.Warning, fontSize = 12.sp)
    if (editor.kind == "entry") OutlinedTextField(editor.requires, { viewModel.updateEditor(editor.copy(requires = it)) }, label = { Text("成员 requires") }, enabled = enabled, modifier = Modifier.fillMaxWidth())
    else {
        var global by remember(state.selected?.id) { mutableStateOf("") }
        Choice("选择全局 key", global, state.choices.globalNames.orEmpty().map { ReferenceOption(it, it) }, enabled) { global = it }
        TextButton(onClick = { viewModel.insertReference(global) }, enabled = enabled && global.isNotEmpty()) { Text("插入引用") }
        if (state.choices.globalNames?.isEmpty() == true) Text("可在「空间管理 → 全局 key」添加；配置里写 \"\$global.<名称>\" 引用。", color = AtollColors.Muted, fontSize = 11.sp)
        val missing = state.choices.globalNames?.let { MemberConfig.missingGlobals(parsed.getOrNull(), it) }.orEmpty()
        if (missing.isNotEmpty()) Text("引用的全局 key 不存在：${missing.joinToString("、")}", color = AtollColors.Warning, fontSize = 12.sp)
    }
    if (state.choices.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (state.choices.error.isNotEmpty()) Text(state.choices.error, color = AtollColors.Warning, fontSize = 12.sp)
    val change = state.selected?.let { actor -> runCatching { if (editor.kind == "entry") MemberConfig.entryChange(actor.memberId, editor.info, editor.sourceMode, editor.reference, editor.text, editor.requires) else MemberConfig.configChange(actor.memberId, editor.info, editor.host, editor.text) }.getOrNull() }
    if (change?.changed == true) JsonValue("将提交的变更", JsonObject(change.payload - "member"))
    Row {
        TextButton(onClick = viewModel::closeEditor, enabled = !state.writeBusy) { Text("取消") }
        TextButton(onClick = viewModel::saveEditor, enabled = enabled && change?.changed == true) { Text(if (state.writeBusy) "正在保存…" else if (editor.kind == "entry") "保存条目" else "保存配置") }
    }
}

@Composable
private fun Choice(label: String, current: String, options: List<ReferenceOption>, enabled: Boolean, select: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, enabled = enabled && options.isNotEmpty()) { Text("$label：${options.find { it.value == current }?.label ?: current.ifEmpty { "请选择" }}", maxLines = 2) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) { options.forEach { option -> DropdownMenuItem(text = { Text(option.label) }, onClick = { select(option.value); open = false }) } }
    }
}
@Composable private fun Fact(name: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(name, Modifier.width(72.dp), fontSize = 13.sp, color = AtollColors.Muted)
        Text(value, Modifier.weight(1f), fontSize = 14.sp)
    }
}
@Composable private fun JsonValue(title: String, value: JsonElement) {
    Text(title, fontSize = 12.sp, color = AtollColors.Muted)
    SelectionContainer { Text(Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), value), Modifier.fillMaxWidth().background(AtollColors.MutedSurface).padding(8.dp), fontSize = 12.sp) }
}
@Composable private fun BuildSummary(build: JsonObject) {
    val result = when (build.text("result")) { "ok" -> "成功"; "failed" -> "失败"; "superseded" -> "被新值取代"; else -> "构建中" }
    Fact("最近一次构建", "$result · 第 ${build.number("attempt").coerceAtLeast(1)} 次尝试 · 描述第 ${build.obj("description").number("channel_revision")} 版 · 配置第 ${build.obj("config").number("revision")} 版")
    if (build.text("state").isNotEmpty()) Fact("状态", when (build.text("state")) { "ready" -> "已就绪"; "serving" -> "服务中"; "stopped" -> "已停止：改描述或配置、或重启后才会再构建"; else -> build.text("state") })
    if (build.text("reason").isNotEmpty()) Text(build.text("reason"), color = AtollColors.Warning, fontSize = 12.sp)
}
private fun valueLabel(value: JsonElement?) = when { value == null -> "—"; value is JsonPrimitive && value.isString -> value.content; else -> value.toString() }

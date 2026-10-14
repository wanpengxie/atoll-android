package dev.atoll.app.roster

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.atoll.app.ui.components.*
import dev.atoll.app.ui.theme.AtollColors
import kotlinx.serialization.json.*

@Composable
internal fun MemberConfigScreen(viewModel: RosterViewModel, modifier: Modifier, onClose: (() -> Unit)?) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    MemberConfigContent(state, modifier, onClose ?: {}, viewModel::readMember, viewModel::describe,
        viewModel::openEditor, viewModel::updateEditor, viewModel::chooseAgentClass, viewModel::insertReference,
        viewModel::closeEditor, viewModel::saveEditor, viewModel::restartSelected, viewModel::fillPlaceholder, viewModel::invoke)
}

/** Passive design surface; all writes remain in the existing per-channel owner. */
@Composable
internal fun MemberConfigContent(state: RosterUiState, modifier: Modifier = Modifier, onClose: () -> Unit = {},
    onRead: () -> Unit = {}, onDescribe: () -> Unit = {}, onEdit: (String) -> Unit = {},
    onUpdate: (MemberEditor) -> Unit = {}, onAgent: (String) -> Unit = {}, onReference: (String) -> Unit = {},
    onCancel: () -> Unit = {}, onSave: () -> Unit = {}, onRestart: () -> Unit = {},
    onFill: (String, String) -> Unit = { _, _ -> }, onInvoke: (String, String) -> Unit = { _, _ -> }) {
    val actor = state.selected ?: return
    val info = state.member
    var tab by remember(actor.id) { mutableIntStateOf(0) }
    var confirmRestart by remember(actor.id) { mutableStateOf(false) }
    var showDiff by remember(actor.id) { mutableStateOf(false) }
    val editor = state.editor
    val change = remember(state.selected?.id, editor) { memberEditorChange(state) }
    val parsed = remember(editor?.text) { editor?.let { runCatching { MemberConfig.parse(it.text) }.getOrNull() } }
    val missing = state.choices.globalNames?.let { MemberConfig.missingGlobals(parsed ?: info?.get("effective"), it) }.orEmpty()
    Column(modifier.fillMaxSize().background(AtollColors.Ground).navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).background(AtollColors.Surface).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClose) { Icon(Icons.Outlined.ArrowBack, "返回成员", Modifier.size(22.dp)) }
            Text("成员详情", Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            IconButton(onRead, enabled = state.online && !state.memberBusy && editor == null && !state.writeBusy) {
                Icon(Icons.Outlined.Refresh, "刷新配置", Modifier.size(20.dp))
            }
        }
        Row(Modifier.fillMaxWidth().background(AtollColors.Surface).padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ActorAvatar(actor.name, actor.id, agent = true, size = 56.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(actor.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${actor.kind.ifEmpty { "Actor" }} · class ${info?.text("class")?.ifEmpty { actor.body } ?: actor.body} · ${info?.text("desired_host")?.ifEmpty { "local-device" } ?: "—"}",
                    fontSize = 13.sp, color = AtollColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val build = info?.obj("build")
                if (build != null && build.isNotEmpty()) Text("${when (build.text("result")) { "ok" -> "构建成功"; "failed" -> "构建失败"; "superseded" -> "已被新值取代"; else -> "构建中" }} · 描述 v${build.obj("description").number("channel_revision")} · 配置 v${build.obj("config").number("revision")}",
                    fontSize = 12.sp, color = if (build.text("result") == "failed") AtollColors.Danger else AtollColors.Lagoon)
                else Text(actor.presence, fontSize = 12.sp, color = AtollColors.Muted)
            }
        }
        Row(Modifier.fillMaxWidth().background(AtollColors.Surface).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            listOf("配置", "描述", "能力", "JSON").forEachIndexed { index, label ->
                Column(Modifier.width(IntrinsicSize.Max).height(44.dp).selectable(tab == index, role = androidx.compose.ui.semantics.Role.Tab) { tab = index }, verticalArrangement = Arrangement.SpaceBetween) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Text(label, fontSize = 14.sp, fontWeight = if (tab == index) FontWeight.SemiBold else FontWeight.Normal, color = if (tab == index) AtollColors.Ink else AtollColors.Muted) }
                    Box(Modifier.fillMaxWidth().height(2.dp).background(if (tab == index) AtollColors.Ink else AtollColors.Surface))
                }
            }
        }
        HorizontalDivider(color = AtollColors.Line)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (state.detailError.isNotEmpty()) NoticeBanner(state.detailError, Tone.Danger)
            if (state.notice.isNotEmpty()) NoticeBanner(state.notice, Tone.Lagoon)
            if (state.choices.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.choices.error.isNotEmpty()) NoticeBanner(state.choices.error, Tone.Waiting)
            if (missing.isNotEmpty()) NoticeBanner("引用的全局 key 不存在：${missing.joinToString("、")}。请在空间管理添加。", Tone.Waiting)
            when (tab) {
                0 -> {
                    if (info == null) AtollButton(if (state.memberBusy) "读取中…" else "读取配置", onRead, enabled = state.online && !state.memberBusy)
                    else {
                        GroupCard {
                            val configEditor = editor?.takeIf { it.kind == "config" }
                            val host = configEditor?.host ?: info.text("desired_host")
                            val hosts = (listOf(ReferenceOption("", "local-device（默认）")) + state.choices.devices.map { ReferenceOption(it.text("id"), it.text("name").ifEmpty { it.text("id") }) } + listOfNotNull(host.takeIf(String::isNotEmpty)?.let { ReferenceOption(it, it) })).distinctBy { it.value }
                            ConfigChoiceRow("运行设备", host, hosts, configEditor != null && state.canWrite) { value -> if (configEditor != null) onUpdate(configEditor.copy(host = value)) }
                            if (configEditor != null && info.text("class") == "agent") ConfigChoiceRow("Agent 类", parsed?.text("agent").orEmpty(), (listOf(parsed?.text("agent").orEmpty()).filter(String::isNotEmpty) + state.choices.agentClasses).distinct().map { ReferenceOption(it, it) }, state.canWrite, onAgent)
                            DefaultSelectionRows(info, editor, state.canWrite, onUpdate)
                            val fields = remember(info, editor?.text) { memberConfigFields(info, editor) }
                            fields.forEach { field -> ConfigField(field, editor?.kind == "config" && state.canWrite,
                                missing.any { field.value.content == "\$global.${it.removePrefix("global/")}" }) { value ->
                                if (editor != null) onUpdate(editedMemberValue(editor, field.path, value))
                            } }
                            val effective = mergeConfigValues(info.obj("effective"), parsed ?: MemberConfig.empty)
                            val hasSelections = effective.array("selections").isNotEmpty() || effective.obj("config").array("selections").isNotEmpty()
                            if (fields.isEmpty() && !hasSelections) GroupRow("配置", subtitle = "没有简单字段；可在 JSON 标签查看和编辑完整配置。", divider = false)
                        }
                        if (state.editableMember && info.bool("generated") != true && info["body"] is JsonObject && editor == null) AtollButton("编辑配置", { onEdit("config") }, kind = ButtonKind.Secondary, enabled = state.canWrite && !state.memberBusy)
                        if (editor?.kind == "entry") Text("正在编辑描述条目；请在描述或 JSON 标签完成编辑。", fontSize = 13.sp, color = AtollColors.Muted)
                        MemberConfig.missing(info).forEach { placeholder ->
                            var value by remember(actor.id, placeholder.key) { mutableStateOf("") }
                            OutlinedTextField(value, { value = it }, label = { Text("填写 ${placeholder.key}") }, supportingText = { Text(placeholder.hint) }, modifier = Modifier.fillMaxWidth(), enabled = state.canWrite, singleLine = true)
                            AtollButton("填入", { onFill(placeholder.key, value) }, enabled = state.canWrite && value.isNotEmpty(), kind = ButtonKind.Secondary)
                        }
                    }
                }
                1 -> {
                    GroupCard {
                        GroupRow("Actor ID", value = actor.id)
                        GroupRow("说明", subtitle = actor.description.ifEmpty { "暂无说明" })
                        GroupRow("来源", subtitle = if (info?.bool("generated") == true) "运行时生成" else MemberConfig.bodyLabel(info?.get("body")), divider = false)
                    }
                    if (info != null) {
                        if (editor?.kind == "entry") MemberConfigEditor(state, editor, onUpdate, onAgent, onReference)
                        else {
                            JsonValue("成员 params", info["params"] ?: MemberConfig.empty)
                            if (info.array("requires").isNotEmpty()) Fact("requires", info.array("requires").joinToString("、") { (it as? JsonPrimitive)?.content.orEmpty() })
                            if (state.editableMember && info.bool("generated") != true && editor == null) AtollButton("编辑条目", { onEdit("entry") }, kind = ButtonKind.Secondary, enabled = state.canWrite)
                        }
                        if (info.obj("build").isNotEmpty()) BuildSummary(info.obj("build"))
                        if (info.text("note").isNotEmpty()) Text(info.text("note"), fontSize = 13.sp)
                    }
                }
                2 -> {
                    AtollButton(if (state.describeBusy) "读取中…" else "刷新能力", onDescribe, kind = ButtonKind.Secondary, enabled = state.online && !state.describeBusy)
                    GroupCard { state.capabilities.forEachIndexed { index, capability -> GroupRow(capability.type, subtitle = capability.description.ifEmpty { "无描述" }, divider = index != state.capabilities.lastIndex) } }
                    if (state.capabilities.isEmpty()) Text("该 Actor 没有公布可调用能力。", fontSize = 13.sp, color = AtollColors.Muted)
                    else {
                        var word by remember(actor.id) { mutableStateOf("") }; var args by remember(actor.id) { mutableStateOf("{}") }
                        Choice("调用能力", word, state.capabilities.map { ReferenceOption(it.type, it.type) }, state.canWrite) { word = it }
                        OutlinedTextField(args, { args = it }, label = { Text("参数 JSON") }, modifier = Modifier.fillMaxWidth(), minLines = 3, enabled = !state.writeBusy)
                        state.capabilities.find { it.type == word }?.let { capability ->
                            if (capability.errors.isNotEmpty()) Text("可能的错误：${capability.errors.joinToString("、")}", fontSize = 12.sp)
                            capability.schema?.let { JsonValue("输入 schema", it) }
                        }
                        AtollButton("提交调用", { onInvoke(word, args) }, enabled = state.canWrite && word.isNotEmpty())
                        state.invocationResult?.let { JsonValue("调用结果", it) }
                    }
                }
                3 -> if (editor != null) MemberConfigEditor(state, editor, onUpdate, onAgent, onReference)
                    else if (info != null) {
                        JsonValue("这一台的配置", info.obj("own_config")["values"] ?: MemberConfig.empty)
                        JsonValue("合成配置", info["effective"] ?: MemberConfig.empty)
                        JsonValue("成员信息", info)
                        if (state.editableMember && info.bool("generated") != true) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AtollButton("编辑配置", { onEdit("config") }, kind = ButtonKind.Secondary, enabled = state.canWrite)
                            AtollButton("编辑条目", { onEdit("entry") }, kind = ButtonKind.Secondary, enabled = state.canWrite)
                        }
                    } else AtollButton("读取配置", onRead, enabled = state.online && !state.memberBusy)
            }
            if (change?.changed == true) GroupCard {
                GroupRow("将提交的变更 · ${change.payload.keys.count { it != "member" }} 处", onClick = { showDiff = !showDiff }, divider = showDiff)
                if (showDiff) Box(Modifier.padding(14.dp)) { Column { JsonValue("RFC 7396 patch", JsonObject(change.payload - "member")) } }
            }
            if (!state.editableMember && info != null) Text("只有 Agent 和工具成员有可编辑的描述和配置。", fontSize = 13.sp, color = AtollColors.Muted)
        }
        HorizontalDivider(color = AtollColors.Line)
        Row(Modifier.fillMaxWidth().background(AtollColors.Surface).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AtollButton("重启", { confirmRestart = true }, kind = ButtonKind.Secondary, enabled = state.canWrite && state.editableMember, height = 48.dp)
            if (editor != null) AtollButton("取消", onCancel, kind = ButtonKind.Text, enabled = !state.writeBusy, height = 48.dp)
            AtollButton(if (state.writeBusy) "正在保存…" else if (editor?.kind == "entry") "保存条目" else "保存配置", onSave, Modifier.weight(1f),
                enabled = state.canWrite && change?.changed == true, height = 48.dp)
        }
    }
    if (confirmRestart) AlertDialog(onDismissRequest = { confirmRestart = false }, title = { Text("重启 ${actor.name}？") },
        text = { Text("成员进程会重新启动，当前未完成的请求可能失败。") },
        confirmButton = { TextButton(onClick = { confirmRestart = false; onRestart() }, enabled = state.canWrite && state.editableMember) { Text("确认重启") } },
        dismissButton = { TextButton(onClick = { confirmRestart = false }) { Text("取消") } })
}

@Composable
private fun ConfigField(field: MemberConfigField, enabled: Boolean, missing: Boolean, onValue: (JsonPrimitive) -> Unit) {
    val value = field.value
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(field.path, Modifier.weight(1f), fontSize = 15.sp)
            if (value.isString && value.content.startsWith("\$global.")) StatusPill("引用全局 key", Tone.Lagoon)
        }
        when {
            !enabled -> Text(value.content, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
            value.booleanOrNull != null && !value.isString -> Switch(value.boolean, { onValue(JsonPrimitive(it)) }, modifier = Modifier.semantics { contentDescription = field.path })
            else -> {
                // Numeric drafts retain their text until valid; never convert a number into a string.
                var draft by remember(field.path, value) { mutableStateOf(value.content) }
                val number = if (!value.isString) draft.toBigDecimalOrNull() else null
                BasicTextField(draft, { draft = it; if (value.isString) onValue(JsonPrimitive(it)) else it.toBigDecimalOrNull()?.let { n -> onValue(JsonPrimitive(n)) } },
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = AtollColors.Ink), singleLine = true,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).border(if (missing) 1.5.dp else 1.dp, if (missing) AtollColors.Danger else AtollColors.ControlLine, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 12.dp).semantics { contentDescription = field.path })
                if (!value.isString && number == null) Text("请输入数字；尚未写入变更。", fontSize = 12.sp, color = AtollColors.Danger)
            }
        }
        if (missing) Text("这个 key 现在还没有值", fontSize = 12.sp, color = AtollColors.Danger)
        if (field.source.isNotEmpty()) Text("来自 ${field.source}", fontSize = 11.sp, color = AtollColors.Muted)
    }
    HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = AtollColors.LineSoft)
}

@Composable
private fun DefaultSelectionRows(info: JsonObject, editor: MemberEditor?, enabled: Boolean, onUpdate: (MemberEditor) -> Unit) {
    val edited = editor?.takeIf { it.kind == "config" }?.let { runCatching { MemberConfig.parse(it.text) }.getOrNull() }
    val values = mergeConfigValues(info.obj("effective"), edited ?: MemberConfig.empty)
    val nested = values.obj("config").containsKey("selections")
    val options = if (nested) values.obj("config") else values
    val selections = options.array("selections").filterIsInstance<JsonObject>()
    if (selections.isEmpty()) return
    val index = options.number("default").toInt()
    val current = selections.getOrNull(index)
    val canEdit = editor?.kind == "config" && enabled
    val path = if (nested) "config.default" else "default"
    val models = selections.map { ReferenceOption(it.text("model"), it.text("model_label").ifEmpty { it.text("model") }) }.distinctBy { it.value }
    ConfigChoiceRow("模型", current?.text("model").orEmpty(), models, canEdit) { model ->
        val selected = selections.indexOfFirst { it.text("model") == model && it.text("effort") == current?.text("effort") }
            .takeIf { it >= 0 } ?: selections.indexOfFirst { it.text("model") == model }
        if (editor != null && selected >= 0) onUpdate(editedMemberValue(editor, path, JsonPrimitive(selected)))
    }
    val efforts = selections.filter { it.text("model") == current?.text("model") }.map { ReferenceOption(it.text("effort"), it.text("effort_label").ifEmpty { it.text("effort") }) }.distinctBy { it.value }
    ConfigChoiceRow("默认推理强度", current?.text("effort").orEmpty(), efforts, canEdit) { effort ->
        val selected = selections.indexOfFirst { it.text("model") == current?.text("model") && it.text("effort") == effort }
        if (editor != null && selected >= 0) onUpdate(editedMemberValue(editor, path, JsonPrimitive(selected)))
    }
}

@Composable
private fun ConfigChoiceRow(title: String, current: String, options: List<ReferenceOption>, enabled: Boolean, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        GroupRow(title, value = options.firstOrNull { it.value == current }?.label ?: current.ifEmpty { "—" },
            valueColor = AtollColors.Body, onClick = if (enabled && options.isNotEmpty()) ({ open = true }) else null)
        DropdownMenu(open, onDismissRequest = { open = false }) {
            options.forEach { option -> DropdownMenuItem(text = { Text(option.label) }, onClick = { open = false; onSelect(option.value) }) }
        }
    }
}

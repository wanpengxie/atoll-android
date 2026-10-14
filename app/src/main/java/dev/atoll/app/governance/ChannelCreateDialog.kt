package dev.atoll.app.governance

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.atoll.app.ui.components.*
import dev.atoll.app.ui.theme.AtollColors

/** Creation lives in the world VM after this dialog closes. Never retry an uncertain request. */
@Composable
fun ChannelCreateDialog(viewModel: GovernanceViewModel, onEnterChannel: ((String) -> Unit)? = null, onClose: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val creations by viewModel.creations.collectAsState()
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ChannelCreateContent(state, viewModel.principalId, creations, viewModel::create,
            viewModel::readEntries, viewModel::refreshCreation, onEnterChannel, onClose)
    }
}

/** The three design steps summarize all four convergence facts, without declaring readiness early. */
internal fun channelCreationSteps(creation: ChannelCreation): List<Pair<String, Boolean>> = listOf(
    "账本确认" to (creation.accepted && creation.ledger),
    "频道可观察" to creation.observable,
    "服务就绪" to (creation.membership && creation.serving))

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChannelCreateContent(state: GovernanceState, principalId: String, creations: List<ChannelCreation>,
    onCreate: (ChannelCreateForm) -> String?, onReadEntries: () -> Unit, onRefresh: (String) -> Unit,
    onEnterChannel: ((String) -> Unit)? = null, onClose: () -> Unit) {
    var requestId by remember(state.channel) { mutableStateOf(creations.lastOrNull { it.parentId == state.channel && !it.ready && !it.failed }?.requestId.orEmpty()) }
    val operation = creations.firstOrNull { it.requestId == requestId }
    var name by remember(state.channel) { mutableStateOf(operation?.name.orEmpty()) }
    var purpose by remember(state.channel) { mutableStateOf("") }
    var start by remember(state.channel) { mutableStateOf(ChannelStart.Blank) }
    var copy by remember(state.channel) { mutableStateOf("") }
    var humans by remember(state.channel) { mutableStateOf<Set<String>>(emptySet()) }
    var picked by remember(state.channel) { mutableStateOf<Set<String>>(emptySet()) }
    var error by remember(state.channel) { mutableStateOf("") }
    val validation = validateGovernanceChannelName(name)
    val locked = !state.writable || operation != null && !operation.failed
    val copyable = governanceCopyableChannels(state.directory.channels, state.memberChannels)
    val principals = governancePrincipals(state.directory.principals).filter { it.text("id") != principalId }
    val entries = state.entries.orEmpty().filter { it.text("id").isNotEmpty() }
    Surface(Modifier.fillMaxSize(), color = AtollColors.Surface) {
        Column(Modifier.safeDrawingPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "关闭", Modifier.size(20.dp)) }
                Text("新建频道", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("channel-create-form"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                item {
                    FormSection("起点") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(ChannelStart.Blank to "空白", ChannelStart.Copy to "复制频道", ChannelStart.Pick to "挑选成员").forEach { (choice, label) ->
                                val active = start == choice
                                Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                                    .border(if (active) 1.5.dp else 1.dp, if (active) AtollColors.Ink else AtollColors.ControlLine, RoundedCornerShape(14.dp))
                                    .selectable(active, enabled = !locked, role = Role.RadioButton) { start = choice }
                                    .padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(when (choice) { ChannelStart.Blank -> Icons.Outlined.InsertDriveFile; ChannelStart.Copy -> Icons.Outlined.ContentCopy; ChannelStart.Pick -> Icons.Outlined.People },
                                        null, Modifier.size(20.dp), tint = if (active) AtollColors.Ink else AtollColors.Muted)
                                    Text(label, fontSize = 14.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium)
                                }
                            }
                        }
                    }
                }
                item { FormSection("父频道") {
                    Row(Modifier.fillMaxWidth().height(48.dp).border(1.dp, AtollColors.ControlLine, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("# ", color = AtollColors.Faint, fontSize = 15.sp); Text(channelName(state), fontSize = 15.sp)
                    }
                } }
                item { FormSection("频道名称") {
                    CreationTextField(name, { name = it }, "频道名称", !locked, prefix = "# ", invalid = name.isNotEmpty() && validation.isNotEmpty())
                    Text(if (name.isNotEmpty() && validation.isNotEmpty()) validation else "1–63 位小写字母、数字或连字符，首尾为字母或数字", fontSize = 12.sp,
                        color = if (name.isNotEmpty() && validation.isNotEmpty()) AtollColors.Danger else AtollColors.Muted)
                } }
                if (start != ChannelStart.Copy) item { FormSection("说明（可选）") {
                    CreationTextField(purpose, { purpose = it }, "这个频道用来做什么", !locked, multiline = true)
                } }
                if (start == ChannelStart.Copy) item { FormSection("复制哪个频道") {
                    GovernanceChoice(copy, copyable.map { it.directoryId() to it.declared().text("qualified_name").ifEmpty { it.displayName() } }, "选择频道", !locked) { copy = it }
                    Text(if (copyable.isEmpty()) "你不是任何可复制频道的成员。" else "复制描述与成员条目；已有成员配置不会带过来。", fontSize = 12.sp, color = AtollColors.Muted)
                } }
                if (start == ChannelStart.Pick) {
                    item { TextButton(onClick = onReadEntries, enabled = !locked && !state.busy) { Text(if (state.entries == null) "读取本频道成员条目" else "重新读取成员条目") } }
                    items(entries, key = { it.text("id") }) { entry ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).toggleable(entry.text("id") in picked, enabled = !locked, role = Role.Checkbox) {
                            picked = if (it) picked + entry.text("id") else picked - entry.text("id")
                        }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(entry.text("id") in picked, null, enabled = !locked)
                            ActorAvatar(entry.text("name"), entry.text("id"), agent = true, size = 28.dp)
                            Text(entry.text("name").ifEmpty { entry.text("id") }, Modifier.padding(start = 8.dp), fontSize = 14.sp)
                        }
                    }
                    if (state.entries?.isEmpty() == true) item { Text("当前描述没有可选成员条目。", color = AtollColors.Muted) }
                }
                item { FormSection("带进来的成员") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.heightIn(min = 36.dp).background(AtollColors.Sunken, RoundedCornerShape(18.dp)).padding(start = 4.dp, end = 12.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ActorAvatar("我", principalId, agent = false, self = true, size = 28.dp)
                            Text("你 · 自动加入", fontSize = 14.sp)
                        }
                        principals.forEach { person ->
                            val id = person.text("id"); val selected = id in humans
                            Row(Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(18.dp))
                                .toggleable(selected, enabled = !locked, role = Role.Checkbox) { humans = if (it) humans + id else humans - id }
                                .background(if (selected) AtollColors.LagoonSurface else AtollColors.Sunken)
                                .padding(start = 4.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                ActorAvatar(person.displayName(), id, agent = false, size = 28.dp)
                                Text(person.displayName(), fontSize = 14.sp)
                                Icon(if (selected) Icons.Outlined.Check else Icons.Outlined.Add, null, Modifier.size(16.dp))
                            }
                        }
                    }
                } }
                if (error.isNotEmpty() || state.error.isNotEmpty()) item { Text(error.ifEmpty { state.error }, color = AtollColors.Danger, fontSize = 13.sp) }
                operation?.let { creation -> item {
                    Column(Modifier.fillMaxWidth().background(AtollColors.Sunken, RoundedCornerShape(14.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(when { creation.ready -> "${creation.name} · 已就绪"; creation.failed -> "${creation.name} · 创建失败"; creation.uncertain -> "${creation.name} · 待确认"; else -> "${creation.name} · 正在创建" }, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        channelCreationSteps(creation).forEach { (label, done) ->
                            Text("${if (done) "✓" else "○"} $label", fontSize = 13.sp, color = if (done) AtollColors.Lagoon else AtollColors.Muted)
                        }
                        if (creation.serving && !creation.membership) Text("服务已启动，等待成员关系确认。", fontSize = 12.sp, color = AtollColors.Muted)
                        if (creation.error.isNotEmpty()) Text(creation.error, color = AtollColors.Danger, fontSize = 13.sp)
                        if (!creation.failed && !creation.ready) TextButton(onClick = { onRefresh(creation.requestId) }) { Text("刷新创建事实") }
                        if (creation.ready && onEnterChannel == null) Text("频道已就绪，进入频道的导航端口还未连接。", fontSize = 12.sp)
                    }
                } }
            }
            HorizontalDivider(color = AtollColors.Line)
            Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp)) {
                when {
                    operation?.ready == true -> AtollButton("进入新频道", { onEnterChannel?.invoke(operation.targetId) }, Modifier.fillMaxWidth(), enabled = onEnterChannel != null, height = 48.dp)
                    operation?.failed == true -> AtollButton("重新创建", { requestId = ""; error = "" }, Modifier.fillMaxWidth(), height = 48.dp)
                    operation != null -> AtollButton("等待频道就绪…", {}, Modifier.fillMaxWidth(), enabled = false, height = 48.dp)
                    else -> AtollButton("创建频道", {
                        error = when {
                            validation.isNotEmpty() -> validation
                            start == ChannelStart.Copy && copyable.none { it.directoryId() == copy } -> "请选择要复制的频道"
                            start == ChannelStart.Pick && entries.none { it.text("id") in picked } -> "请至少挑一个成员条目"
                            else -> ""
                        }
                        if (error.isEmpty()) requestId = onCreate(ChannelCreateForm(name, state.channel, principalId,
                            humans.filter { id -> principals.any { it.text("id") == id } }.toSet(), start, copy, purpose,
                            entries.filter { it.text("id") in picked })).orEmpty()
                    }, Modifier.fillMaxWidth(), enabled = !locked && !state.busy && validation.isEmpty(), height = 48.dp)
                }
            }
        }
    }
}

@Composable
private fun FormSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AtollColors.Muted)
        content()
    }
}

@Composable
private fun CreationTextField(value: String, onValue: (String) -> Unit, label: String, enabled: Boolean,
    prefix: String = "", multiline: Boolean = false, invalid: Boolean = false) {
    var focused by remember { mutableStateOf(false) }
    BasicTextField(value, onValue, enabled = enabled, singleLine = !multiline, minLines = if (multiline) 2 else 1, maxLines = if (multiline) 4 else 1,
        textStyle = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, color = if (enabled) AtollColors.Ink else AtollColors.Muted),
        modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.semantics { contentDescription = label },
        decorationBox = { input ->
            Row(Modifier.fillMaxWidth().heightIn(min = if (multiline) 72.dp else 48.dp)
                .border(if (invalid || focused) 1.5.dp else 1.dp, if (invalid) AtollColors.Danger else if (focused) AtollColors.Lagoon else AtollColors.ControlLine, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = if (multiline) 12.dp else 0.dp), verticalAlignment = if (multiline) Alignment.Top else Alignment.CenterVertically) {
                if (prefix.isNotEmpty()) Text(prefix, fontSize = 15.sp, color = AtollColors.Faint)
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(label, fontSize = 15.sp, color = AtollColors.Faint)
                    input()
                }
            }
        })
}

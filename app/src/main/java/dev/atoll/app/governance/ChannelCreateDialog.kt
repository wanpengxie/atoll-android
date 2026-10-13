package dev.atoll.app.governance

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Creation lives in the world VM after this dialog closes. Never retry an uncertain request. */
@Composable
fun ChannelCreateDialog(viewModel: GovernanceViewModel,onEnterChannel: ((String)->Unit)?=null,onClose: ()->Unit) {
    val state by viewModel.state.collectAsState();val creations by viewModel.creations.collectAsState()
    var requestId by remember(state.channel) { mutableStateOf(creations.lastOrNull { it.parentId==state.channel && !it.ready && !it.failed }?.requestId.orEmpty()) }
    val operation=creations.firstOrNull { it.requestId==requestId }
    var name by remember(state.channel) { mutableStateOf(operation?.name.orEmpty()) };var purpose by remember(state.channel) { mutableStateOf("") }
    var start by remember(state.channel) { mutableStateOf(ChannelStart.Blank) };var copy by remember(state.channel) { mutableStateOf("") }
    var humans by remember(state.channel) { mutableStateOf<Set<String>>(emptySet()) };var picked by remember(state.channel) { mutableStateOf<Set<String>>(emptySet()) }
    var error by remember(state.channel) { mutableStateOf("") }
    val validation=validateGovernanceChannelName(name)
    val locked=!state.writable || operation!=null && !operation.failed
    val copyable=governanceCopyableChannels(state.directory.channels,state.memberChannels)
    val principals=governancePrincipals(state.directory.principals).filter { it.text("id")!=viewModel.principalId }
    val entries=state.entries.orEmpty().filter { it.text("id").isNotEmpty() }
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxWidth(.96f).heightIn(max=720.dp),shape=MaterialTheme.shapes.large) { Column(Modifier.imePadding()) {
            GovernanceHeader("新建频道",onClose)
            LazyColumn(Modifier.fillMaxWidth().weight(1f,false),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                item { Text("在 ${channelName(state)} 下创建子频道。") }
                item { OutlinedTextField(name,{ name=it },label={ Text("频道名称") },modifier=Modifier.fillMaxWidth(),singleLine=true,enabled=!locked,isError=name.isNotEmpty() && validation.isNotEmpty(),supportingText={ if (name.isNotEmpty() && validation.isNotEmpty()) Text(validation) }) }
                item { Text("起点",style=MaterialTheme.typography.titleSmall) }
                items(listOf(ChannelStart.Blank to "空白",ChannelStart.Copy to "复制一个频道",ChannelStart.Pick to "从本频道挑成员")) { (choice,label) -> Row(Modifier.fillMaxWidth().selectable(start==choice,enabled=!locked,role=Role.RadioButton) { start=choice }) {
                    RadioButton(start==choice,onClick=null,enabled=!locked);Text(label,Modifier.padding(top=12.dp))
                } }
                if (start!=ChannelStart.Copy) item { OutlinedTextField(purpose,{ purpose=it },label={ Text("说明（可选）") },modifier=Modifier.fillMaxWidth(),enabled=!locked,minLines=2,maxLines=4) }
                if (start==ChannelStart.Copy) item {
                    GovernanceChoice(copy,copyable.map { it.directoryId() to it.declared().text("qualified_name").ifEmpty { it.displayName() } },"复制哪个频道",!locked) { copy=it }
                    Text(if (copyable.isEmpty()) "你不是任何可复制频道的成员。" else "复制描述与成员条目；已有成员配置不会带过来。",style=MaterialTheme.typography.bodySmall)
                }
                if (start==ChannelStart.Pick) {
                    item { TextButton(onClick=viewModel::readEntries,enabled=!locked && !state.busy) { Text(if (state.entries==null) "读取本频道成员条目" else "重新读取成员条目") } }
                    items(entries,key={ it.text("id") }) { entry -> Row(Modifier.fillMaxWidth().toggleable(entry.text("id") in picked,enabled=!locked,role=Role.Checkbox) { selected -> picked=if (selected) picked+entry.text("id") else picked-entry.text("id") }) { Checkbox(entry.text("id") in picked,null,enabled=!locked);Column(Modifier.weight(1f).padding(top=8.dp)) { Text(entry.text("name").ifEmpty { entry.text("id") });Text(entry.obj("body").toString(),style=MaterialTheme.typography.bodySmall) } } }
                    if (state.entries?.isEmpty()==true) item { Text("当前描述没有可选成员条目。") }
                }
                item { Text("带进来的人",style=MaterialTheme.typography.titleSmall);Text("✓ 我 · ${viewModel.principalId}（自动加入）",style=MaterialTheme.typography.bodySmall) }
                items(principals,key={ it.text("id") }) { person -> Row(Modifier.fillMaxWidth().toggleable(person.text("id") in humans,enabled=!locked,role=Role.Checkbox) { selected -> humans=if (selected) humans+person.text("id") else humans-person.text("id") }) { Checkbox(person.text("id") in humans,null,enabled=!locked);Text(person.displayName(),Modifier.padding(top=12.dp)) } }
                if (error.isNotEmpty() || state.error.isNotEmpty()) item { Text(error.ifEmpty { state.error },color=MaterialTheme.colorScheme.error) }
                operation?.let { creation -> item {
                    GovernanceCard(when { creation.ready -> "${creation.name} · 已就绪";creation.failed -> "${creation.name} · 创建失败";creation.uncertain -> "${creation.name} · 待确认";else -> "${creation.name} · 正在收敛" }) {
                        listOf(creation.ledger to "账本确认",creation.observable to "频道可观察",creation.membership to "成员关系",creation.serving to "服务就绪").forEach { (done,label) -> Text("${if (done) "✓" else "·"} $label") }
                        if (creation.error.isNotEmpty()) Text(creation.error,color=MaterialTheme.colorScheme.error)
                        if (!creation.failed && !creation.ready) TextButton(onClick={ viewModel.refreshCreation(creation.requestId) }) { Text("刷新创建事实") }
                        if (creation.ready && onEnterChannel==null) Text("频道已就绪，进入频道的导航端口还未连接。")
                    }
                } }
                item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick=onClose) { Text("关闭") }
                    when {
                        operation?.ready==true -> Button(onClick={ onEnterChannel?.invoke(operation.targetId) },enabled=onEnterChannel!=null) { Text("进入新频道") }
                        operation?.failed==true -> Button(onClick={ requestId="";error="" }) { Text("重新创建") }
                        operation!=null -> Button(onClick={},enabled=false) { Text("等待频道就绪…") }
                        else -> Button(onClick={
                            error=when { validation.isNotEmpty() -> validation;start==ChannelStart.Copy && copyable.none { it.directoryId()==copy } -> "请选择要复制的频道";start==ChannelStart.Pick && entries.none { it.text("id") in picked } -> "请至少挑一个成员条目";else -> "" }
                            if (error.isEmpty()) requestId=viewModel.create(ChannelCreateForm(name,state.channel,viewModel.principalId,humans.filter { id -> principals.any { it.text("id")==id } }.toSet(),start,copy,purpose,entries.filter { it.text("id") in picked })).orEmpty()
                        },enabled=!locked && !state.busy && validation.isEmpty()) { Text("创建频道") }
                    }
                } }
            }
        } }
    }
}

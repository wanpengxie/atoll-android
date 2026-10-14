package dev.atoll.app.governance

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.atoll.app.ui.components.NoticeBanner
import dev.atoll.app.ui.components.Spinner
import dev.atoll.app.ui.components.Tone
import dev.atoll.app.ui.theme.AtollColors
import dev.atoll.app.ui.theme.AtollShapes
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*

/** Host owns the VM/access facts; opening a panel only refreshes OBS, never writes. */
@Composable
fun GovernanceScreen(viewModel: GovernanceViewModel,modifier: Modifier=Modifier,initialTab: String="members",onActor: ((JsonObject)->Unit)?=null,onEnterChannel: ((String)->Unit)?=null,onClose: (() -> Unit)?=null) {
    val state by viewModel.state.collectAsState()
    var tab by remember(state.channel,initialTab) { mutableStateOf(initialTab.takeIf { it in setOf("members","settings","danger") } ?: "members") }
    var confirm by remember(state.channel) { mutableStateOf<GovernanceConfirmation?>(null) }
    var create by remember(state.channel) { mutableStateOf(false) }
    LaunchedEffect(state.channel) { viewModel.refresh() }
    Column(modifier.fillMaxSize().background(AtollColors.Ground)) {
        GovernanceHeader("频道详情",onClose) {
            TextButton(onClick={ create=true },enabled=state.writable) { Text("新建子频道") }
        }
        GovernanceTabs(listOf("members" to "成员","settings" to "设置","danger" to "危险操作"),tab) { tab=it }
        GovernanceStatus(state)
        when (tab) {
            "members" -> ChannelMembers(viewModel,state,onActor) { confirm=it }
            "settings" -> ChannelSettings(viewModel,state,onEnterChannel)
            else -> ChannelDanger(viewModel,state) { confirm=it }
        }
    }
    confirm?.let { entry -> GovernanceConfirmationDialog(entry,state.busy,onDismiss={ confirm=null }) { viewModel.execute(entry.command,entry.success);confirm=null } }
    state.memberView?.let { member -> AlertDialog(onDismissRequest=viewModel::dismissMember,title={ Text("成员详情") },text={ SelectionContainer { Text(member.toString()) } },confirmButton={ TextButton(onClick=viewModel::dismissMember) { Text("关闭") } }) }
    if (create) ChannelCreateDialog(viewModel,onEnterChannel,onClose={ create=false })
}
internal data class GovernanceConfirmation(val title: String,val detail: String,val command: GovernanceCommand,val success: String="操作终态已确认")
@Composable internal fun GovernanceConfirmationDialog(entry: GovernanceConfirmation,busy: Boolean,onDismiss: ()->Unit,onConfirm: ()->Unit) {
    AlertDialog(onDismissRequest={ if (!busy) onDismiss() },title={ Text(entry.title) },text={ Text(entry.detail) },
        dismissButton={ TextButton(onClick=onDismiss,enabled=!busy) { Text("取消") } },confirmButton={ TextButton(onClick=onConfirm,enabled=!busy) { Text("确认",color=MaterialTheme.colorScheme.error) } })
}
@Composable internal fun GovernanceHeader(title: String,onClose: (() -> Unit)?=null,actions: @Composable RowScope.()->Unit={}) {
    Row(Modifier.fillMaxWidth().background(AtollColors.Surface).heightIn(min=56.dp).padding(start=16.dp,end=4.dp),verticalAlignment=Alignment.CenterVertically) {
        Text(title,Modifier.weight(1f),style=MaterialTheme.typography.titleLarge);actions()
        onClose?.let { TextButton(onClick=it) { Text("关闭",color=AtollColors.Muted) } }
    }
}
@Composable internal fun GovernanceTabs(tabs: List<Pair<String,String>>,selected: String,onSelect: (String)->Unit) {
    Row(Modifier.fillMaxWidth().background(AtollColors.Surface).horizontalScroll(rememberScrollState()).padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(22.dp)) {
        tabs.forEach { (key,label) ->
            val active=key==selected
            Column(Modifier.heightIn(min=44.dp).semantics { this.selected=active }.clickable(role=Role.Tab) { onSelect(key) },horizontalAlignment=Alignment.CenterHorizontally) {
                Box(Modifier.heightIn(min=42.dp),contentAlignment=Alignment.Center) {
                    Text(label,fontSize=15.sp,fontWeight=if (active) FontWeight.SemiBold else FontWeight.Normal,
                        color=if (key=="danger") AtollColors.Danger else if (active) AtollColors.Ink else AtollColors.Muted)
                }
                Box(Modifier.height(2.dp).width(28.dp).background(if (active) AtollColors.Ink else Color.Transparent))
            }
        }
    }
    HorizontalDivider(color=AtollColors.Line)
}
@Composable internal fun GovernanceStatus(state: GovernanceState) {
    if (state.loading || state.busy || state.error.isNotEmpty() || state.message.isNotEmpty()) Column(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=6.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        if (state.loading || state.busy) Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Spinner(14.dp,AtollColors.Muted,AtollColors.Line);Text(if (state.busy) "正在提交…" else "正在读取目录…",fontSize=13.sp,color=AtollColors.Muted)
        }
        if (state.message.isNotEmpty()) NoticeBanner(state.message,Tone.Lagoon)
        if (state.error.isNotEmpty()) NoticeBanner(state.error,Tone.Danger)
    }
}
@Composable internal fun GovernanceCard(title: String,modifier: Modifier=Modifier,content: @Composable ColumnScope.()->Unit) {
    Column(modifier.fillMaxWidth().clip(AtollShapes.Card).background(AtollColors.Surface).border(1.dp,AtollColors.Line,AtollShapes.Card).padding(14.dp),
        verticalArrangement=Arrangement.spacedBy(10.dp)) { Text(title,fontSize=15.sp,fontWeight=FontWeight.SemiBold);content() }
}
@Composable internal fun GovernanceChoice(value: String,options: List<Pair<String,String>>,label: String,enabled: Boolean=true,onSelect: (String)->Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box { OutlinedButton(onClick={ expanded=true },enabled=enabled,modifier=Modifier.fillMaxWidth()) { Text("$label：${options.firstOrNull { it.first==value }?.second ?: "请选择"}") }
        DropdownMenu(expanded,onDismissRequest={ expanded=false }) { options.forEach { (key,name) -> DropdownMenuItem(text={ Text(name) },onClick={ expanded=false;onSelect(key) }) } }
    }
}
@Composable
private fun ChannelMembers(vm: GovernanceViewModel,state: GovernanceState,onActor: ((JsonObject)->Unit)?,onConfirm: (GovernanceConfirmation)->Unit) {
    val roster=remember(state.directory.actors) { governanceMembers(state.directory.actors) }
    val owner=currentChannel(state).text("owner_principal")
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        item { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text("当前成员 ${roster.size}",Modifier.padding(top=12.dp),style=MaterialTheme.typography.titleSmall);TextButton(onClick=vm::refresh,enabled=!state.loading) { Text("刷新") } } }
        items(roster,key={ it.id }) { row ->
            val ownerActor=row.principal.isNotEmpty() && row.principal==owner
            GovernanceCard(row.name+if (row.id==state.selfId) " · 我" else "") {
                Text("${row.kind} · ${row.id}",style=MaterialTheme.typography.bodySmall)
                if (row.body.isNotEmpty()) Text(row.body,style=MaterialTheme.typography.bodySmall)
                Text(when { row.online==true -> "在线";row.bound==true -> "已绑定";row.bound==false -> "未绑定";else -> "等待状态" },style=MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick={ if (onActor!=null) onActor(row.source) else vm.readMember(row.id) }) { Text("查看") }
                    TextButton(onClick={},enabled=false) { Text("绑定") }
                    TextButton(onClick={ onConfirm(GovernanceConfirmation("重启 ${row.name}？","重启结果以账本和名册事实为准。",GovernanceCommands.memberRestart(row.id),"重启终态已确认")) },enabled=state.writable && !state.busy && row.kind!="human" && !ownerActor) { Text("重启") }
                    TextButton(onClick={ onConfirm(GovernanceConfirmation("移除 ${row.name}？","成员条目及该频道里的配置将删除。",GovernanceCommands.memberDelete(row.id),"移除终态已确认")) },enabled=state.writable && !state.busy && row.id!=state.selfId && !ownerActor && !row.protected) { Text(if (ownerActor) "Owner" else "移除",color=if (ownerActor) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error) }
                }
            }
        }
        if (roster.isEmpty()) item { Text("暂无可管理的业务 Actor") }
        if (state.memberReady && !state.busy) item { Text("成员已就绪",color=MaterialTheme.colorScheme.primary) }
        item { Text("运行时推导的把手已隐藏。",style=MaterialTheme.typography.bodySmall) }
        item { AddParticipant(vm,state,roster) }
    }
}
@Composable private fun AddParticipant(vm: GovernanceViewModel,state: GovernanceState,roster: List<GovernanceMember>) {
    val principals=governancePrincipals(state.directory.principals).filter { p -> roster.none { it.principal==p.text("id") } }
    val descriptions=governanceDescriptions(state.directory.descriptions,true)
    val options=principals.map { "principal:${it.text("id")}" to "${it.displayName()} · 用户" }+descriptions.map { "description:${descriptionRef(it)}" to "${descriptionLabel(it,descriptions)} · class ${it.text("class")}" }+listOf("class:" to "直接按 Class 新建…")
    var candidate by remember(state.channel) { mutableStateOf("") };var name by remember(state.channel) { mutableStateOf("") };var klass by remember(state.channel) { mutableStateOf("") }
    GovernanceCard("添加参与者") {
        Text("用户直接邀请；Agent 和工具从某一版 Actor 描述或 Class 创建。显示名可以留空。",style=MaterialTheme.typography.bodySmall)
        GovernanceChoice(candidate,options,"参与者",!state.busy) { next -> candidate=next;name=if (next.startsWith("description:")) descriptions.firstOrNull { "description:${descriptionRef(it)}"==next }?.text("name").orEmpty() else "" }
        if (candidate.startsWith("class:")) OutlinedTextField(klass,{ klass=it },label={ Text("Class") },modifier=Modifier.fillMaxWidth(),enabled=!state.busy,singleLine=true)
        if (candidate.isNotEmpty() && !candidate.startsWith("principal:")) OutlinedTextField(name,{ name=it },label={ Text("显示名（可选）") },modifier=Modifier.fillMaxWidth(),enabled=!state.busy,singleLine=true)
        Button(onClick={
            val command=when {
                candidate.startsWith("principal:") -> GovernanceCommands.admit(candidate.removePrefix("principal:"))
                candidate.startsWith("description:") -> GovernanceCommands.memberCreate(candidate.removePrefix("description:"),false,name)
                else -> GovernanceCommands.memberCreate(klass,true,name)
            }
            vm.execute(command,"成员条目已写入；等待名册确认就绪")
        },enabled=state.writable && !state.busy && candidate in options.map { it.first } && (candidate!="class:" || klass.isNotBlank())) { Text("添加到频道") }
    }
}
@Composable private fun ChannelSettings(vm: GovernanceViewModel,state: GovernanceState,onEnter: ((String)->Unit)?) {
    val body=descriptionBody(state);val edit=descriptionEditable(state)
    var description by remember(state.channel,body) { mutableStateOf(body?.text("description").orEmpty()) }
    var serving by remember(state.channel,body) { mutableStateOf(body?.integer("serving")==1) }
    val current=currentChannel(state);val revision=state.channelView?.obj("description")?.integer("revision") ?: 0
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        item { GovernanceCard(channelName(state)) {
            Text("ID：${state.channel}\n父级：${current.text("parent_id").ifEmpty { "无（空间根）" }}\nOwner：${current.text("owner_principal").ifEmpty { "—" }}")
            val row=currentChannelRow(state)
            Text(when (row.measure("open")) { true -> "服务中";false -> "未服务";else -> "服务状态未知" })
            TextButton(onClick=vm::refresh,enabled=!state.loading) { Text("刷新目录事实") }
        } }
        item { GovernanceCard("频道状态") {
            TextButton(onClick=vm::readChannel,enabled=!state.busy && state.selfId.isNotEmpty()) { Text(if (state.channelView==null) "读取" else "刷新描述") }
            if (state.channelView==null) Text("点“读取”获取频道描述。",style=MaterialTheme.typography.bodySmall)
            else {
                Text("描述版本：第 $revision 版")
                if (body!=null) { Text("说明：${body.text("description").ifEmpty { "—" }}\n对外服务：${if (body.integer("serving")==1) "是" else "否"}");if (body.bool("readonly")) {
                    Text("这份描述由内核写入，只读。")
                    val raw=state.channelView.obj("description")["raw"]
                    SelectionContainer { Text((raw as? JsonPrimitive)?.content ?: raw?.toString() ?: body.toString(),style=MaterialTheme.typography.bodySmall) }
                } }
                else { Text(state.channelView.obj("description").text("problem").ifEmpty { "描述不可读" },color=MaterialTheme.colorScheme.error);SelectionContainer { Text(state.channelView.obj("description")["raw"]?.let { (it as? JsonPrimitive)?.content ?: it.toString() }.orEmpty()) } }
            }
        } }
        if (edit) item { GovernanceCard("说明与服务") {
            OutlinedTextField(description,{ description=it },label={ Text("说明") },modifier=Modifier.fillMaxWidth(),enabled=!state.busy,minLines=2,maxLines=5)
            Row(Modifier.fillMaxWidth().toggleable(serving,enabled=!state.busy,role=Role.Checkbox) { serving=it }) { Checkbox(serving,null,enabled=!state.busy);Text("对外服务",Modifier.padding(top=12.dp)) }
            Button(onClick={ vm.execute(GovernanceCommands.channelSet(state.channel,description,serving),"频道描述已写入") },enabled=state.writable && !state.busy) { Text("保存") }
        } }
        if (edit) item { GovernanceCard("设备") {
            Text("local-device · 默认，不需要挂载",style=MaterialTheme.typography.bodySmall)
            state.directory.devices.filter { it.directoryId()!="local-device" }.forEach { device ->
                val id=device.directoryId();val attached=(body?.get("devices") as? JsonArray)?.contains(JsonPrimitive(id))==true
                Text("${device.displayName()} · ${when (device.measure("online")) { true -> "在线";false -> "离线";else -> "未知" }} · ${if (!attached) "未挂载" else if (state.directory.channelDevices.any { it.directoryId()==id }) "已挂载" else "已写入描述"}",style=MaterialTheme.typography.bodySmall)
                TextButton(onClick={ vm.execute(GovernanceCommands.deviceAttach(state.channel,id,!attached),if (attached) "设备已卸载" else "设备已挂载") },enabled=state.writable && !state.busy) { Text(if (attached) "卸载" else "挂到本频道") }
            }
        } }
        item { GovernanceCard("子频道") {
            val children=state.directory.channels.filter { it.declared().text("parent_id")==state.channel }
            if (children.isEmpty()) Text("还没有子频道。")
            children.forEach { row -> TextButton(onClick={ onEnter?.invoke(row.directoryId()) },enabled=onEnter!=null) { Text("# ${row.declared().text("qualified_name").ifEmpty { row.displayName() }}") } }
        } }
    }
}
@Composable private fun ChannelDanger(vm: GovernanceViewModel,state: GovernanceState,onConfirm: (GovernanceConfirmation)->Unit) {
    var typed by remember(state.channel) { mutableStateOf("") }
    var selected by remember(state.channel) { mutableStateOf(retirementTarget(state)) }
    val candidate=retirementTarget(state,selected)
    LaunchedEffect(candidate) { selected=candidate }
    val target=selected
    Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("退役频道",style=MaterialTheme.typography.titleMedium)
        Text("退役后频道停止写入。存在活动子频道或描述只读时，服务端会拒绝。")
        if (target==null) Text("正在读取频道信息…",style=MaterialTheme.typography.bodySmall)
        else OutlinedTextField(typed,{ typed=it },label={ Text("输入 ${target.name} 确认") },modifier=Modifier.fillMaxWidth(),singleLine=true,enabled=!state.busy)
        Button(onClick={ target?.let { onConfirm(GovernanceConfirmation("退役 ${it.name}？","频道将停止写入。",GovernanceCommands.channelDelete(it.channel),"频道退役终态已确认")) } },enabled=target!=null && state.writable && !state.busy && typed==target.name,colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)) { Text("退役当前频道") }
    }
}

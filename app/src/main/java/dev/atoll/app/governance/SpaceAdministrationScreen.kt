package dev.atoll.app.governance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*

@Composable
fun SpaceAdministrationScreen(viewModel: GovernanceViewModel,modifier: Modifier=Modifier,onClose: (() -> Unit)?=null) {
    val state by viewModel.state.collectAsState();var tab by remember { mutableStateOf("descriptions") }
    var confirm by remember { mutableStateOf<GovernanceConfirmation?>(null) }
    LaunchedEffect(state.channel) { viewModel.refresh() }
    Column(modifier.fillMaxSize().background(dev.atoll.app.ui.theme.AtollColors.Ground)) {
        GovernanceHeader("空间管理",onClose)
        GovernanceTabs(listOf("descriptions" to "Actor 描述","devices" to "设备","keys" to "全局 key"),tab) { tab=it }
        GovernanceStatus(state)
        when (tab) {
            "descriptions" -> ActorDescriptions(viewModel,state) { confirm=it }
            "devices" -> SpaceDevices(viewModel,state) { confirm=it }
            else -> GlobalKeysScreen(viewModel,state)
        }
    }
    confirm?.let { entry -> GovernanceConfirmationDialog(entry,state.busy,onDismiss={ confirm=null }) { viewModel.execute(entry.command,entry.success);confirm=null } }
}
@Composable private fun ActorDescriptions(vm: GovernanceViewModel,state: GovernanceState,onConfirm: (GovernanceConfirmation)->Unit) {
    val rows=governanceDescriptions(state.directory.descriptions);val groups=rows.groupBy { it.text("id") }
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        item { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text("Actor 描述 ${groups.size}",Modifier.padding(top=12.dp));TextButton(onClick=vm::refresh,enabled=!state.loading) { Text("刷新") } } }
        groups.forEach { (id,versions) ->
            item(key="group:$id") { Text("${versions.maxBy { it.integer("version") }.text("name").ifEmpty { id }} · $id",style=MaterialTheme.typography.titleSmall) }
            items(versions.sortedByDescending { it.integer("version") },key={ descriptionRef(it) }) { row ->
                var params by remember { mutableStateOf(false) }
                GovernanceCard(descriptionLabel(row,rows)) {
                    Text("class ${row.text("class")} · ${if (row.text("status")=="retired") "已退役" else "可用"}${if (row["configurable"]==JsonPrimitive(false)) " · 不可配置" else ""}\nOwner：${row.text("owner").ifEmpty { "—" }}",style=MaterialTheme.typography.bodySmall)
                    if (row.text("description").isNotEmpty()) Text(row.text("description"))
                    if (row.obj("params").isNotEmpty()) { TextButton(onClick={ params=!params }) { Text("参数 JSON") };if (params) SelectionContainer { Text(row.obj("params").toString(),style=MaterialTheme.typography.bodySmall) } }
                    if (row.text("status")!="retired") TextButton(onClick={ onConfirm(GovernanceConfirmation("退役 ${descriptionLabel(row,rows)}？","新成员将不能引用这一版；已引用它的成员继续运行。",GovernanceCommands.actorDescriptionRetire(row.text("id"),row.integer("version")),"Actor 描述版本已退役")) },enabled=state.writable && !state.busy) { Text("退役",color=MaterialTheme.colorScheme.error) }
                }
            }
        }
        if (rows.isEmpty()) item { Text("还没有 Actor 描述。") }
        item { ActorDescriptionForm(vm,state,groups) }
    }
}
@Composable private fun ActorDescriptionForm(vm: GovernanceViewModel,state: GovernanceState,groups: Map<String,List<JsonObject>>) {
    var base by remember { mutableStateOf("") };var name by remember { mutableStateOf("") };var klass by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") };var params by remember { mutableStateOf("{}") };var configurable by remember { mutableStateOf(true) };var error by remember { mutableStateOf("") }
    val latest=groups.mapValues { it.value.maxBy { row -> row.integer("version") } }
    GovernanceCard("新建 Actor 描述") {
        Text("已有版本不会修改；可以新建一条，或给同一个 ID 出新版本。",style=MaterialTheme.typography.bodySmall)
        GovernanceChoice(base,listOf("" to "新的一条 Actor 描述")+latest.map { (id,row) -> id to "${descriptionLabel(row,latest.values.toList())} 的新版本" },"建什么",!state.busy) { id ->
            base=id;latest[id]?.let { row -> name=row.text("name");klass=row.text("class");description=row.text("description");params=row.obj("params").toString();configurable=row["configurable"]!=JsonPrimitive(false) }
        }
        if (base.isNotEmpty()) Text("将建成 @${(latest[base]?.integer("version") ?: 0)+1}",style=MaterialTheme.typography.bodySmall)
        OutlinedTextField(name,{ name=it },label={ Text("名字") },modifier=Modifier.fillMaxWidth(),enabled=!state.busy,singleLine=true)
        OutlinedTextField(klass,{ klass=it },label={ Text("Class") },modifier=Modifier.fillMaxWidth(),enabled=!state.busy,singleLine=true)
        OutlinedTextField(description,{ description=it },label={ Text("说明") },modifier=Modifier.fillMaxWidth(),enabled=!state.busy,minLines=2,maxLines=4)
        OutlinedTextField(params,{ params=it },label={ Text("参数 JSON") },modifier=Modifier.fillMaxWidth(),enabled=!state.busy,minLines=3,maxLines=7)
        Row(Modifier.fillMaxWidth().toggleable(configurable,enabled=!state.busy,role=Role.Checkbox) { configurable=it }) { Checkbox(configurable,null,enabled=!state.busy);Text("可被配置",Modifier.padding(top=12.dp)) }
        Text("参数可用 \"\$required:说明\" 标记必填；\"\$global.名称\" 引用全局 key。",style=MaterialTheme.typography.bodySmall)
        if (error.isNotEmpty()) Text(error,color=MaterialTheme.colorScheme.error)
        Button(onClick={
            val parsed=runCatching { Json.parseToJsonElement(params.ifBlank { "{}" }) as? JsonObject }.getOrNull()
            if (parsed==null) error="参数必须是 JSON 对象" else { error="";vm.execute(GovernanceCommands.actorDescriptionCreate(base,name,klass,description,parsed,configurable),"Actor 描述已创建") }
        },enabled=state.writable && !state.busy && name.isNotBlank() && klass.isNotBlank()) { Text(if (base.isEmpty()) "新建" else "出新版本") }
    }
}
@Composable private fun SpaceDevices(vm: GovernanceViewModel,state: GovernanceState,onConfirm: (GovernanceConfirmation)->Unit) {
    var name by remember { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        item { GovernanceCard("创建设备身份") {
            OutlinedTextField(name,{ name=it },label={ Text("设备名称") },modifier=Modifier.fillMaxWidth(),enabled=!state.busy,singleLine=true)
            Button(onClick={ vm.execute(GovernanceCommands.deviceCreate(name),"设备身份已创建") },enabled=state.writable && !state.busy && name.isNotBlank()) { Text("创建设备") }
        } }
        item { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text("空间设备",Modifier.padding(top=12.dp));TextButton(onClick=vm::refresh,enabled=!state.loading) { Text("刷新") } } }
        items(state.directory.devices,key={ it.directoryId() }) { row ->
            val id=row.directoryId()
            GovernanceCard(row.displayName()) {
                Text("$id · ${when (row.measure("online")) { true -> "在线";false -> "离线";else -> "未知" }}",style=MaterialTheme.typography.bodySmall)
                TextButton(onClick={ onConfirm(GovernanceConfirmation("退役设备 ${row.displayName()}？","这个设备身份将被删除。",GovernanceCommands.deviceDelete(id),"设备已退役")) },enabled=state.writable && !state.busy && id!="local-device" && !row.declared().bool("protected")) { Text(if (id=="local-device") "默认设备" else "退役",color=MaterialTheme.colorScheme.error) }
            }
        }
        if (state.directory.devices.isEmpty()) item { Text("当前目录没有设备。") }
        item { Text("挂载设备请到“频道详情 → 设置”。",style=MaterialTheme.typography.bodySmall) }
    }
}
@Composable private fun GlobalKeysScreen(vm: GovernanceViewModel,state: GovernanceState) {
    var name by remember { mutableStateOf("") };var value by remember { mutableStateOf("") };var editing by remember { mutableStateOf("") };var changed by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") };var operation by remember { mutableStateOf("") };var error by remember { mutableStateOf("") }
    val available=state.selfId.isNotEmpty()
    LaunchedEffect(available) { if (available && state.keyNames==null) vm.keyNames() }
    LaunchedEffect(state.keyRevision) { if (state.message=="全局 key 已保存") { value="";changed="";editing="" } }
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        item { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text("全局 key ${state.keyNames?.size ?: ""}",Modifier.padding(top=12.dp));TextButton(onClick=vm::keyNames,enabled=available && !state.busy) { Text("刷新") } } }
        item { Text(if (available) "整个空间共用一份；成员配置用 \"\$global.名称\" 引用。这里只列名字。" else "全局 key 需要经一个你是成员的频道读写。",style=MaterialTheme.typography.bodySmall) }
        if (available) items(state.keyNames.orEmpty(),key={ it }) { key -> GovernanceCard(key) {
            Text("\$global.$key",style=MaterialTheme.typography.bodySmall)
            Row {
                TextButton(onClick={ editing=key;changed="" },enabled=state.writable && !state.busy) { Text("修改值") }
                TextButton(onClick={ confirmation=key;operation="delete" },enabled=state.writable && !state.busy) { Text("删除",color=MaterialTheme.colorScheme.error) }
            }
            if (editing==key) {
                OutlinedTextField(changed,{ changed=it },label={ Text("新值") },modifier=Modifier.fillMaxWidth(),enabled=!state.busy,visualTransformation=PasswordVisualTransformation(),singleLine=true)
                Row { TextButton(onClick={ editing="";changed="" },enabled=!state.busy) { Text("取消") };Button(onClick={ confirmation=key;operation="write" },enabled=state.writable && !state.busy && changed.isNotEmpty()) { Text("保存新值") } }
            }
        } }
        if (available && state.keyNames?.isEmpty()==true) item { Text("还没有全局 key。") }
        item { GovernanceCard("添加全局 key") {
            OutlinedTextField(name,{ name=it },label={ Text("名称") },modifier=Modifier.fillMaxWidth(),enabled=!state.busy,singleLine=true)
            Text("1–64 位小写字母、数字、下划线或连字符。",style=MaterialTheme.typography.bodySmall)
            OutlinedTextField(value,{ value=it },label={ Text("值") },modifier=Modifier.fillMaxWidth(),enabled=!state.busy,visualTransformation=PasswordVisualTransformation(),singleLine=true)
            if (error.isNotEmpty()) Text(error,color=MaterialTheme.colorScheme.error)
            Button(onClick={
                if (!name.trim().matches(Regex("[a-z0-9_-]{1,64}"))) error="全局 key 名称无效" else { error="";confirmation=name.trim();operation="save" }
            },enabled=available && state.writable && !state.busy && name.isNotBlank() && value.isNotEmpty()) { Text("添加") }
        } }
    }
    if (confirmation.isNotEmpty()) AlertDialog(onDismissRequest={ confirmation="" },title={ Text(if (operation=="delete") "删除 global/$confirmation？" else "保存 global/$confirmation？") },text={ Text(if (operation=="delete") "引用它的成员配置可能因缺少这个 key 而无法构建。" else "已有同名 key 会被覆盖。") },dismissButton={ TextButton(onClick={ confirmation="" }) { Text("取消") } },confirmButton={ TextButton(onClick={
        when (operation) { "delete" -> vm.deleteKey(confirmation);"write" -> vm.saveKey(confirmation,changed,true);else -> vm.saveKey(confirmation,value,false) };confirmation=""
    },enabled=!state.busy) { Text("确认") } })
}

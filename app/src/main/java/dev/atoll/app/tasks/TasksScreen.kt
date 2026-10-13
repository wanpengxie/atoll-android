package dev.atoll.app.tasks

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.serialization.json.*
import java.time.*
import java.time.format.DateTimeFormatter

internal val taskKinds=linkedMapOf("task" to "任务","approval" to "审批","agent_run" to "Agent 回合","recovery" to "恢复事项","automation" to "自动动作")
internal val taskStates=mapOf("active" to "进行中","running" to "运行中","queued" to "排队中","held" to "已暂停","waiting" to "等待中","blocked" to "受阻","uncertain" to "待确认","completed" to "已完成","failed" to "失败","cancelled" to "已取消","expired" to "已过期")
internal val actionLabels=mapOf(TaskAction.APPROVE to "批准",TaskAction.REJECT to "拒绝",TaskAction.RETRY to "使用原编号重试",TaskAction.CANCEL to "撤回请求",TaskAction.CANCEL_TIMER to "取消自动动作","agent.steer" to "插入指令","agent.interrupt" to "停止")
internal fun taskDate(value: String): String = runCatching {
    val instant=value.toLongOrNull()?.let(Instant::ofEpochMilli) ?: Instant.parse(value)
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(instant)
}.getOrDefault(value.ifEmpty { "未设置" })

@Composable
fun TasksScreen(viewModel: TasksViewModel,modifier: Modifier=Modifier,onSource: (JsonObject)->Unit={},onTurn: (String)->Unit={}) {
    val state by viewModel.state.collectAsState()
    var waiting by remember { mutableStateOf(false) }
    var scope by remember { mutableStateOf("me") };var status by remember { mutableStateOf("active") };var kind by remember { mutableStateOf("all") };var page by remember { mutableIntStateOf(0) }
    var automation by remember { mutableStateOf(false) }
    val visible=remember(state.items,scope,status,kind,state.selfId) { filterTasks(state.items,scope,status,kind,state.selfId) }
    val window=remember(visible,page) { taskPage(visible,page) }
    LaunchedEffect(window.page) { page=window.page }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().heightIn(min=44.dp),horizontalArrangement=Arrangement.End) {
            TextButton(onClick={ automation=true },enabled=state.writable) { Text("安排自动动作") }
            if (state.providers.isNotEmpty()) TextButton(onClick={ viewModel.beginCreation() },enabled=state.creationReady) { Text("新建任务") }
        }
        TabRow(selectedTabIndex=if (waiting) 1 else 0) {
            Tab(!waiting,onClick={ waiting=false },text={ Text("工作项 ${state.items.size}") })
            Tab(waiting,onClick={ waiting=true },text={ Text("等待区 ${state.waiting.size}") })
        }
        if (state.error.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal=12.dp)) {
            Text(state.error,Modifier.weight(1f),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
            TextButton(onClick=viewModel::clearError) { Text("关闭") }
        }
        if (!waiting) {
            Row(Modifier.fillMaxWidth()) {
                ChoiceFilter(scope,mapOf("me" to "与我相关","all" to "全部"),Modifier.weight(1f)) { scope=it;page=0 }
                ChoiceFilter(status,mapOf("active" to "待处理","completed" to "已完成","failed" to "失败"),Modifier.weight(1f)) { status=it;page=0 }
                ChoiceFilter(kind,mapOf("all" to "全部类型")+taskKinds,Modifier.weight(1f)) { kind=it;page=0 }
            }
            val groups=remember(window.items) { window.items.groupBy(::taskGroup) }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(),contentPadding=PaddingValues(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                if (window.hasOlder) item { TextButton(onClick={ page++ }) { Text("查看更早任务") } }
                for ((group,label) in listOf("needs_you" to "需要你处理","active" to "进行中","recovery" to "恢复事项","automation" to "自动动作 · 本次会话","history" to "历史")) {
                    val rows=groups[group].orEmpty();if (rows.isEmpty()) continue
                    item(key="group:$group") { Text("$label ${rows.size}",style=MaterialTheme.typography.titleSmall,modifier=Modifier.padding(top=4.dp)) }
                    items(rows,key={ it.key }) { item -> TaskRow(item,state.actorNames) { viewModel.select(item.key) } }
                }
                if (visible.isEmpty()) item { Text(if (scope!="me" || status!="active" || kind!="all") "没有符合当前筛选的项目" else "当前没有待处理工作项",Modifier.padding(vertical=24.dp)) }
                if (state.providers.isEmpty()) item { Text("当前频道没有声明 task.create 的成员。",style=MaterialTheme.typography.bodySmall) }
                if (window.hasNewer) item { TextButton(onClick={ page=(page-1).coerceAtLeast(0) }) { Text("查看更新任务") } }
            }
        } else LazyColumn(Modifier.weight(1f).fillMaxWidth(),contentPadding=PaddingValues(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            items(state.waiting,key={ it.key }) { item ->
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                    Text(compactWorkTitle(item.title),style=MaterialTheme.typography.titleSmall,maxLines=1,overflow=TextOverflow.Ellipsis)
                    Text("${state.actorNames[item.actorId] ?: item.actorId} · ${taskStates[item.state] ?: item.state}",style=MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                        item.actions.forEach { action ->
                            val command=state.commandStates["${item.key}:$action"]
                            val supported=action in setOf(TaskAction.CANCEL,"agent.steer","agent.interrupt")
                            val authority=action==TaskAction.CANCEL && item.submission!=null || state.writable && item.actorId in state.actorNames
                            TextButton(onClick={ viewModel.perform { executeWaiting(item,action) } },enabled=supported && authority && command?.state !in setOf("submitting","disabled")) { Text(actionLabels[action] ?: action) }
                        }
                    }
                    if (item.actions.isEmpty()) Text("当前账本未声明可用控制",style=MaterialTheme.typography.bodySmall)
                } }
            }
            if (state.waiting.isEmpty()) item { Text("等待区为空",Modifier.padding(vertical=24.dp)) }
        }
    }
    state.selectedItem?.let { item -> TaskDetail(item,viewModel,onClose={ viewModel.select("") },onSource=onSource,onTurn=onTurn) }
    if (state.creating) CreateTaskDialog(viewModel,onClose=viewModel::endCreation)
    if (automation) TimedActionsDialog(viewModel,onClose={ automation=false })
}
@Composable private fun ChoiceFilter(value: String,options: Map<String,String>,modifier: Modifier=Modifier,onSelect: (String)->Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) { TextButton(onClick={ expanded=true }) { Text(options[value] ?: value,maxLines=1,overflow=TextOverflow.Ellipsis) }
        DropdownMenu(expanded,onDismissRequest={ expanded=false }) { options.forEach { (key,label) -> DropdownMenuItem(text={ Text(label) },onClick={ expanded=false;onSelect(key) }) } }
    }
}
@Composable private fun TaskRow(item: TaskFact,names: Map<String,String>,onOpen: ()->Unit) {
    Card(onClick=onOpen,modifier=Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Row { Text(taskKinds[item.kind] ?: item.kind,Modifier.weight(1f),style=MaterialTheme.typography.labelSmall);Text(taskStates[item.state] ?: item.state,style=MaterialTheme.typography.labelSmall) }
        Text(compactWorkTitle(item.title),style=MaterialTheme.typography.titleSmall,maxLines=1,overflow=TextOverflow.Ellipsis)
        Row { Text(item.assigneeActorIds.joinToString("、") { names[it] ?: it }.ifEmpty { "未指定" },Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
            if (item.localScope=="this_device") Text(if(item.kind=="automation") "本次会话" else "本设备",style=MaterialTheme.typography.labelSmall) }
    } }
}

/** Can also be mounted directly under a human.approve/human.ask timeline turn. */
@Composable
fun ApprovalCard(item: TaskFact,names: Map<String,String> = emptyMap(),busy: Boolean=false,error: String="",onResolve: (action: String,text: String,note: String)->Unit) {
    var answer by remember(item.key) { mutableStateOf("") };var note by remember(item.key) { mutableStateOf("") }
    val ask=(item.turn?.request?.type ?: item.diagnostic.text("requestType"))=="human.ask"
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(item.title,style=MaterialTheme.typography.titleMedium)
        Text("${names[item.requesterActorId] ?: item.requesterActorId} · ${taskStates[item.state] ?: item.state}",style=MaterialTheme.typography.bodySmall)
        item.turn?.request?.body?.text("text")?.takeIf { it.isNotEmpty() && it!=item.title }?.let { Text(it) }
        if (item.diagnostic.text("impact").isNotEmpty()) Text("影响：${item.diagnostic.text("impact")}")
        if (item.dueAt.isNotEmpty()) Text("到期：${taskDate(item.dueAt)}",style=MaterialTheme.typography.bodySmall)
        if (item.actionableBySelf && item.state=="waiting") {
            if (ask) OutlinedTextField(answer,{ answer=it },label={ Text("回答") },modifier=Modifier.fillMaxWidth(),enabled=!busy,minLines=2)
            else OutlinedTextField(note,{ note=it },label={ Text("备注（可选）") },modifier=Modifier.fillMaxWidth(),enabled=!busy)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                if (TaskAction.APPROVE in item.actions) Button(onClick={ onResolve(TaskAction.APPROVE,answer,note) },enabled=!busy) { Text(if (busy) "正在提交…" else if (ask) "提交回答" else "批准") }
                if (!ask && TaskAction.REJECT in item.actions) OutlinedButton(onClick={ onResolve(TaskAction.REJECT,"",note) },enabled=!busy) { Text("拒绝") }
            }
        }
        if (error.isNotEmpty()) Text(error,color=MaterialTheme.colorScheme.error)
    } }
}

@Composable
fun TaskDetail(item: TaskFact,viewModel: TasksViewModel,modifier: Modifier=Modifier,onClose: ()->Unit,onSource: (JsonObject)->Unit={},onTurn: (String)->Unit={}) {
    val state by viewModel.state.collectAsState()
    var diagnostic by remember(item.key) { mutableStateOf(false) }
    TaskDialog(item.title,onClose,modifier) {
        LazyColumn(Modifier.fillMaxWidth().weight(1f,false),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item { Text("${taskKinds[item.kind] ?: item.kind} · ${taskStates[item.state] ?: item.state}",style=MaterialTheme.typography.titleSmall) }
            if (item.localScope=="this_device") item { Text(if(item.kind=="automation") "本次会话记录" else "本设备记录",style=MaterialTheme.typography.labelLarge) }
            item { Text("负责人：${item.assigneeActorIds.joinToString("、") { state.actorNames[it] ?: it }.ifEmpty { "未指定" }}\n创建者：${state.actorNames[item.requesterActorId] ?: item.requesterActorId}\n截止/触发：${taskDate(item.dueAt)}\n事实来源：${when { item.provenance=="ledger" -> "频道账本";item.kind=="automation" -> "本次会话记录";else -> "本设备持久记录" }}") }
            if (item.description.isNotEmpty()) item { Text(item.description) }
            if (item.waitingFor.isNotEmpty()) item { Text("当前等待：${item.waitingFor}") }
            when (item.kind) {
                "approval" -> item {
                    val commands=item.actions.mapNotNull { state.commandStates["${item.key}:$it"] }
                    ApprovalCard(item,state.actorNames,commands.any { it.state in setOf("submitting","disabled") },commands.firstOrNull { it.state=="failed" }?.error.orEmpty()) { action,text,note -> viewModel.perform { execute(item,action,note,text) } }
                }
                "agent_run" -> item { TextButton(onClick={ onTurn(item.requestId.ifEmpty { item.id }) }) { Text("打开完整回合与控制") } }
                "recovery" -> item { Text(if (item.state=="uncertain") "发送结果尚未由频道账本确认。" else "上一次提交明确失败，使用原编号重试。") }
                "automation" -> item { Text("消息类型：${item.diagnostic.text("msgType")}");SelectionContainer { Text(item.diagnostic["payload"]?.toString().orEmpty(),fontFamily=FontFamily.Monospace) } }
                "task" -> item { Text("任务编号：${item.nativeId}\n执行者：${state.actorNames[item.diagnostic.text("providerActorId")] ?: item.diagnostic.text("providerActorId")}") }
            }
            if (item.kind!="approval") item {
                if (item.actions.isEmpty()) Text("当前事实没有声明可用操作。",style=MaterialTheme.typography.bodySmall)
                item.actions.forEach { action ->
                    val command=state.commandStates["${item.key}:$action"]
                    val supported=action in setOf(TaskAction.CANCEL,TaskAction.RETRY,TaskAction.CANCEL_TIMER)
                    TextButton(onClick={ viewModel.perform { execute(item,action) } },enabled=supported && (state.writable || action==TaskAction.RETRY || action==TaskAction.CANCEL && item.submission!=null) && command?.state !in setOf("submitting","disabled")) { Text(actionLabels[action] ?: action) }
                    if (command?.state=="failed") Text(command.error,color=MaterialTheme.colorScheme.error)
                }
            }
            item { TextButton(onClick={ onSource(item.source) }) { Text("返回来源") } }
            item { TextButton(onClick={ diagnostic=!diagnostic }) { Text("诊断信息") };if (diagnostic) SelectionContainer { Text(item.diagnostic.toString(),fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodySmall) } }
        }
    }
}
@Composable internal fun TaskDialog(title: String,onClose: ()->Unit,modifier: Modifier=Modifier,busy: Boolean=false,content: @Composable ColumnScope.()->Unit) {
    Dialog(onDismissRequest={ if (!busy) onClose() },properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(modifier.fillMaxWidth(.96f).heightIn(max=720.dp),shape=MaterialTheme.shapes.large) {
            Column(Modifier.imePadding()) {
                Row(Modifier.fillMaxWidth().padding(start=16.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                    Text(title,Modifier.weight(1f).padding(vertical=12.dp),style=MaterialTheme.typography.titleMedium)
                    TextButton(onClick=onClose,enabled=!busy) { Text("关闭") }
                }
                content()
            }
        }
    }
}
@Composable
fun ActivityCenterScreen(snapshot: ActivitySnapshot,modifier: Modifier=Modifier,onSource: (JsonObject)->Unit={}) {
    var operations by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize()) {
        TabRow(if (operations) 1 else 0) {
            Tab(!operations,onClick={ operations=false },text={ Text("活动") });Tab(operations,onClick={ operations=true },text={ Text("操作") })
        }
        val rows=if (operations) snapshot.operations else snapshot.activities
        LazyColumn(Modifier.weight(1f).fillMaxWidth(),contentPadding=PaddingValues(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            if (operations && snapshot.operationsUnavailable) item { Text("当前没有可用的进行中操作快照") }
            items(rows,key={ it.key }) { row -> Card(onClick={ onSource(row.source) },modifier=Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text(compactWorkTitle(row.title),style=MaterialTheme.typography.titleSmall,maxLines=1,overflow=TextOverflow.Ellipsis);Text("${taskKinds[row.kind] ?: "操作"} · ${row.channelName} · ${taskStates[row.state] ?: row.state}",style=MaterialTheme.typography.bodySmall)
                    if (row.detail.isNotEmpty()) Text(row.detail,style=MaterialTheme.typography.bodySmall,maxLines=2,overflow=TextOverflow.Ellipsis)
                    Text("返回来源 ›",color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelMedium)
                }
            } }
            if (rows.isEmpty() && !(operations && snapshot.operationsUnavailable)) item { Text("暂无活动",Modifier.padding(vertical=24.dp)) }
        }
    }
}

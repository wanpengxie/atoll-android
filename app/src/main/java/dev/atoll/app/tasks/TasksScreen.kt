package dev.atoll.app.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.atoll.app.ui.components.*
import dev.atoll.app.ui.theme.AtollColors
import dev.atoll.app.ui.theme.AtollShapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

internal fun kindTone(kind: String): Tone = when (kind) {
    "approval" -> Tone.Waiting; "recovery" -> Tone.Danger; "agent_run" -> Tone.Running; "task" -> Tone.Lagoon; else -> Tone.Neutral
}

@Composable
fun TasksScreen(viewModel: TasksViewModel,modifier: Modifier=Modifier,onSource: (JsonObject)->Unit={},onTurn: (String)->Unit={}) {
    val state by viewModel.state.collectAsState()
    var waiting by remember { mutableStateOf(false) }
    var scope by remember { mutableStateOf("me") };var status by remember { mutableStateOf("active") };var kind by remember { mutableStateOf("all") };var page by remember { mutableIntStateOf(0) }
    var automation by remember { mutableStateOf(false) }
    val visible=remember(state.items,scope,status,kind,state.selfId) { filterTasks(state.items,scope,status,kind,state.selfId) }
    val window=remember(visible,page) { taskPage(visible,page) }
    LaunchedEffect(window.page) { page=window.page }
    Column(modifier.fillMaxSize().background(AtollColors.Ground)) {
        Row(Modifier.fillMaxWidth().padding(start=12.dp,end=12.dp,top=10.dp),verticalAlignment=Alignment.CenterVertically) {
            SegmentedControl(listOf("工作项 ${state.items.size}","等待区 ${state.waiting.size}"),if (waiting) 1 else 0,{ waiting=it==1 })
        }
        if (state.error.isNotEmpty()) NoticeBanner(state.error,Tone.Danger,Modifier.padding(start=12.dp,end=12.dp,top=8.dp),action="关闭",onAction=viewModel::clearError)
        if (!waiting) {
            ChipRow(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=8.dp)) {
                ChoiceFilter(scope,mapOf("me" to "与我相关","all" to "全部")) { scope=it;page=0 }
                ChoiceFilter(status,mapOf("active" to "待处理","completed" to "已完成","failed" to "失败")) { status=it;page=0 }
                ChoiceFilter(kind,mapOf("all" to "全部类型")+taskKinds) { kind=it;page=0 }
            }
            val groups=remember(window.items) { window.items.groupBy(::taskGroup) }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(),contentPadding=PaddingValues(start=12.dp,end=12.dp,bottom=12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                if (window.hasOlder) item { TextButton(onClick={ page++ }) { Text("查看更早任务",color=AtollColors.Lagoon) } }
                for ((group,label) in listOf("needs_you" to "需要你处理","active" to "进行中","recovery" to "恢复事项","automation" to "自动动作 · 本次会话","history" to "历史")) {
                    val rows=groups[group].orEmpty();if (rows.isEmpty()) continue
                    item(key="group:$group") { SectionLabel(label,Modifier.padding(top=4.dp),rows.size.toString()) }
                    items(rows,key={ it.key }) { item -> TaskRow(item,state.actorNames) { viewModel.select(item.key) } }
                }
                if (visible.isEmpty()) item { EmptyNote(if (scope!="me" || status!="active" || kind!="all") "没有符合当前筛选的项目" else "当前没有待处理工作项") }
                if (state.providers.isEmpty()) item { Text("当前频道没有声明 task.create 的成员。",Modifier.padding(horizontal=4.dp),fontSize=13.sp,color=AtollColors.Muted) }
                if (window.hasNewer) item { TextButton(onClick={ page=(page-1).coerceAtLeast(0) }) { Text("查看更新任务",color=AtollColors.Lagoon) } }
            }
        } else LazyColumn(Modifier.weight(1f).fillMaxWidth(),contentPadding=PaddingValues(12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            items(state.waiting,key={ it.key }) { item ->
                Column(Modifier.fillMaxWidth().clip(AtollShapes.Card).background(AtollColors.Surface).border(1.dp,AtollColors.Line,AtollShapes.Card).padding(14.dp),
                    verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    WorkTitle(item.title)
                    Text("${state.actorNames[item.actorId] ?: item.actorId} · ${taskStates[item.state] ?: item.state}",fontSize=13.sp,color=AtollColors.Muted)
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        item.actions.forEach { action ->
                            val command=state.commandStates["${item.key}:$action"]
                            val supported=action in setOf(TaskAction.CANCEL,"agent.steer","agent.interrupt")
                            val authority=action==TaskAction.CANCEL && item.submission!=null || state.writable && item.actorId in state.actorNames
                            AtollButton(actionLabels[action] ?: action,{ viewModel.perform { executeWaiting(item,action) } },
                                kind=if (action=="agent.interrupt") ButtonKind.Danger else ButtonKind.Secondary,height=40.dp,
                                enabled=supported && authority && command?.state !in setOf("submitting","disabled"))
                        }
                    }
                    if (item.actions.isEmpty()) Text("当前账本未声明可用控制",fontSize=13.sp,color=AtollColors.Muted)
                }
            }
            if (state.waiting.isEmpty()) item { EmptyNote("等待区为空") }
        }
        if (!waiting) Row(Modifier.fillMaxWidth().background(AtollColors.Surface).padding(12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            AtollButton("安排自动动作",{ automation=true },Modifier.weight(1f),kind=ButtonKind.Secondary,enabled=state.writable)
            if (state.providers.isNotEmpty()) AtollButton("新建任务",{ viewModel.beginCreation() },Modifier.weight(1f),enabled=state.creationReady)
        }
    }
    state.selectedItem?.let { item -> TaskDetail(item,viewModel,onClose={ viewModel.select("") },onSource=onSource,onTurn=onTurn) }
    if (state.creating) CreateTaskDialog(viewModel,onClose=viewModel::endCreation)
    if (automation) TimedActionsDialog(viewModel,onClose={ automation=false })
}
@Composable private fun EmptyNote(text: String) {
    Text(text,Modifier.fillMaxWidth().padding(vertical=32.dp),fontSize=15.sp,color=AtollColors.Muted,textAlign=TextAlign.Center)
}
@Composable private fun ChoiceFilter(value: String,options: Map<String,String>,onSelect: (String)->Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.heightIn(min=36.dp).clip(AtollShapes.Pill).background(AtollColors.Surface).border(1.dp,AtollColors.ControlLine,AtollShapes.Pill)
            .clickable { expanded=true }.padding(start=12.dp,end=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(options[value] ?: value,fontSize=13.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
            Icon(Icons.Outlined.KeyboardArrowDown,null,Modifier.size(16.dp),tint=AtollColors.Muted)
        }
        DropdownMenu(expanded,onDismissRequest={ expanded=false }) { options.forEach { (key,label) -> DropdownMenuItem(text={ Text(label,fontSize=15.sp) },onClick={ expanded=false;onSelect(key) }) } }
    }
}
@Composable private fun TaskRow(item: TaskFact,names: Map<String,String>,onOpen: ()->Unit) {
    Column(Modifier.fillMaxWidth().clip(AtollShapes.Card).background(AtollColors.Surface).border(1.dp,if (item.kind=="recovery") AtollColors.DangerLine else AtollColors.Line,AtollShapes.Card)
        .clickable(onClick=onOpen).padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            StatusPill(taskKinds[item.kind] ?: item.kind,kindTone(item.kind),live=item.kind=="agent_run" && item.state in setOf("running","active"))
            Spacer(Modifier.weight(1f))
            Text(taskStates[item.state] ?: item.state,fontSize=12.sp,color=AtollColors.Muted)
        }
        WorkTitle(item.title)
        Row(verticalAlignment=Alignment.CenterVertically) {
            Text(item.assigneeActorIds.joinToString("、") { names[it] ?: it }.ifEmpty { "未指定" },Modifier.weight(1f),fontSize=13.sp,color=AtollColors.Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
            if (item.localScope=="this_device") Text(if(item.kind=="automation") "本次会话" else "本设备",fontSize=12.sp,color=AtollColors.Muted)
            Text(" ›",fontSize=16.sp,color=AtollColors.Faint)
        }
    }
}

/** Can also be mounted directly under a human.approve/human.ask timeline turn. */
@Composable
fun ApprovalCard(item: TaskFact,names: Map<String,String> = emptyMap(),busy: Boolean=false,error: String="",onResolve: (action: String,text: String,note: String)->Unit) {
    var answer by remember(item.key) { mutableStateOf("") };var note by remember(item.key) { mutableStateOf("") }
    val ask=(item.turn?.request?.type ?: item.diagnostic.text("requestType"))=="human.ask"
    var receipt by remember(item.turn) { mutableStateOf<ApprovalReceipt?>(null) }
    LaunchedEffect(item.turn) {
        receipt = withContext(Dispatchers.Default) { approvalReceipt(item.turn) }
    }
    val open=item.actionableBySelf && item.state=="waiting"
    Column(Modifier.fillMaxWidth().padding(start=18.dp,top=4.dp,bottom=8.dp).clip(AtollShapes.Card).background(AtollColors.Surface)
        .border(if (open) 1.5.dp else 1.dp,if (open) AtollColors.WarningLine else AtollColors.Line,AtollShapes.Card)) {
        Row(Modifier.fillMaxWidth().background(if (open) AtollColors.WarningSurface else AtollColors.Raised).padding(horizontal=14.dp,vertical=10.dp),
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.VerifiedUser,null,Modifier.size(16.dp),tint=AtollColors.Warning)
            Text("${names[item.requesterActorId] ?: item.requesterActorId} ${if (ask) "向你提问" else "请你审批"}",Modifier.weight(1f),fontSize=13.sp,
                fontWeight=FontWeight.SemiBold,color=AtollColors.WarningInk,maxLines=1,overflow=TextOverflow.Ellipsis)
            Text(taskStates[item.state] ?: item.state,fontSize=12.sp,color=AtollColors.Warning)
        }
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(item.title,style=MaterialTheme.typography.titleMedium)
            item.turn?.request?.body?.text("text")?.takeIf { it.isNotEmpty() && it!=item.title }?.let { Text(it,fontSize=15.sp,lineHeight=22.sp) }
            item.description.takeIf(String::isNotEmpty)?.let { Text(it,fontSize=15.sp,lineHeight=22.sp,color=AtollColors.Body) }
            if (item.diagnostic.text("impact").isNotEmpty()) Text("影响：${item.diagnostic.text("impact")}",fontSize=14.sp,color=AtollColors.Body)
            if (item.dueAt.isNotEmpty()) Text("到期：${taskDate(item.dueAt)}",fontSize=12.sp,color=AtollColors.Muted)
            if (open) {
                if (ask) OutlinedTextField(answer,{ answer=it },label={ Text("回答") },modifier=Modifier.fillMaxWidth(),enabled=!busy,minLines=2,shape=MaterialTheme.shapes.medium)
                else OutlinedTextField(note,{ note=it },label={ Text("备注（可选）") },modifier=Modifier.fillMaxWidth(),enabled=!busy,shape=MaterialTheme.shapes.medium)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    if (!ask && TaskAction.REJECT in item.actions) AtollButton("拒绝",{ onResolve(TaskAction.REJECT,"",note) },Modifier.weight(1f),kind=ButtonKind.Secondary,enabled=!busy)
                    if (TaskAction.APPROVE in item.actions) AtollButton(if (busy) "正在提交…" else if (ask) "提交回答" else "批准",{ onResolve(TaskAction.APPROVE,answer,note) },Modifier.weight(1f),enabled=!busy)
                }
            }
            receipt?.let { response ->
                HorizontalDivider(color=AtollColors.LineSoft)
                Text("已回执",fontSize=13.sp,fontWeight=FontWeight.SemiBold)
                if (response.unavailable) Text("终态详情不可用，请刷新或重新进入频道。",fontSize=13.sp,color=AtollColors.Muted)
                else {
                    Text("处理者：${names[response.resolverId] ?: response.resolverId}" + response.decision.takeIf(String::isNotEmpty)?.let { " · $it" }.orEmpty(),fontSize=13.sp,color=AtollColors.Muted)
                    if (response.text.isNotEmpty()) Markdown("approval:${item.key}:response",response.text)
                    response.result?.let { StructuredResult(it) }
                }
            }
            if (error.isNotEmpty()) Text(error,fontSize=13.sp,color=AtollColors.DangerText)
        }
    }
}

@Composable
fun TaskDetail(item: TaskFact,viewModel: TasksViewModel,modifier: Modifier=Modifier,onClose: ()->Unit,onSource: (JsonObject)->Unit={},onTurn: (String)->Unit={}) {
    val state by viewModel.state.collectAsState()
    var diagnostic by remember(item.key) { mutableStateOf(false) }
    TaskDialog(item.title,onClose,modifier) {
        LazyColumn(Modifier.fillMaxWidth().weight(1f,false),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                StatusPill(taskKinds[item.kind] ?: item.kind,kindTone(item.kind))
                Text(taskStates[item.state] ?: item.state,fontSize=13.sp,color=AtollColors.Muted)
                if (item.localScope=="this_device") Text(if(item.kind=="automation") "本次会话记录" else "本设备记录",fontSize=13.sp,color=AtollColors.Muted)
            } }
            item { GroupCard {
                GroupRow("负责人",value=item.assigneeActorIds.joinToString("、") { state.actorNames[it] ?: it }.ifEmpty { "未指定" })
                GroupRow("创建者",value=state.actorNames[item.requesterActorId] ?: item.requesterActorId)
                GroupRow("截止/触发",value=taskDate(item.dueAt))
                GroupRow("事实来源",value=when { item.provenance=="ledger" -> "频道账本";item.kind=="automation" -> "本次会话记录";else -> "本设备持久记录" },divider=false)
            } }
            if (item.description.isNotEmpty() && item.kind != "approval") item { Text(item.description,fontSize=15.sp,lineHeight=23.sp) }
            if (item.waitingFor.isNotEmpty()) item { Text("当前等待：${item.waitingFor}",fontSize=14.sp,color=AtollColors.Warning) }
            when (item.kind) {
                "approval" -> item {
                    val commands=item.actions.mapNotNull { state.commandStates["${item.key}:$it"] }
                    ApprovalCard(item,state.actorNames,commands.any { it.state in setOf("submitting","disabled") },commands.firstOrNull { it.state=="failed" }?.error.orEmpty()) { action,text,note -> viewModel.perform { execute(item,action,note,text) } }
                }
                "agent_run" -> item { AtollButton("打开完整回合与控制",{ onTurn(item.requestId.ifEmpty { item.id }) },Modifier.fillMaxWidth(),kind=ButtonKind.Secondary) }
                "recovery" -> item { NoticeBanner(if (item.state=="uncertain") "发送结果尚未由频道账本确认。" else "上一次提交明确失败，使用原编号重试。",if (item.state=="uncertain") Tone.Waiting else Tone.Danger) }
                "automation" -> item { Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text("消息类型：${item.diagnostic.text("msgType")}",fontSize=14.sp)
                    SelectionContainer { Text(item.diagnostic["payload"]?.toString().orEmpty(),Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(AtollColors.Code).padding(10.dp),fontFamily=FontFamily.Monospace,fontSize=12.sp) }
                } }
                "task" -> item { Text("任务编号：${item.nativeId}\n执行者：${state.actorNames[item.diagnostic.text("providerActorId")] ?: item.diagnostic.text("providerActorId")}",fontSize=14.sp,lineHeight=21.sp) }
            }
            if (item.kind!="approval") item { Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                if (item.actions.isEmpty()) Text("当前事实没有声明可用操作。",fontSize=13.sp,color=AtollColors.Muted)
                item.actions.forEach { action ->
                    val command=state.commandStates["${item.key}:$action"]
                    val supported=action in setOf(TaskAction.CANCEL,TaskAction.RETRY,TaskAction.CANCEL_TIMER)
                    AtollButton(actionLabels[action] ?: action,{ viewModel.perform { execute(item,action) } },Modifier.fillMaxWidth(),
                        kind=if (action==TaskAction.RETRY) ButtonKind.Primary else ButtonKind.Secondary,
                        enabled=supported && (state.writable || action==TaskAction.RETRY || action==TaskAction.CANCEL && item.submission!=null) && command?.state !in setOf("submitting","disabled"))
                    if (command?.state=="failed") Text(command.error,fontSize=13.sp,color=AtollColors.DangerText)
                }
            } }
            item { Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                TextButton(onClick={ onSource(item.source) }) { Text("返回来源",color=AtollColors.Lagoon) }
                TextButton(onClick={ diagnostic=!diagnostic }) { Text("诊断信息",color=AtollColors.Muted) }
            } }
            if (diagnostic) item { SelectionContainer { Text(item.diagnostic.toString(),Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(AtollColors.Code).padding(10.dp),fontFamily=FontFamily.Monospace,fontSize=12.sp) } }
        }
    }
}
/** Bottom-anchored sheet with a title and 关闭; content scrolls and the keyboard pushes it up. */
@Composable internal fun TaskDialog(title: String,onClose: ()->Unit,modifier: Modifier=Modifier,busy: Boolean=false,content: @Composable ColumnScope.()->Unit) {
    Dialog(onDismissRequest={ if (!busy) onClose() },properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        Box(Modifier.fillMaxSize().safeDrawingPadding(),contentAlignment=Alignment.BottomCenter) {
            Surface(modifier.fillMaxWidth().heightIn(max=760.dp),shape=AtollShapes.Sheet,color=AtollColors.Ground) {
                Column(Modifier.imePadding()) {
                    SheetHeader(title,{ if (!busy) onClose() })
                    content()
                }
            }
        }
    }
}
@Composable
fun ActivityCenterScreen(snapshot: ActivitySnapshot,modifier: Modifier=Modifier,onSource: (JsonObject)->Unit={},
    approvalCommands: Map<String,TaskCommandState> = emptyMap(), onResolve: ((ActivityItem,String)->Unit)? = null) {
    var operations by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize().background(AtollColors.Ground)) {
        SegmentedControl(listOf("活动 ${snapshot.activities.size}","操作 ${snapshot.operations.size}"),if (operations) 1 else 0,{ operations=it==1 },
            Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp),fill=true)
        val rows=if (operations) snapshot.operations else snapshot.activities
        LazyColumn(Modifier.weight(1f).fillMaxWidth(),contentPadding=PaddingValues(12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            if (operations && snapshot.operationsUnavailable) item { NoticeBanner("当前没有可用的进行中操作快照",Tone.Neutral) }
            items(rows,key={ it.key }) { row ->
                val approval = row.approval?.takeIf { it.diagnostic.text("requestType") == "human.approve" }
                val command = approval?.let { approvalCommands[it.key] }
                Column(Modifier.fillMaxWidth().clip(AtollShapes.Card).background(AtollColors.Surface).border(1.dp,AtollColors.Line,AtollShapes.Card)
                    .clickable { onSource(row.source) }.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        StatusPill(taskKinds[row.kind] ?: "操作",kindTone(row.kind),live=row.state in setOf("running","active") && row.kind=="agent_run")
                        Text("# ${row.channelName}",Modifier.weight(1f),fontSize=12.sp,color=AtollColors.Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
                        if (command?.state != "disabled") Text(taskStates[row.state] ?: row.state,fontSize=12.sp,color=AtollColors.Muted)
                    }
                    WorkTitle(row.title)
                    if (row.detail.isNotEmpty()) Text(row.detail,fontSize=14.sp,color=AtollColors.Body,maxLines=2,overflow=TextOverflow.Ellipsis)
                    if (approval != null) ActivityApprovalActions(row, approval, command, onResolve) { onSource(row.source) }
                    else Text("返回来源 ›",fontSize=13.sp,fontWeight=FontWeight.Medium,color=AtollColors.Lagoon)
                }
            }
            if (rows.isEmpty() && !(operations && snapshot.operationsUnavailable)) item { EmptyNote("暂无活动") }
        }
    }
}

@Composable
private fun ActivityApprovalActions(row: ActivityItem, item: TaskFact, command: TaskCommandState?,
    onResolve: ((ActivityItem,String)->Unit)?, onDetails: ()->Unit) {
    val handled = command?.state == "disabled" || item.state in setOf("completed", "failed", "cancelled", "uncertain")
    val busy = command?.state == "submitting"
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (handled) Text("已处理", fontSize = 14.sp, color = AtollColors.Muted)
        else if (item.state == "waiting") {
            AtollButton("拒绝", { onResolve?.invoke(row, TaskAction.REJECT) }, kind = ButtonKind.Secondary, height = 40.dp,
                enabled = onResolve != null && TaskAction.REJECT in item.actions && !busy)
            AtollButton("批准", { onResolve?.invoke(row, TaskAction.APPROVE) }, height = 40.dp, busy = busy,
                enabled = onResolve != null && TaskAction.APPROVE in item.actions)
        } else Text(taskStates[item.state] ?: item.state, fontSize = 14.sp, color = AtollColors.Muted)
        Spacer(Modifier.weight(1f))
        TextButton(onDetails, Modifier.height(40.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
            Text("详情", fontSize = 14.sp, color = AtollColors.Muted)
        }
    }
    if (!handled && command?.error?.isNotEmpty() == true) Text(command.error, fontSize = 13.sp, color = AtollColors.Danger)
}

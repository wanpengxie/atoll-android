package dev.atoll.app.tasks

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.atoll.app.ui.theme.AtollColors
import kotlinx.coroutines.delay
import java.time.Instant
import kotlinx.serialization.json.*
import java.time.LocalDateTime
import java.time.ZoneId

@Composable
fun CreateTaskDialog(viewModel: TasksViewModel,onClose: ()->Unit) {
    val state by viewModel.state.collectAsState()
    val context=LocalContext.current
    var title by remember { mutableStateOf("") };var description by remember { mutableStateOf("") }
    var provider by remember { mutableStateOf(state.providers.firstOrNull()?.actorId.orEmpty()) }
    var due by remember { mutableStateOf<LocalDateTime?>(null) };var menu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) };var error by remember { mutableStateOf("") }
    TaskDialog("新建任务",onClose,busy=busy) {
        LazyColumn(Modifier.fillMaxWidth().weight(1f,false),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            state.source?.let { source -> item { Text("来源：动态 #${source.number("seq").takeIf { it>0 }?.toString() ?: source.text("objectId")}") } }
            item { OutlinedTextField(title,{ title=it },label={ Text("任务内容") },modifier=Modifier.fillMaxWidth(),enabled=!busy,minLines=3,maxLines=6) }
            item { OutlinedTextField(description,{ description=it },label={ Text("补充说明（可选）") },modifier=Modifier.fillMaxWidth(),enabled=!busy,minLines=2,maxLines=4) }
            item { Box { OutlinedButton(onClick={ menu=true },enabled=!busy) { Text(state.providers.firstOrNull { it.actorId==provider }?.name ?: "选择执行者") }
                DropdownMenu(menu,onDismissRequest={ menu=false }) { state.providers.forEach { p -> DropdownMenuItem(text={ Text(p.name) },onClick={ provider=p.actorId;menu=false }) } }
            } }
            item { Row {
                TextButton(onClick={
                    val initial=due ?: LocalDateTime.now()
                    DatePickerDialog(context,{ _,year,month,day -> TimePickerDialog(context,{ _,hour,minute -> due=LocalDateTime.of(year,month+1,day,hour,minute) },initial.hour,initial.minute,true).show() },initial.year,initial.monthValue-1,initial.dayOfMonth).show()
                },enabled=!busy) { Text(due?.toString()?.replace('T',' ') ?: "截止时间（可选）") }
                if (due!=null) TextButton(onClick={ due=null },enabled=!busy) { Text("清除") }
            } }
            if (error.isNotEmpty()) item { Text(error,color=MaterialTheme.colorScheme.error) }
            item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                TextButton(onClick=onClose,enabled=!busy) { Text("取消") }
                Button(onClick={ busy=true;error="";viewModel.perform {
                    try { createTask(provider,title,description,due?.atZone(ZoneId.systemDefault())?.toInstant()?.toString().orEmpty());onClose() }
                    catch (e:Exception) { error=e.message.orEmpty();throw e }
                    finally { busy=false }
                } },enabled=!busy && state.creationReady && title.isNotBlank() && provider.isNotEmpty()) { Text(if (busy) "正在提交…" else "创建任务") }
            } }
        }
    }
}
@Composable
fun TimedActionsDialog(viewModel: TasksViewModel, onClose: () -> Unit) {
    val state by viewModel.state.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    fun perform(block: suspend TasksViewModel.() -> String) {
        if (busy) return
        busy = true; error = ""; notice = ""
        viewModel.perform {
            try { notice = block() }
            catch (e: Exception) { error = e.message.orEmpty(); throw e }
            finally { busy = false }
        }
    }
    TaskDialog("定时动作", onClose, busy = busy) {
        TimedActionsForm(state.timers, state.writable, busy, error, notice,
            onSchedule = { duration, type, payload -> perform { "已安排 ${schedule(duration, type, payload)}" } },
            onCancel = { id -> perform { cancelTimer(id); "已确认取消 $id" } },
            modifier = Modifier.fillMaxWidth().weight(1f, false))
    }
}

/** View-only form; the existing session timer owner remains the only writer. */
@Composable
internal fun TimedActionsForm(timers: List<AutomationRecord>, writable: Boolean, busy: Boolean = false,
    error: String = "", notice: String = "", onSchedule: (Long, String, JsonObject) -> Unit,
    onCancel: (String) -> Unit, modifier: Modifier = Modifier,
    clock: () -> Instant = Instant::now, zone: ZoneId = ZoneId.systemDefault(),
    pickCustom: ((LocalDateTime, (LocalDateTime) -> Unit) -> Unit)? = null) {
    val context = LocalContext.current
    var preset by rememberSaveable { mutableStateOf(SchedulePreset.OneHour) }
    var custom by rememberSaveable { mutableStateOf<LocalDateTime?>(null) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var type by rememberSaveable { mutableStateOf("agent.ask") }
    var payload by rememberSaveable { mutableStateOf("{\"text\":\"定时提醒\"}") }
    var cancelId by rememberSaveable { mutableStateOf("") }
    var validation by remember { mutableStateOf("") }
    val now by produceState(clock(), clock) { while (true) { delay(60_000); value = clock() } }
    val body = remember(payload) { runCatching { Json.parseToJsonElement(payload) as? JsonObject }.getOrNull() }
    val future = runCatching { scheduleDelayMillis(preset, now, zone, custom) }.isSuccess
    fun chooseCustom() {
        val initial = custom ?: clock().atZone(zone).toLocalDateTime().plusHours(1)
        val selected: (LocalDateTime) -> Unit = { custom = it; validation = "" }
        if (pickCustom != null) pickCustom(initial, selected)
        else DatePickerDialog(context, { _, year, month, day ->
            TimePickerDialog(context, { _, hour, minute -> selected(LocalDateTime.of(year, month + 1, day, hour, minute)) },
                initial.hour, initial.minute, true).show()
        }, initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
    }
    Column(modifier.background(AtollColors.Surface)) {
        LazyColumn(Modifier.fillMaxWidth().weight(1f, false).testTag("schedule.form.list"), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item("presets") { SchedulePresetSelector(preset, custom, now, zone,
                { preset = it; validation = "" }, ::chooseCustom, enabled = !busy) }
            item("reminder") {
                OutlinedTextField(body?.text("text").orEmpty(), { text ->
                    payload = JsonObject(body.orEmpty() + ("text" to JsonPrimitive(text))).toString()
                }, label = { Text("提醒内容") }, modifier = Modifier.fillMaxWidth(), enabled = !busy && body != null, minLines = 3, maxLines = 6)
            }
            item("advanced") {
                Column {
                    Text(if (advanced) "高级：消息类型与 JSON 负载 ⌃" else "高级：消息类型与 JSON 负载 ⌄",
                        Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(enabled = !busy) { advanced = !advanced }.padding(vertical = 12.dp), fontSize = 14.sp)
                    if (advanced) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(type, { type = it }, label = { Text("消息类型") }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                        OutlinedTextField(payload, { payload = it }, label = { Text("Payload JSON") }, modifier = Modifier.fillMaxWidth(), enabled = !busy,
                            isError = body == null, minLines = 3, maxLines = 6,
                            supportingText = { if (body == null) Text("Payload 必须是 JSON 对象") })
                        OutlinedTextField(cancelId, { cancelId = it }, label = { Text("取消已知 timer_id") }, modifier = Modifier.fillMaxWidth(), enabled = !busy)
                        TextButton({ onCancel(cancelId.trim()) }, enabled = !busy && writable && cancelId.isNotBlank()) { Text("取消定时动作") }
                    }
                }
            }
            if (error.isNotEmpty() || validation.isNotEmpty()) item("error") { Text(validation.ifEmpty { error }, color = AtollColors.Danger) }
            if (notice.isNotEmpty()) item("notice") { Text(notice, style = MaterialTheme.typography.bodySmall) }
            item("timers-heading") { Text("本次会话已安排", fontSize = 13.sp, color = AtollColors.Muted) }
            items(timers, key = { it.timerId }) { timer ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = AtollColors.Ground)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        WorkTitle(timer.payload.text("text").ifEmpty { timer.msgType })
                        Text("${when (timer.state) { "scheduled" -> "已安排"; "fired" -> "已触发"; else -> "已取消" }} · ${taskDate(timer.dueAt.toString())}", style = MaterialTheme.typography.bodySmall)
                        if (timer.state == "scheduled") TextButton({ onCancel(timer.timerId) }, enabled = !busy && writable) { Text("取消", color = AtollColors.Danger) }
                    }
                }
            }
            if (timers.isEmpty()) item("empty") { Text("本次会话还没有定时记录。", style = MaterialTheme.typography.bodySmall) }
        }
        HorizontalDivider(color = AtollColors.Line)
        Button({
            try {
                val duration = scheduleDelayMillis(preset, clock(), zone, custom)
                requireNotNull(body) { "Payload 必须是 JSON 对象" }
                validation = ""; onSchedule(duration, type.trim(), body)
            } catch (e: IllegalArgumentException) { validation = e.message.orEmpty() }
        }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).heightIn(min = 50.dp),
            enabled = !busy && writable && future && body != null && type.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = AtollColors.Ink)) { Text(if (busy) "正在提交…" else "安排定时动作", fontSize = 16.sp) }
    }
}

/** Mount once at workspace level, so addressed forms do not depend on the Tasks tab. */
@Composable
fun UiFormsHost(controller: UiFormController,actorNames: Map<String,String> = emptyMap(),channelNames: Map<String,String> = emptyMap()) {
    val state by controller.state.collectAsState()
    state.current?.let { form -> UiFormDialog(form,controller,actorNames[form.requester].orEmpty(),channelNames[form.channelId].orEmpty()) }
    if (state.notice.isNotEmpty()) AlertDialog(onDismissRequest=controller::dismissNotice,text={ Text(state.notice) },confirmButton={ TextButton(onClick=controller::dismissNotice) { Text("知道了") } })
}
@Composable
fun UiFormDialog(form: UiForm,controller: UiFormController,requesterName: String="",channelName: String="") {
    val state by controller.state.collectAsState()
    var values by remember(form.id) { mutableStateOf(initialFormValues(form.fields,form.values)) }
    var errors by remember(form.id) { mutableStateOf<Map<String,String>>(emptyMap()) }
    val busy=state.busy.isNotEmpty()
    TaskDialog(form.title,onClose={ controller.cancelAsync(form) },busy=busy) {
        LazyColumn(Modifier.fillMaxWidth().weight(1f,false),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item { Text("${requesterName.ifEmpty { form.requester }} 请你在这块屏上填写${if (channelName.isNotEmpty()) " · $channelName" else ""}",style=MaterialTheme.typography.bodySmall) }
            items(form.fields,key={ it.name }) { field ->
                val value=values[field.name];val error=errors[field.name].orEmpty()
                val label=field.label+if (field.required) " *" else ""
                when (field.control) {
                    "checkbox" -> Row { Checkbox(value==JsonPrimitive(true),onCheckedChange={ values=values+(field.name to JsonPrimitive(it)) },enabled=!busy);Text(label,Modifier.padding(top=12.dp)) }
                    "select" -> {
                        var menu by remember { mutableStateOf(false) }
                        Box { OutlinedButton(onClick={ menu=true },enabled=!busy,modifier=Modifier.fillMaxWidth()) {
                            Text("$label：${field.enum?.firstOrNull { it.toString()==(value as? JsonPrimitive)?.content }?.let { (it as? JsonPrimitive)?.contentOrNull ?: it.toString() } ?: "请选择"}")
                        }
                            DropdownMenu(menu,onDismissRequest={ menu=false }) { field.enum.orEmpty().forEach { option -> DropdownMenuItem(text={ Text((option as? JsonPrimitive)?.contentOrNull ?: option.toString()) },onClick={ menu=false;values=values+(field.name to JsonPrimitive(option.toString())) }) } }
                        }
                    }
                    else -> OutlinedTextField((value as? JsonPrimitive)?.contentOrNull.orEmpty(),onValueChange={ values=values+(field.name to JsonPrimitive(it)) },label={ Text(label) },modifier=Modifier.fillMaxWidth(),enabled=!busy,
                        isError=error.isNotEmpty(),supportingText={ if (error.isNotEmpty()) Text(error) else if (field.description.isNotEmpty()) Text(field.description) },
                        singleLine=field.control!="json",minLines=if (field.control=="json") 3 else 1,maxLines=if (field.control=="json") 7 else 1,
                        keyboardOptions=KeyboardOptions(keyboardType=when (field.control) { "password" -> KeyboardType.Password;"number" -> KeyboardType.Decimal;else -> KeyboardType.Text }),
                        visualTransformation=if (field.secret) PasswordVisualTransformation() else VisualTransformation.None)
                }
                if (error.isNotEmpty() && field.control in setOf("select","checkbox")) Text(error,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
                if (field.secret) {
                    Text("写入 ${form.secret[field.name]}，回复里只有掩码。",style=MaterialTheme.typography.bodySmall)
                    val existing=state.existing[field.name]
                    if (existing?.exists==true) Text("这个 key 已有值，提交会覆盖。",style=MaterialTheme.typography.bodySmall)
                    else if (existing?.error?.isNotEmpty()==true) Text("无法确认这个 key 是否存在：${existing.error}",style=MaterialTheme.typography.bodySmall)
                    else if (existing!=null) Text("这个 key 现在还没有值。",style=MaterialTheme.typography.bodySmall)
                }
            }
            if (state.error.isNotEmpty()) item { Text(state.error,color=MaterialTheme.colorScheme.error) }
            item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                TextButton(onClick={ controller.cancelAsync(form) },enabled=!busy) { Text(if (state.busy=="cancel") "正在取消…" else "取消") }
                Button(onClick={ val validation=validateFormValues(form.fields,values);errors=validation.errors;if (validation.valid) controller.submitAsync(form,validation.values) },enabled=!busy) { Text(if (state.busy=="submit") "正在提交…" else "提交") }
            } }
        }
    }
}

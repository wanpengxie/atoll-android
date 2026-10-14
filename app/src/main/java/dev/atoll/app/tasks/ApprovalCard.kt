package dev.atoll.app.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.atoll.app.ui.components.*
import dev.atoll.app.ui.theme.ChatColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * An approval or question addressed to me. In the timeline it is the narrow ask card (ChatStates ⑫):
 * title line with a red dot and the time left, the request, and a bottom row of buttons; opening it
 * shows the HumanAsk form (note or answer, 拒绝 / 批准). Outside the timeline it always shows the form.
 */
@Composable
fun ApprovalCard(item: TaskFact, names: Map<String, String> = emptyMap(), busy: Boolean = false, error: String = "",
    timeline: Boolean = false, onResolve: (action: String, text: String, note: String) -> Unit) {
    var answer by remember(item.key) { mutableStateOf("") }
    var note by remember(item.key) { mutableStateOf("") }
    var expanded by remember(item.key) { mutableStateOf(!timeline) }
    val ask = (item.turn?.request?.type ?: item.diagnostic.text("requestType")) == "human.ask"
    var receipt by remember(item.turn) { mutableStateOf<ApprovalReceipt?>(null) }
    LaunchedEffect(item.turn) { receipt = withContext(Dispatchers.Default) { approvalReceipt(item.turn) } }
    val open = item.actionableBySelf && item.state == "waiting"
    val requester = names[item.requesterActorId] ?: item.requesterActorId
    val left = remainingLabel(item.dueAt)
    val body = item.turn?.request?.body?.text("text")?.takeIf { it.isNotEmpty() && it != item.title }
    val canReject = !ask && TaskAction.REJECT in item.actions
    val canApprove = TaskAction.APPROVE in item.actions
    Column(Modifier.padding(top = 10.dp).then(if (timeline && !expanded) Modifier.width(248.dp) else Modifier.fillMaxWidth())
        .clip(if (timeline) RoundedCornerShape(4.dp, 12.dp, 12.dp, 12.dp) else RoundedCornerShape(12.dp)).background(ChatColors.Block)) {
        if (timeline && !expanded) {
            Column(Modifier.fillMaxWidth().clickable(onClickLabel = "打开") { expanded = true }.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (open) Box(Modifier.size(6.dp).clip(CircleShape).background(ChatColors.Red))
                    Text(if (ask) "$requester 问你" else "请你审批", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = ChatColors.Ink, maxLines = 1)
                    Text(if (!open) "· ${taskStates[item.state] ?: item.state}" else left?.let { "· 剩 $it" }.orEmpty(), fontSize = 13.sp, color = ChatColors.Gray, maxLines = 1)
                }
                Text(item.title, fontSize = 16.sp, lineHeight = 25.sp, color = ChatColors.Body, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            if (open) Column {
                HorizontalDivider(color = ChatColors.Ground)
                Row(Modifier.fillMaxWidth().height(44.dp)) {
                    if (ask) CardButton("我来回答", true, Modifier.weight(1f)) { expanded = true }
                    else {
                        if (canReject) {
                            CardButton("拒绝", false, Modifier.weight(1f), enabled = !busy) { onResolve(TaskAction.REJECT, "", "") }
                            Box(Modifier.width(1.dp).fillMaxHeight().background(ChatColors.Ground))
                        }
                        if (canApprove) CardButton(if (busy) "提交中…" else "批准", true, Modifier.weight(1f), enabled = !busy) { onResolve(TaskAction.APPROVE, "", "") }
                    }
                }
            }
            receipt?.let { ReceiptLine(it, names, compact = true) }
            if (error.isNotEmpty()) Text(error, Modifier.padding(horizontal = 14.dp, vertical = 6.dp), fontSize = 13.sp, color = ChatColors.Red)
            return@Column
        }
        // HumanAsk form.
        Column(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActorAvatar(requester, item.requesterActorId, agent = !item.requesterActorId.startsWith("human:"), size = 24.dp)
                Text(if (ask) "$requester 向你提问" else "$requester 请你审批", Modifier.weight(1f, fill = false), fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    color = ChatColors.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (open) left?.let { "剩 $it" }.orEmpty() else taskStates[item.state] ?: item.state, Modifier.weight(1f), fontSize = 13.sp, color = ChatColors.Gray)
                if (timeline) Text("收起", Modifier.heightIn(min = 40.dp).clickable { expanded = false }.padding(horizontal = 12.dp).wrapContentHeight(),
                    fontSize = 14.sp, color = ChatColors.Gray)
            }
            Text(item.title, Modifier.padding(horizontal = 16.dp), fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium, color = ChatColors.Ink)
            body?.let { Text(it, Modifier.padding(horizontal = 16.dp), fontSize = 16.sp, lineHeight = 25.sp, color = ChatColors.Body) }
            item.description.takeIf(String::isNotEmpty)?.let { Text(it, Modifier.padding(horizontal = 16.dp), fontSize = 16.sp, lineHeight = 25.sp, color = ChatColors.Body) }
            val facts = listOfNotNull(item.diagnostic.text("impact").takeIf(String::isNotEmpty)?.let { "影响" to it },
                item.dueAt.takeIf { it.isNotEmpty() && left == null }?.let { "到期" to taskDate(it) })
            if (facts.isNotEmpty()) Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                facts.forEach { (label, value) -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(label, fontSize = 14.sp, lineHeight = 20.sp, color = ChatColors.Gray)
                    Text(value, Modifier.weight(1f), fontSize = 14.sp, lineHeight = 20.sp, color = ChatColors.Body)
                } }
            }
            if (open) {
                if (ask) AskField(answer, { answer = it }, "回答 $requester", enabled = !busy, action = if (busy) "提交中…" else "提交",
                    actionEnabled = !busy && answer.isNotBlank() && canApprove) { onResolve(TaskAction.APPROVE, answer, note) }
                else {
                    AskField(note, { note = it }, "备注（可选），说明你的决定", enabled = !busy)
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (canReject) ChatSecondaryButton("拒绝", { onResolve(TaskAction.REJECT, "", note) }, Modifier.weight(1f), enabled = !busy)
                        if (canApprove) ChatPrimaryButton(if (busy) "正在提交…" else "批准", { onResolve(TaskAction.APPROVE, answer, note) }, Modifier.weight(1f), enabled = !busy)
                    }
                }
            }
            receipt?.let { ReceiptLine(it, names, compact = false, key = item.key) }
            if (error.isNotEmpty()) Text(error, Modifier.padding(horizontal = 16.dp), fontSize = 13.sp, color = ChatColors.Red)
        }
    }
}

@Composable
private fun CardButton(label: String, primary: Boolean, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(modifier.fillMaxHeight().clickable(enabled = enabled, role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, fontSize = 15.sp, fontWeight = if (primary) FontWeight.Medium else FontWeight.Normal,
            color = if (!enabled) ChatColors.Hint else if (primary) ChatColors.Link else ChatColors.Body)
    }
}

/** A grey #F7F7F7 field; the field is the only writer of its text. */
@Composable
private fun AskField(value: String, onValue: (String) -> Unit, placeholder: String, enabled: Boolean, action: String? = null,
    actionEnabled: Boolean = false, onAction: () -> Unit = {}) {
    Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth().heightIn(min = 40.dp).clip(RoundedCornerShape(8.dp)).background(ChatColors.Bar)
        .padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(value, onValue, Modifier.weight(1f).padding(vertical = 9.dp).semantics { contentDescription = placeholder }, enabled = enabled,
            textStyle = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, color = ChatColors.Body), cursorBrush = SolidColor(ChatColors.Green),
            decorationBox = { inner -> Box { if (value.isEmpty()) Text(placeholder, fontSize = 16.sp, lineHeight = 22.sp, color = ChatColors.Hint); inner() } })
        if (action != null) Text(action, Modifier.heightIn(min = 40.dp).clickable(enabled = actionEnabled, role = Role.Button, onClick = onAction)
            .padding(start = 12.dp, end = 12.dp).wrapContentHeight(), fontSize = 16.sp, fontWeight = FontWeight.Medium,
            color = if (actionEnabled) ChatColors.Link else ChatColors.Hint)
        else Spacer(Modifier.width(12.dp))
    }
}

@Composable
private fun ReceiptLine(response: ApprovalReceipt, names: Map<String, String>, compact: Boolean, key: String = "") {
    Column(Modifier.fillMaxWidth().padding(horizontal = if (compact) 14.dp else 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (response.unavailable) Text("已回执 · 终态详情不可用，请刷新或重新进入频道。", fontSize = 13.sp, color = ChatColors.Gray)
        else {
            Text("已回执 · 处理者：${names[response.resolverId] ?: response.resolverId}" + response.decision.takeIf(String::isNotEmpty)?.let { " · $it" }.orEmpty(),
                fontSize = 13.sp, color = ChatColors.Gray)
            if (!compact) {
                if (response.text.isNotEmpty()) Markdown("approval:$key:response", response.text)
                response.result?.let { StructuredResult(it) }
            }
        }
    }
}

/** “14 分钟” / “2 小时” until the due time; null when there is none or it has passed. */
internal fun remainingLabel(dueAt: String, now: Long = System.currentTimeMillis()): String? = runCatching {
    val due = dueAt.toLongOrNull() ?: Instant.parse(dueAt).toEpochMilli()
    val minutes = (due - now) / 60_000
    when { minutes < 0 -> null; minutes < 1 -> "不到 1 分钟"; minutes < 120 -> "$minutes 分钟"; minutes < 48 * 60 -> "${minutes / 60} 小时"; else -> "${minutes / 1440} 天" }
}.getOrNull()

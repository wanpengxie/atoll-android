package dev.atoll.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CallMerge
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontFamily
import dev.atoll.app.ui.screens.MessageViewState
import dev.atoll.app.ui.screens.TurnKind
import dev.atoll.app.ui.theme.ChatColors
import kotlinx.serialization.json.JsonObject

internal fun showTurnWorkStrip(message: MessageViewState) =
    message.trail?.let { !hasResendFooter(message) || it.running || it.records.isNotEmpty() } == true || message.subTasks.isNotEmpty() ||
        message.relatedCalls.isNotEmpty() || message.progress != null || (message.status != null && !hasResendFooter(message))
internal fun showTurnProcessLink(message: MessageViewState) = message.status != null && !hasResendFooter(message)

@Composable
fun TurnFailureSummary(result: ResultViewState) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(failureTitle(result.errorCode.orEmpty()), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = ChatColors.Red)
        if (result.detail.isNotEmpty()) Text(result.detail, fontSize = 13.sp, lineHeight = 19.sp, color = ChatColors.Body)
        Text(result.errorCode.orEmpty(), fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = ChatColors.Gray)
        result.errorData?.takeUnless { it is JsonObject && it.isEmpty() }?.let { data ->
            StructuredResult(ResultViewState(value = data, title = "错误详情数据"))
        }
    }
}

@Composable
fun EditedTag() = Text("已编辑", Modifier.clip(RoundedCornerShape(5.dp)).background(ChatColors.Bar)
    .padding(horizontal = 6.dp, vertical = 1.dp), fontSize = 12.sp, color = ChatColors.Gray)

internal fun hasResendFooter(message: MessageViewState) = message.turnState?.kind == TurnKind.Failed && message.turnState.resendable
internal fun turnBottomPadding(message: MessageViewState) = if (hasResendFooter(message)) 0.dp else 7.dp
internal fun turnBottomShape(message: MessageViewState, default: Shape): Shape = if (hasResendFooter(message)) RectangleShape else default

@Composable
fun TurnFailureActions(message: MessageViewState, onResend: () -> Unit, enabled: Boolean, onProcess: ((String) -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(bottom = 7.dp).clip(RoundedCornerShape(bottomStart = 18.dp, bottomEnd = 18.dp))
        .background(ChatColors.Block).padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onResend, enabled = enabled, modifier = Modifier.heightIn(min = 44.dp), shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, ChatColors.Line), contentPadding = PaddingValues(horizontal = 12.dp)) { Text("重新发送", fontSize = 13.sp) }
        onProcess?.let { open -> TextButton({ open(message.turnState?.requestId.orEmpty()) }, Modifier.heightIn(min = 44.dp)) {
            Text("查看过程", fontSize = 13.sp, color = ChatColors.Gray)
        } }
    }
}

/** TurnStates: one compact row; its original body remains available to copy/process. */
@Composable
fun TurnStateRow(message: MessageViewState, onProcess: (String) -> Unit, onResend: (() -> Unit)? = null,
    resendEnabled: Boolean = true) {
    val state = message.turnState ?: return
    val merged = state.kind in setOf(TurnKind.Merged, TurnKind.Preempted)
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(if (merged) 14.dp else 18.dp))
        .background(if (merged) ChatColors.LineInWhite else ChatColors.Block).then(if (state.kind == TurnKind.Lost) Modifier.drawBehind {
            val stroke = 1.dp.toPx(); val radius = 18.dp.toPx()
            drawRoundRect(Color(0xFFC4C4BC), Offset(stroke / 2, stroke / 2),
                Size(size.width - stroke, size.height - stroke), CornerRadius(radius, radius),
                style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
        } else Modifier).padding(horizontal = 14.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (merged) Icon(Icons.Outlined.CallMerge, null, Modifier.size(15.dp), tint = ChatColors.Body)
        else ActorAvatar(message.recipient ?: message.sender, state.receiverId.ifEmpty { message.recipient ?: message.sender }, agent = true, size = 22.dp)
        if (message.self && state.edited) EditedTag()
        val text = when (state.kind) {
            TurnKind.Merged -> "“${message.text}”已并入 ${message.recipient.orEmpty()} 的当前回合"
            TurnKind.Preempted -> "“${message.text}”已被新的回合抢占"
            else -> message.text
        }
        Text(text, Modifier.weight(1f), fontSize = if (merged) 13.sp else 14.sp,
            color = ChatColors.Body, maxLines = 1, overflow = TextOverflow.Ellipsis)
        when (state.kind) {
            TurnKind.Merged, TurnKind.Preempted -> TextButton({ onProcess(state.targetId) }, Modifier.heightIn(min = 44.dp)) {
                Text("查看 ›", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = ChatColors.Link)
            }
            TurnKind.Stopped -> Text("已停止 · 发消息即继续", fontSize = 12.sp, color = ChatColors.Gray)
            TurnKind.Lost -> {
                Text(if (state.lostReason == "restart") "未完成 · Agent 已重启" else "未完成 · 已过期",
                    fontSize = 12.sp, color = Color(0xFF6B4A12))
                onResend?.let { callback -> TextButton(callback, Modifier.heightIn(min = 44.dp), enabled = resendEnabled) {
                    Text("重发", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                } }
            }
            else -> Unit
        }
    }
}

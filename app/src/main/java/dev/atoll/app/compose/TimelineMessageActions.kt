@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package dev.atoll.app.compose

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.atoll.app.core.Envelope
import dev.atoll.app.model.Turn
import dev.atoll.app.ui.screens.MessageViewState
import dev.atoll.app.ui.screens.MessagePart
import dev.atoll.app.ui.screens.TurnKind
import dev.atoll.app.ui.components.TurnStateRow
import dev.atoll.app.ui.components.TurnFailureActions
import dev.atoll.app.ui.theme.AtollColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Mount in ChannelScreen.messageContent around its existing content, including approval cards.
 * Touch: swipe right to reply, long-press for every action. No buttons sit under each message;
 * the same actions are exposed to accessibility services as custom actions.
 */
@Composable
fun TimelineMessageActions(message: MessageViewState, envelope: Envelope, turn: Turn?,
    selfId: String, roster: List<ComposerActor>, writable: Boolean, online: Boolean,
    newerUnloaded: Boolean, safeEditActors: Set<String>, editingTargetId: String = "",
    onReply: (ReplyTarget) -> Unit, onEdit: (String) -> Unit,
    onCreateTask: ((Envelope, Turn?) -> Unit)? = null,
    onStop: ((String) -> Unit)? = null, busyActors: Set<String> = emptySet(),
    starred: Boolean = false, onToggleStar: ((Envelope, String) -> Unit)? = null,
    onOpenProcess: ((String) -> Unit)? = null, onResend: (() -> Unit)? = null, resendEnabled: Boolean = true,
    content: @Composable () -> Unit) {
    val actions = TimelineActionPolicy.actions(envelope, turn, message.text, selfId, roster,
        writable, online, newerUnloaded, safeEditActors)
    var menu by remember(message.id) { mutableStateOf(false) }
    var copied by remember(message.id) { mutableStateOf("") }
    var feedback by remember(message.id) { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val copyText = remember(message.text, message.result) { messageCopyText(message) }
    fun copy() {
        feedback?.cancel()
        copied = try { clipboard.setText(AnnotatedString(copyText)); "✓ 已复制" } catch (_: Exception) { "复制失败" }
        feedback = scope.launch { delay(1600); copied = "" }
    }
    val canStar = onToggleStar != null && envelope.id.isNotEmpty() && envelope.channelId.isNotEmpty()
    val canStop = actions.stopTargetId.isNotEmpty() && onStop != null
    val stopEnabled = editingTargetId.isEmpty() && turn?.request?.audience?.firstOrNull() !in busyActors
    val hasMenu = copyText.isNotEmpty() || canStar || actions.reply != null || actions.editTargetId.isNotEmpty() || onCreateTask != null || canStop
    val reply = actions.reply
    val threshold = with(LocalDensity.current) { 64.dp.toPx() }
    var drag by remember(message.id) { mutableFloatStateOf(0f) }
    val shift by animateFloatAsState(drag, label = "reply-swipe")
    val accessibility = buildList {
        if (copyText.isNotEmpty()) add(CustomAccessibilityAction("复制") { copy(); true })
        if (reply != null) add(CustomAccessibilityAction("回复") { onReply(reply); true })
        if (canStar) add(CustomAccessibilityAction(if (starred) "取消星标" else "星标") { onToggleStar?.invoke(envelope, message.text); true })
        if (canStop && stopEnabled) add(CustomAccessibilityAction("停止") { onStop?.invoke(actions.stopTargetId); true })
    }
    Column(Modifier.testTag("atoll.message.${envelope.id}").semantics {
        text = AnnotatedString(message.text)
        if (accessibility.isNotEmpty()) customActions = accessibility
    }.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth()) {
            if (reply != null && shift > 8f) Icon(Icons.AutoMirrored.Outlined.Reply, null,
                Modifier.align(Alignment.CenterStart).padding(start = 12.dp).size(22.dp)
                    .graphicsLayer { alpha = (shift / threshold).coerceIn(0f, 1f) }, tint = AtollColors.Lagoon)
            Box(Modifier.fillMaxWidth().graphicsLayer { translationX = shift }
                .then(if (reply != null) Modifier.pointerInput(reply) {
                    detectHorizontalDragGestures(
                        onDragEnd = { if (drag >= threshold) onReply(reply); drag = 0f },
                        onDragCancel = { drag = 0f },
                    ) { change, amount ->
                        val next = (drag + amount).coerceIn(0f, threshold * 1.4f)
                        if (next != drag) { drag = next; change.consume() }
                    }
                } else Modifier)
                .then(if (hasMenu) Modifier.combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                    onClick = {}, onLongClickLabel = "消息操作", onLongClick = { menu = true }) else Modifier)) {
                if (message.turnState?.compact == true) TurnStateRow(message, onOpenProcess ?: {}, onResend, resendEnabled)
                else content()
                DropdownMenu(menu, { menu = false }) {
                    if (copyText.isNotEmpty()) DropdownMenuItem(text = { Text("复制", fontSize = 15.sp) }, onClick = { menu = false; copy() })
                    if (reply != null) DropdownMenuItem(text = { Text("回复", fontSize = 15.sp) }, onClick = { menu = false; onReply(reply) })
                    if (canStar) DropdownMenuItem(text = { Text(if (starred) "取消星标" else "星标", fontSize = 15.sp) },
                        onClick = { menu = false; onToggleStar?.invoke(envelope, message.text) })
                    if (actions.editTargetId.isNotEmpty()) DropdownMenuItem(text = { Text("编辑", fontSize = 15.sp) },
                        enabled = editingTargetId.isEmpty(), onClick = { menu = false; onEdit(actions.editTargetId) })
                    if (onCreateTask != null) DropdownMenuItem(text = { Text("从这里新建任务", fontSize = 15.sp) },
                        enabled = writable && online, onClick = { menu = false; onCreateTask(envelope, turn) })
                    if (canStop) DropdownMenuItem(text = { Text("停止", fontSize = 15.sp, color = AtollColors.Danger) },
                        enabled = stopEnabled, onClick = { menu = false; onStop?.invoke(actions.stopTargetId) })
                }
            }
        }
        if (message.turnState?.kind == TurnKind.Failed && message.part != MessagePart.Request && onResend != null) {
            TurnFailureActions(message, onResend, resendEnabled, onOpenProcess)
        }
        if (editingTargetId.isNotEmpty() && envelope.id == editingTargetId) {
            Text("正在输入框中编辑", Modifier.padding(horizontal = 16.dp, vertical = 2.dp), fontSize = 12.sp, color = AtollColors.LagoonText)
        }
        if (starred || copied.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (starred) Text("★ 已星标", fontSize = 12.sp, color = AtollColors.Warning)
            if (copied.isNotEmpty()) Text(copied, fontSize = 12.sp, color = AtollColors.Muted)
        }
    }
}

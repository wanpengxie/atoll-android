@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package dev.atoll.app.compose

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onPlaced
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
import dev.atoll.app.ui.components.CompactTurnRow
import dev.atoll.app.ui.components.LocalTurnResend
import dev.atoll.app.ui.components.TurnResend
import dev.atoll.app.ui.theme.ChatColors
import dev.atoll.app.ui.components.LocalMarkdownSelectionEnabled
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
    // Read only when the menu opens; holding coordinates writes no state while scrolling.
    val anchor = remember(message.id) { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
    val popoverRoom = with(LocalDensity.current) { 220.dp.toPx() }
    var selecting by remember(message.id) { mutableStateOf(false) }
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
    val processId = messageProcessId(message, envelope, turn)
    val canOpenProcess = onOpenProcess != null && processId.isNotEmpty()
    val hasMenu = canOpenProcess || copyText.isNotEmpty() || canStar || actions.reply != null || actions.editTargetId.isNotEmpty() || onCreateTask != null || canStop
    val reply = actions.reply
    val threshold = with(LocalDensity.current) { 64.dp.toPx() }
    var drag by remember(message.id) { mutableFloatStateOf(0f) }
    val shift by animateFloatAsState(drag, label = "reply-swipe")
    val accessibility = buildList {
        if (hasMenu) add(CustomAccessibilityAction("消息操作") { menu = true; true })
        if (canOpenProcess) add(CustomAccessibilityAction("查看过程") { onOpenProcess?.invoke(processId); true })
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
                    .graphicsLayer { alpha = (shift / threshold).coerceIn(0f, 1f) }, tint = ChatColors.Gray)
            Box(Modifier.fillMaxWidth().graphicsLayer { translationX = shift }
                .then(if (hasMenu) Modifier.onPlaced { anchor[0] = it } else Modifier)
                .drawWithContent { drawContent(); if (menu) drawRect(Color.Black.copy(alpha = .06f)) }
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
                if (message.turnState?.compact == true) CompactTurnRow(message, onOpenProcess ?: {}, onResend, resendEnabled)
                else CompositionLocalProvider(LocalMarkdownSelectionEnabled provides false,
                    LocalTurnResend provides onResend?.takeIf { message.turnState?.kind == TurnKind.Failed }?.let { TurnResend(it, resendEnabled) }) { content() }
                // Inside the message box, so the popover anchors to this message rather than the list.
                if (menu) {
                    fun runAction(action: () -> Unit): () -> Unit = { menu = false; action() }
                    val items = buildList {
                        if (copyText.isNotEmpty()) add(MessageAction("复制", Icons.Outlined.ContentCopy, onClick = runAction(::copy)))
                        if (reply != null) add(MessageAction(if (reply.senderId.startsWith("agent:")) "追问" else "引用", Icons.AutoMirrored.Outlined.Reply,
                            onClick = runAction { onReply(reply) }))
                        if (canStar) add(MessageAction(if (starred) "取消星标" else "星标", if (starred) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                            onClick = runAction { onToggleStar?.invoke(envelope, message.text) }))
                        if (copyText.isNotEmpty()) add(MessageAction("选择文本", Icons.Outlined.Notes) { menu = false; selecting = true })
                        if (onCreateTask != null) add(MessageAction("新建任务", Icons.Outlined.AddTask, enabled = writable && online,
                            onClick = runAction { onCreateTask(envelope, turn) }))
                        if (canOpenProcess) add(MessageAction("查看过程", Icons.Outlined.FormatListBulleted, onClick = runAction { onOpenProcess?.invoke(processId) }))
                        if (actions.editTargetId.isNotEmpty()) add(MessageAction("编辑", Icons.Outlined.Edit, enabled = editingTargetId.isEmpty(),
                            onClick = runAction { onEdit(actions.editTargetId) }))
                        if (canStop) add(MessageAction("中断", Icons.Outlined.StopCircle, enabled = stopEnabled, onClick = runAction { onStop?.invoke(actions.stopTargetId) }))
                    }
                    val top = anchor[0]?.takeIf { it.isAttached }?.boundsInWindow()?.top ?: 0f
                    MessageActionPopover(items, above = top > popoverRoom, onDismiss = { menu = false })
                }
            }
        }
        val alignment = if (message.self) Arrangement.End else Arrangement.Start
        if (editingTargetId.isNotEmpty() && envelope.id == editingTargetId) Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = alignment) {
            Text("正在输入框中编辑", fontSize = 12.sp, color = ChatColors.Gray)
        }
        if (starred || copied.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp, if (message.self) Alignment.End else Alignment.Start)) {
            if (starred) Text("★ 已星标", fontSize = 12.sp, color = ChatColors.Gray)
            if (copied.isNotEmpty()) Text(copied, fontSize = 12.sp, color = ChatColors.Gray)
        }
    }
    if (selecting) MessageTextSelectionSheet(copyText, onDismiss = { selecting = false })
}

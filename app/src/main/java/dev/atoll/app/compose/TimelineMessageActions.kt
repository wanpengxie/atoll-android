@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package dev.atoll.app.compose

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.atoll.app.core.Envelope
import dev.atoll.app.model.Turn
import dev.atoll.app.ui.screens.MessageViewState
import dev.atoll.app.ui.theme.AtollColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Mount in ChannelScreen.messageContent around its existing content, including approval cards. */
@Composable
fun TimelineMessageActions(message: MessageViewState, envelope: Envelope, turn: Turn?,
    selfId: String, roster: List<ComposerActor>, writable: Boolean, online: Boolean,
    newerUnloaded: Boolean, safeEditActors: Set<String>, editingTargetId: String = "",
    onReply: (ReplyTarget) -> Unit, onEdit: (String) -> Unit,
    onCreateTask: ((Envelope, Turn?) -> Unit)? = null,
    onStop: ((String) -> Unit)? = null, busyActors: Set<String> = emptySet(),
    content: @Composable () -> Unit) {
    val actions = TimelineActionPolicy.actions(envelope, turn, message.text, selfId, roster,
        writable, online, newerUnloaded, safeEditActors)
    var menu by remember(message.id) { mutableStateOf(false) }
    var copied by remember(message.id) { mutableStateOf("") }
    var feedback by remember(message.id) { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val copyText = message.text.trim()
    fun copy() {
        feedback?.cancel()
        copied = try { clipboard.setText(AnnotatedString(copyText)); "✓ 已复制" } catch (_: Exception) { "复制失败" }
        feedback = scope.launch { delay(1600); copied = "" }
    }
    Column(Modifier.testTag("atoll.message.${envelope.id}").semantics { text = AnnotatedString(message.text) }.fillMaxWidth()) {
        Box(if (copyText.isNotEmpty() || actions.reply != null) Modifier.combinedClickable(
            onClick = { actions.reply?.let(onReply) }, onLongClickLabel = "复制整条消息",
            onLongClick = { if (copyText.isNotEmpty()) copy() }) else Modifier) { content() }
        if (editingTargetId.isNotEmpty() && envelope.id == editingTargetId) {
            Text("正在输入框中编辑", Modifier.padding(horizontal = 16.dp), fontSize = 11.sp, color = AtollColors.Muted)
        }
        if (copyText.isNotEmpty() || actions.reply != null || actions.editTargetId.isNotEmpty() || onCreateTask != null || actions.stopTargetId.isNotEmpty() && onStop != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = if (message.self) Arrangement.End else Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically) {
                if (copyText.isNotEmpty()) TextButton({ copy() }, Modifier.heightIn(min = 44.dp)) {
                    Text(copied.ifEmpty { "复制" }, fontSize = 11.sp, color = AtollColors.Muted)
                }
                actions.reply?.let { reply ->
                    TextButton({ onReply(reply) }, Modifier.heightIn(min = 44.dp)) {
                        Text("↩ 回复", fontSize = 11.sp, color = AtollColors.Muted)
                    }
                }
                if (actions.stopTargetId.isNotEmpty() && onStop != null) TextButton({ onStop(actions.stopTargetId) },
                    Modifier.heightIn(min = 44.dp), enabled = editingTargetId.isEmpty() && turn?.request?.audience?.firstOrNull() !in busyActors) {
                    Text("停止", fontSize = 11.sp, color = AtollColors.Muted)
                }
                if (actions.editTargetId.isNotEmpty() || onCreateTask != null) Box {
                    IconButton({ menu = true }, Modifier.size(44.dp)) {
                        Icon(Icons.Outlined.MoreHoriz, "消息操作", tint = AtollColors.Muted, modifier = Modifier.size(18.dp))
                    }
                    DropdownMenu(menu, { menu = false }) {
                        if (actions.editTargetId.isNotEmpty()) DropdownMenuItem(text = { Text("编辑") },
                            enabled = editingTargetId.isEmpty(), onClick = { menu = false; onEdit(actions.editTargetId) })
                        if (onCreateTask != null) DropdownMenuItem(text = { Text("创建任务") },
                            enabled = writable && online, onClick = { menu = false; onCreateTask(envelope, turn) })
                    }
                }
            }
        }
    }
}

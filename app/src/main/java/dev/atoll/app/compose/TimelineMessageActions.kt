package dev.atoll.app.compose

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.atoll.app.core.Envelope
import dev.atoll.app.model.Turn
import dev.atoll.app.ui.screens.MessageViewState
import dev.atoll.app.ui.theme.AtollColors

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
    Column(Modifier.fillMaxWidth()) {
        content()
        if (editingTargetId.isNotEmpty() && envelope.id == editingTargetId) {
            Text("正在输入框中编辑", Modifier.padding(horizontal = 16.dp), fontSize = 11.sp, color = AtollColors.Muted)
        }
        if (actions.reply != null || actions.editTargetId.isNotEmpty() || onCreateTask != null || actions.stopTargetId.isNotEmpty() && onStop != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = if (message.self) Arrangement.End else Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically) {
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

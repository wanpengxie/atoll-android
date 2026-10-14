package dev.atoll.app.waiting

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.atoll.app.compose.ComposerEditBinding
import dev.atoll.app.ui.components.ActorAvatar
import dev.atoll.app.ui.components.ChatNotice
import dev.atoll.app.ui.components.ChatSheetHeader
import dev.atoll.app.ui.components.ChatSheetShape
import dev.atoll.app.ui.components.ChatSpinner
import dev.atoll.app.ui.theme.ChatColors
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.serialization.json.*

/**
 * Messages that wait for a busy agent (Waiting board). Collapsed: one 36dp grey strip above the composer.
 * Expanded: a bottom sheet with one white group per agent; each row can be inserted, edited or cancelled.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WaitingDock(viewModel: WaitingViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val count = state.groups.sumOf { it.rows.size }
    Column(modifier.fillMaxWidth()) {
        if (state.edit.notice.isNotEmpty() || state.error.isNotEmpty())
            ChatNotice(state.error.ifEmpty { state.edit.notice }, danger = state.error.isNotEmpty(), action = "关闭", onAction = viewModel::clearError)
        val first = state.groups.firstOrNull()
        if (count > 0 && first != null) Column(Modifier.fillMaxWidth().background(ChatColors.Bar)) {
            HorizontalDivider(color = ChatColors.Line)
            Row(Modifier.fillMaxWidth().height(36.dp).clickable(onClickLabel = "展开等待区") { viewModel.setCollapsed(false) }
                .semantics { contentDescription = "$count 条等待消息，展开等待区" }.padding(start = 14.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActorAvatar(first.name, first.actorId, agent = true, size = 16.dp)
                Text("$count 条等待", fontSize = 13.sp, color = ChatColors.Ink)
                Text("· " + (if (state.groups.size > 1) "${state.groups.size} 个 Agent" else first.name) +
                    if (first.paused) " 已暂停 · 恢复后发出" else " 本轮结束后依次发出", Modifier.weight(1f), fontSize = 13.sp, color = ChatColors.Gray,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("›", fontSize = 15.sp, color = ChatColors.Gray)
            }
        }
    }
    if (count > 0 && !state.collapsed) ModalBottomSheet(onDismissRequest = { viewModel.setCollapsed(true) }, containerColor = ChatColors.Bar,
        shape = ChatSheetShape, dragHandle = null, scrimColor = ChatColors.Scrim) {
        ChatSheetHeader("等待区 · $count 条", "收起", { viewModel.setCollapsed(true) }, subtitle = "Agent 忙时，你的消息在这里排队")
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            state.groups.forEach { group ->
                item(key = "group:${group.actorId}") {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)).background(ChatColors.Block)
                        .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ActorAvatar(group.name, group.actorId, agent = true, size = 32.dp)
                        Column(Modifier.weight(1f)) {
                            Text("${group.name} · ${group.rows.size} 条", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = ChatColors.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (!group.paused) ChatSpinner()
                                Text(if (group.paused) "已暂停 · 恢复后发出" else "本轮结束后依次发出", fontSize = 13.sp, color = ChatColors.Gray)
                            }
                        }
                        if (group.rows.any { it.actions.insert != null }) WaitingAction(if (state.groups.size == 1) "全部插入" else "插入 ${group.name} 全部",
                            state.online && !group.busy, ChatColors.Link) { viewModel.insertAll(group.actorId) }
                    }
                }
                itemsIndexed(group.rows, key = { _, row -> row.turn.requestId }) { index, row ->
                    val editing = state.edit.session?.targetId == row.turn.requestId
                    val last = index == group.rows.lastIndex
                    Column(Modifier.testTag("atoll.waiting.${row.turn.requestId}").fillMaxWidth()
                        .then(if (last) Modifier.clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)) else Modifier).background(ChatColors.Block)) {
                        HorizontalDivider(Modifier.padding(start = 16.dp), color = ChatColors.LineInWhite)
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("${index + 1}", fontSize = 13.sp, lineHeight = 23.sp, color = ChatColors.Gray)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(row.turn.exactText().ifEmpty { row.turn.request.type }, fontSize = 16.sp, lineHeight = 23.sp, color = ChatColors.Body,
                                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                                val labels = row.labels + listOfNotNull("正在输入框中编辑".takeIf { editing })
                                if (labels.isNotEmpty()) Text(labels.joinToString(" · "), fontSize = 12.sp, color = ChatColors.Gray)
                                if (!editing) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (row.actions.insert != null) WaitingAction("插入", state.online && !group.busy, ChatColors.Link) { viewModel.insert(row.turn.requestId) }
                                    if (row.actions.canEdit) WaitingAction("编辑", state.online && !group.busy && state.edit.phase == EditPhase.IDLE, ChatColors.Body) {
                                        viewModel.requestEdit(row.turn.requestId); viewModel.setCollapsed(true)
                                    }
                                    if (row.actions.canCancel) WaitingAction("取消", row.localSubmission != null || state.online && !group.busy, ChatColors.Red) { viewModel.cancel(row.turn.requestId) }
                                    row.actions.extras.forEach { control -> WaitingAction(control.label, state.online && !group.busy, ChatColors.Body) { viewModel.extra(row.turn.requestId, control.word) } }
                                }
                            }
                        }
                    }
                }
            }
            val cancellable = state.groups.filter { group -> group.rows.any { it.actions.canCancel } }
            if (cancellable.isNotEmpty()) item(key = "cancel-all") {
                Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.CenterStart) {
                    WaitingAction("全部取消", cancellable.any { group -> state.online && !group.busy || group.rows.any { it.localSubmission != null && it.actions.canCancel } }, ChatColors.Red) {
                        cancellable.forEach { viewModel.cancelAll(it.actorId) }
                    }
                }
            }
        }
    }
    if (state.warningTarget != null) AlertDialog(onDismissRequest = { viewModel.confirmEdit(false) }, title = { Text("确认编辑") }, text = { Text(state.warning) },
        containerColor = ChatColors.Block,
        confirmButton = { TextButton(onClick = { viewModel.confirmEdit(true) }) { Text("继续编辑", color = ChatColors.Link) } },
        dismissButton = { TextButton(onClick = { viewModel.confirmEdit(false) }) { Text("取消", color = ChatColors.Ink) } })
}

/** Text actions, 14sp, 44dp tall: 插入 link, 编辑 ink, 取消 red. */
@Composable
private fun WaitingAction(label: String, enabled: Boolean, color: Color, onClick: () -> Unit) {
    Text(label, Modifier.heightIn(min = 44.dp).widthIn(min = 44.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 4.dp).wrapContentHeight(), fontSize = 14.sp, color = if (enabled) color else ChatColors.Hint, maxLines = 1)
}

/** Read state in the host, pass this to AtollComposer(editing = ...). */
fun WaitingViewModel.editorBinding(state: WaitingUiState): ComposerEditBinding? {
    val session = state.edit.session ?: return null
    return ComposerEditBinding(session.id, state.groups.find { it.actorId == session.actorId }?.name ?: session.actorId,
        session.text, session.command, state.edit.phase == EditPhase.EDITING, state.edit.phase == EditPhase.SUBMITTING, state.edit.error,
        session.attachments.mapNotNull { (it as? JsonObject)?.text("name")?.takeIf(String::isNotEmpty) },
        onText = { editText(it) }, onExternalText = { text, start, end -> editText(text, true, start, end) }, onSubmit = { submitEdit(it) }, onCancel = ::cancelEdit)
}

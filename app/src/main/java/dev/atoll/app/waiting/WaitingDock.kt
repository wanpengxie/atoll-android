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
import dev.atoll.app.ui.components.NoticeBanner
import dev.atoll.app.ui.components.Tone
import dev.atoll.app.ui.theme.AtollColors
import kotlinx.serialization.json.*

/**
 * Messages that wait for a busy agent, grouped by agent. Collapsed it is one amber bar on top
 * of the composer; expanded each item can be inserted into the running turn, edited or cancelled.
 */
@Composable
fun WaitingDock(viewModel: WaitingViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val count = state.groups.sumOf { it.rows.size }
    val dockHeight = (LocalConfiguration.current.screenHeightDp * .45f).dp
    Column(modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (state.edit.notice.isNotEmpty() || state.error.isNotEmpty())
            NoticeBanner(state.error.ifEmpty { state.edit.notice }, Tone.Waiting, action = "关闭", onAction = viewModel::clearError)
        if (count > 0) Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp).clip(RoundedCornerShape(16.dp)).background(AtollColors.WarningSurface)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { viewModel.setCollapsed(!state.collapsed) }.padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.Schedule, null, Modifier.size(17.dp), tint = AtollColors.Warning)
                Text("↳ $count 条等待消息", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(state.groups.joinToString("、") { it.name + if (it.paused) "（已暂停）" else "" }, Modifier.weight(1f), fontSize = 13.sp,
                    color = AtollColors.WarningInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (state.collapsed) "展开" else "收起", Modifier.padding(horizontal = 10.dp), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = AtollColors.Warning)
            }
            if (!state.collapsed) LazyColumn(Modifier.fillMaxWidth().heightIn(max = dockHeight).background(AtollColors.Ground),
                contentPadding = PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                state.groups.forEach { group ->
                    item(key = "group:${group.actorId}") {
                        Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ActorAvatar(group.name, group.actorId, agent = true, size = 26.dp)
                            Column(Modifier.weight(1f)) {
                                Text("${group.name} · ${group.rows.size} 条", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(if (group.paused) "已暂停 · 恢复后发出" else "本轮结束后依次发出", fontSize = 12.sp,
                                    color = if (group.paused) AtollColors.Warning else AtollColors.Muted)
                            }
                            if (group.rows.any { it.actions.insert != null }) WaitingButton(if (state.groups.size == 1) "全部插入" else "插入 ${group.name} 全部",
                                state.online && !group.busy) { viewModel.insertAll(group.actorId) }
                            if (group.rows.any { it.actions.canCancel }) WaitingButton(if (state.groups.size == 1) "全部取消" else "取消 ${group.name} 全部",
                                state.online && !group.busy || group.rows.any { it.localSubmission != null && it.actions.canCancel }, danger = true) { viewModel.cancelAll(group.actorId) }
                        }
                    }
                    itemsIndexed(group.rows, key = { _, row -> row.turn.requestId }) { index, row ->
                        val editing = state.edit.session?.targetId == row.turn.requestId
                        Column(Modifier.testTag("atoll.waiting.${row.turn.requestId}").fillMaxWidth().clip(RoundedCornerShape(14.dp))
                            .background(AtollColors.Surface).border(if (editing) 1.5.dp else 1.dp, if (editing) AtollColors.Lagoon else AtollColors.Line, RoundedCornerShape(14.dp))
                            .padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Box(Modifier.size(20.dp).clip(CircleShape).background(AtollColors.Sunken), contentAlignment = Alignment.Center) {
                                    Text("${index + 1}", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = AtollColors.Muted)
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    if (row.labels.isNotEmpty()) Text(row.labels.joinToString(" · "), fontSize = 12.sp, color = AtollColors.Warning)
                                    Text(row.turn.exactText().ifEmpty { row.turn.request.type }, fontSize = 15.sp, lineHeight = 22.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                    if (editing) Text("正在输入框中编辑", fontSize = 12.sp, color = AtollColors.LagoonText)
                                }
                            }
                            if (!editing) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (row.actions.insert != null) WaitingButton("插入", state.online && !group.busy, primary = true) { viewModel.insert(row.turn.requestId) }
                                if (row.actions.canEdit) WaitingButton("编辑", state.online && !group.busy && state.edit.phase == EditPhase.IDLE) { viewModel.requestEdit(row.turn.requestId) }
                                if (row.actions.canCancel) WaitingButton("取消", row.localSubmission != null || state.online && !group.busy, danger = true) { viewModel.cancel(row.turn.requestId) }
                                row.actions.extras.forEach { control -> WaitingButton(control.label, state.online && !group.busy) { viewModel.extra(row.turn.requestId, control.word) } }
                            }
                        }
                    }
                }
            }
        }
    }
    if (state.warningTarget != null) AlertDialog(onDismissRequest = { viewModel.confirmEdit(false) }, title = { Text("确认编辑") }, text = { Text(state.warning) },
        confirmButton = { TextButton(onClick = { viewModel.confirmEdit(true) }) { Text("继续编辑") } }, dismissButton = { TextButton(onClick = { viewModel.confirmEdit(false) }) { Text("取消") } })
}

@Composable
private fun WaitingButton(label: String, enabled: Boolean, primary: Boolean = false, danger: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(Modifier.heightIn(min = 36.dp).widthIn(min = 44.dp).clip(shape)
        .background(if (primary && enabled) AtollColors.Ink else AtollColors.Surface)
        .then(if (primary && enabled) Modifier else Modifier.border(1.dp, if (danger) AtollColors.DangerLine else AtollColors.ControlLine, shape))
        .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            color = when { !enabled -> AtollColors.Faint; primary -> Color.White; danger -> AtollColors.Danger; else -> AtollColors.Ink })
    }
}

/** Read state in the host, pass this to AtollComposer(editing = ...). */
fun WaitingViewModel.editorBinding(state: WaitingUiState): ComposerEditBinding? {
    val session = state.edit.session ?: return null
    return ComposerEditBinding(session.id, state.groups.find { it.actorId == session.actorId }?.name ?: session.actorId,
        session.text, session.command, state.edit.phase == EditPhase.EDITING, state.edit.phase == EditPhase.SUBMITTING, state.edit.error,
        session.attachments.mapNotNull { (it as? JsonObject)?.text("name")?.takeIf(String::isNotEmpty) },
        onText = { editText(it) }, onExternalText = { text, start, end -> editText(text, true, start, end) }, onSubmit = { submitEdit(it) }, onCancel = ::cancelEdit)
}

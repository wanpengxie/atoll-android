package dev.atoll.app.waiting

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import dev.atoll.app.ui.theme.AtollColors
import kotlinx.serialization.json.*

@Composable
fun WaitingDock(viewModel: WaitingViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val count = state.groups.sumOf { it.rows.size }
    val dockHeight = (LocalConfiguration.current.screenHeightDp * .4f).dp
    Column(modifier) {
        if (state.edit.notice.isNotEmpty() || state.error.isNotEmpty()) Row(Modifier.fillMaxWidth()) {
            Text(state.error.ifEmpty { state.edit.notice }, Modifier.weight(1f).padding(8.dp), color = AtollColors.Warning, fontSize = 12.sp)
            TextButton(onClick = viewModel::clearError) { Text("关闭") }
        }
        if (count > 0) Surface(color = AtollColors.MutedSurface, shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, AtollColors.ControlLine)) {
            Column(Modifier.heightIn(max = dockHeight)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    Text("↳ $count 条等待消息", Modifier.padding(10.dp), fontSize = 12.sp)
                    if (!state.collapsed) state.groups.forEach { group ->
                        if (group.rows.any { it.actions.insert != null }) TextButton(onClick = { viewModel.insertAll(group.actorId) }, enabled = state.online && !group.busy) { Text(if (state.groups.size == 1) "全部插入" else "插入 ${group.name} 全部", fontSize = 12.sp) }
                        if (group.rows.any { it.actions.canCancel }) TextButton(onClick = { viewModel.cancelAll(group.actorId) }, enabled = state.online && !group.busy || group.rows.any { it.localSubmission != null && it.actions.canCancel }) { Text(if (state.groups.size == 1) "全部取消" else "取消 ${group.name} 全部", fontSize = 12.sp) }
                    }
                    TextButton(onClick = { viewModel.setCollapsed(!state.collapsed) }) { Text(if (state.collapsed) "展开" else "收起", fontSize = 12.sp) }
                }
                if (!state.collapsed) LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                    state.groups.forEach { group ->
                        if (state.groups.size > 1) item(key = "group:${group.actorId}") { Text(group.name + if (group.paused) "（已暂停）" else "", Modifier.padding(horizontal = 10.dp, vertical = 4.dp), color = AtollColors.Agent, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        items(group.rows, key = { it.turn.requestId }) { row ->
                            val editing = state.edit.session?.targetId == row.turn.requestId
                            Row(Modifier.testTag("atoll.waiting.${row.turn.requestId}").fillMaxWidth().heightIn(min = 44.dp).padding(start = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                val labels = row.labels.takeIf { it.isNotEmpty() }?.joinToString(" · ", postfix = " · ").orEmpty()
                                Text("↳ $labels${row.turn.exactText().ifEmpty { row.turn.request.type }}", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                                if (!editing) Row(Modifier.widthIn(max = 200.dp).horizontalScroll(rememberScrollState())) {
                                    if (row.actions.insert != null) WaitingButton("插入", state.online && !group.busy) { viewModel.insert(row.turn.requestId) }
                                    if (row.actions.canEdit) WaitingButton("编辑", state.online && !group.busy && state.edit.phase == EditPhase.IDLE) { viewModel.requestEdit(row.turn.requestId) }
                                    if (row.actions.canCancel) WaitingButton("取消", row.localSubmission != null || state.online && !group.busy) { viewModel.cancel(row.turn.requestId) }
                                    row.actions.extras.forEach { control -> WaitingButton(control.label, state.online && !group.busy) { viewModel.extra(row.turn.requestId, control.word) } }
                                }
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
private fun WaitingButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.widthIn(min = 44.dp).height(44.dp), contentPadding = PaddingValues(horizontal = 6.dp)) {
        Text(label, fontSize = 12.sp, maxLines = 1)
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

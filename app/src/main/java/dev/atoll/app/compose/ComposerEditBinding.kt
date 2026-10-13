package dev.atoll.app.compose

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardReturn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.atoll.app.ui.theme.AtollColors

/** An edit session owns its input; ordinary channel drafts and attachments remain untouched. */
data class ComposerEditBinding(
    val sessionId: String, val actorName: String, val text: String, val command: EditorCommand,
    val editable: Boolean, val submitting: Boolean, val error: String = "", val attachmentNames: List<String> = emptyList(),
    val onText: (String) -> Unit, val onExternalText: (String, Int, Int) -> Unit,
    val onSubmit: (String) -> Unit, val onCancel: () -> Unit,
)

@Composable
internal fun EditingComposer(binding: ComposerEditBinding, modifier: Modifier) {
    var editor by remember(binding.sessionId) { mutableStateOf(TextFieldValue(binding.text)) }
    LaunchedEffect(binding.sessionId, binding.command) {
        editor = TextFieldValue(binding.command.text, TextRange(binding.command.selectionStart, binding.command.selectionEnd))
    }
    fun newline() {
        if (!binding.editable) return
        val text = editor.text.replaceRange(editor.selection.min, editor.selection.max, "\n")
        val cursor = editor.selection.min + 1
        editor = TextFieldValue(text, TextRange(cursor))
        binding.onExternalText(text, cursor, cursor)
    }
    Column(modifier.padding(horizontal = 10.dp, vertical = 5.dp)) {
        Text("编辑 @${binding.actorName}", color = AtollColors.Agent, fontSize = 12.sp)
        if (!binding.editable) Text(if (binding.submitting) "修改提交中，正在确认 Agent 接纳…" else "正在暂停 Agent 并确认编辑状态…", color = AtollColors.Muted, fontSize = 12.sp)
        if (binding.error.isNotEmpty()) Text(binding.error, color = AtollColors.Accent, fontSize = 12.sp)
        binding.attachmentNames.forEach { Text("◇ $it · 编辑时保留原附件", color = AtollColors.Muted, fontSize = 12.sp) }
        key(binding.sessionId) {
            NativeComposerEditor(binding.command, Modifier.fillMaxWidth(), binding.editable, 6,
                onValue = { editor = it; binding.onText(it.text) },
                onKey = { key, shift, composing, repeated -> when {
                    key == "Escape" -> { binding.onCancel(); true }
                    key == "Enter" && repeated -> true
                    key == "Enter" && !composing && !shift -> { if (binding.editable && editor.text.isNotBlank()) binding.onSubmit(editor.text); true }
                    key == "Enter" && !composing && shift -> { newline(); true }
                    else -> false
                } },
                onSend = { if (binding.editable && editor.text.isNotBlank()) binding.onSubmit(editor.text) },
                onFocus = {}, onFiles = {}, onDrag = {})
        }
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = binding.onCancel) { Text("取消编辑") }
            IconButton(onClick = ::newline, enabled = binding.editable) { Icon(Icons.Outlined.KeyboardReturn, "换行") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { binding.onSubmit(editor.text) }, enabled = binding.editable && editor.text.isNotBlank()) {
                if (binding.submitting) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("提交编辑")
            }
        }
    }
}

package dev.atoll.app.compose

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.DragEvent
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.editableText
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setText
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.ContentInfoCompat

/** Native IME/span authority, plus keyboard/clipboard/drop file ingress. */
@Composable
internal fun NativeComposerEditor(command: EditorCommand, modifier: Modifier, enabled: Boolean, maxLines: Int,
    onValue: (TextFieldValue) -> Unit, onKey: (String, Boolean, Boolean, Boolean) -> Boolean,
    onSend: () -> Unit, onFocus: (Boolean) -> Unit, onFiles: (List<android.net.Uri>) -> Unit, onDrag: (Boolean) -> Unit) {
    var native by remember { mutableStateOf<NativeEditor?>(null) }
    var semanticText by remember { mutableStateOf("") }
    AndroidView(modifier = modifier.testTag("atoll.composer.editor").semantics {
        editableText = AnnotatedString(semanticText)
        setText { value ->
            val writer = native
            if (!enabled || writer == null) false else {
                writer.setText(value.text); writer.setSelection(writer.length()); true
            }
        }
    }, factory = { context -> NativeEditor(context).apply {
        native = this
        contentDescription = "消息输入框"
        importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES
        textSize = 16f
        hint = "输入消息；@ 选择成员，/ 使用命令"
        setHintTextColor(Color.rgb(111, 98, 82)); setTextColor(Color.rgb(46, 36, 25))
        setBackgroundColor(Color.TRANSPARENT)
        setPadding((8 * resources.displayMetrics.density).toInt(), (10 * resources.displayMetrics.density).toInt(), (8 * resources.displayMetrics.density).toInt(), (10 * resources.displayMetrics.density).toInt())
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        imeOptions = EditorInfo.IME_ACTION_SEND
        minHeight = (44 * resources.displayMetrics.density).toInt()
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { publish() }
        })
        setOnKeyListener { _, code, event ->
            if (event.action != KeyEvent.ACTION_DOWN) false else {
                val name = when (code) { KeyEvent.KEYCODE_ENTER -> "Enter"; KeyEvent.KEYCODE_DEL -> "Backspace"; KeyEvent.KEYCODE_ESCAPE -> "Escape"; KeyEvent.KEYCODE_DPAD_UP -> "ArrowUp"; KeyEvent.KEYCODE_DPAD_DOWN -> "ArrowDown"; else -> "" }
                keyCallback?.invoke(name, event.isShiftPressed, composing(), event.repeatCount > 0) ?: false
            }
        }
        setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_SEND) { if (!composing()) sendCallback?.invoke(); true } else false }
        onFocusChangeListener = android.view.View.OnFocusChangeListener { _, focused -> focusCallback?.invoke(focused) }
        ViewCompat.setOnReceiveContentListener(this, arrayOf("image/*", "video/*", "audio/*", "application/*", "text/*")) { _, payload ->
            val uris = payload.clip.uris()
            if (uris.isEmpty()) payload else { filesCallback?.invoke(uris); payload.partition { it.uri == null }.first }
        }
    } }, update = { editor ->
        editor.valueCallback = { value -> semanticText = value.text; onValue(value) }; editor.keyCallback = onKey; editor.sendCallback = onSend
        editor.focusCallback = onFocus; editor.filesCallback = onFiles; editor.dragCallback = onDrag
        editor.isEnabled = enabled; editor.maxLines = maxLines
        if (editor.writeAuthority.accept(command)) {
            editor.suppress = true
            try {
                editor.setText(command.text)
                editor.setSelection(command.selectionStart.coerceIn(0, editor.length()), command.selectionEnd.coerceIn(0, editor.length()))
                semanticText = command.text
            } finally { editor.suppress = false }
        }
    })
}
private class NativeEditor(context: Context) : EditText(context) {
    val writeAuthority = EditorWriteAuthority()
    var valueCallback: ((TextFieldValue) -> Unit)? = null
    var keyCallback: ((String, Boolean, Boolean, Boolean) -> Boolean)? = null
    var sendCallback: (() -> Unit)? = null
    var focusCallback: ((Boolean) -> Unit)? = null
    var filesCallback: ((List<android.net.Uri>) -> Unit)? = null
    var dragCallback: ((Boolean) -> Unit)? = null
    var suppress = false
    fun composing() = BaseInputConnection.getComposingSpanStart(text) >= 0
    fun publish() {
        if (suppress || valueCallback == null) return
        val start = BaseInputConnection.getComposingSpanStart(text)
        val end = BaseInputConnection.getComposingSpanEnd(text)
        valueCallback?.invoke(TextFieldValue(text.toString(), TextRange(selectionStart.coerceAtLeast(0), selectionEnd.coerceAtLeast(0)), if (start >= 0 && end >= start) TextRange(start, end) else null))
    }
    override fun onSelectionChanged(selStart: Int, selEnd: Int) { super.onSelectionChanged(selStart, selEnd); publish() }
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val connection = super.onCreateInputConnection(outAttrs) ?: return null
        outAttrs.imeOptions = (outAttrs.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION.inv()) or EditorInfo.IME_ACTION_SEND
        EditorInfoCompat.setContentMimeTypes(outAttrs, arrayOf("image/*", "video/*", "audio/*", "application/*", "text/*"))
        return InputConnectionCompat.createWrapper(connection, outAttrs) { info, flags, _ ->
            if (flags and InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION != 0) try { info.requestPermission() } catch (_: Exception) { return@createWrapper false }
            filesCallback?.invoke(listOf(info.contentUri)); true
        }
    }
    override fun onTextContextMenuItem(id: Int): Boolean {
        if (id == android.R.id.paste || id == android.R.id.pasteAsPlainText) {
            val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
            val uris = clip?.uris().orEmpty()
            if (uris.isNotEmpty()) { filesCallback?.invoke(uris); return true }
        }
        return super.onTextContextMenuItem(id)
    }
    override fun onDragEvent(event: DragEvent): Boolean {
        when (event.action) {
            DragEvent.ACTION_DRAG_ENTERED -> dragCallback?.invoke(true)
            DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> dragCallback?.invoke(false)
            DragEvent.ACTION_DROP -> {
                dragCallback?.invoke(false)
                val uris = event.clipData?.uris().orEmpty()
                if (uris.isNotEmpty()) {
                    var owner = context
                    while (owner is android.content.ContextWrapper && owner !is android.app.Activity) owner = owner.baseContext
                    (owner as? android.app.Activity)?.requestDragAndDropPermissions(event)
                    filesCallback?.invoke(uris); return true
                }
            }
        }
        return super.onDragEvent(event)
    }
}
private fun ClipData.uris() = (0 until itemCount).mapNotNull { getItemAt(it).uri?.takeIf { uri -> uri.scheme in setOf("content", "file") } }

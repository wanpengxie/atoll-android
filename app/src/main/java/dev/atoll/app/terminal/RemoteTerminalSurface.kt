package dev.atoll.app.terminal

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.view.*
import android.view.inputmethod.*
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import com.termux.terminal.*
import com.termux.view.*
import java.util.ArrayDeque
import kotlin.math.max

/**
 * TerminalView is final and expects a local TerminalSession in its input and
 * selection paths. This thin parent owns those paths; the unchanged child
 * provides VT rendering. No TerminalSession is instantiated, no reflection.
 */
class RemoteTerminalSurface(context: Context) : FrameLayout(context) {
    val terminalView = TerminalView(context, null)
    private val handler = Handler(Looper.getMainLooper())
    private var handle: PtyHandle? = null
    private var theme = TerminalTheme.DARK
    private var ctrl = false
    var onControlConsumed: () -> Unit = {}
    private val pending = ArrayDeque<ByteArray>()
    private var pendingBytes = 0
    var columns = 80; private set
    var rows = 24; private set
    private val output = object : TerminalOutput() {
        override fun write(data: ByteArray, offset: Int, count: Int) { send(data.copyOfRange(offset, offset + count)) }
        override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
        override fun onCopyTextToClipboard(text: String?) { clipboard().setPrimaryClip(ClipData.newPlainText("终端文本", text.orEmpty())) }
        override fun onPasteTextFromClipboard() { pasteClipboard() }
        override fun onBell() = Unit
        override fun onColorsChanged() { terminalView.invalidate() }
    }
    private val emulatorClient = object : TerminalSessionClient {
        override fun onTextChanged(session: TerminalSession?) = Unit
        override fun onTitleChanged(session: TerminalSession?) = Unit
        override fun onSessionFinished(session: TerminalSession?) = Unit
        override fun onCopyTextToClipboard(session: TerminalSession?, text: String?) { output.onCopyTextToClipboard(text) }
        override fun onPasteTextFromClipboard(session: TerminalSession?) { pasteClipboard() }
        override fun onBell(session: TerminalSession?) = Unit
        override fun onColorsChanged(session: TerminalSession?) { terminalView.invalidate() }
        override fun onTerminalCursorStateChange(state: Boolean) = Unit
        override fun getTerminalCursorStyle(): Int = TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK
        override fun logError(tag: String?, message: String?) = Unit
        override fun logWarn(tag: String?, message: String?) = Unit
        override fun logInfo(tag: String?, message: String?) = Unit
        override fun logDebug(tag: String?, message: String?) = Unit
        override fun logVerbose(tag: String?, message: String?) = Unit
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
        override fun logStackTrace(tag: String?, e: Exception?) = Unit
    }
    private var emulator = createEmulator()
    init {
        isFocusable = true; isFocusableInTouchMode = true
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        terminalView.isFocusable = false; terminalView.isFocusableInTouchMode = false
        terminalView.importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        terminalView.setTextSize((13 * resources.displayMetrics.scaledDensity).toInt())
        terminalView.mEmulator = emulator
        terminalView.setTerminalViewClient(object : TerminalViewClient {
            override fun onScale(scale: Float): Float = scale
            override fun onSingleTapUp(event: MotionEvent?) { showKeyboard() }
            override fun shouldBackButtonBeMappedToEscape() = false
            override fun shouldEnforceCharBasedInput() = false
            override fun shouldUseCtrlSpaceWorkaround() = false
            override fun isTerminalViewSelected() = true
            override fun copyModeChanged(copyMode: Boolean) = Unit
            override fun onKeyDown(keyCode: Int, event: KeyEvent, session: TerminalSession?): Boolean { handleKey(keyCode, event); return true }
            override fun onKeyUp(keyCode: Int, event: KeyEvent?) = true
            override fun onLongPress(event: MotionEvent?): Boolean { showSelection(); return true }
            override fun readControlKey() = ctrl
            override fun readAltKey() = false
            override fun readShiftKey() = false
            override fun readFnKey() = false
            override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean { send(terminalCodePoint(codePoint, ctrlDown)); return true }
            override fun onEmulatorSet() = Unit
            override fun logError(tag: String?, message: String?) = Unit
            override fun logWarn(tag: String?, message: String?) = Unit
            override fun logInfo(tag: String?, message: String?) = Unit
            override fun logDebug(tag: String?, message: String?) = Unit
            override fun logVerbose(tag: String?, message: String?) = Unit
            override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
            override fun logStackTrace(tag: String?, e: Exception?) = Unit
        })
        // Own every touch gesture, including fling, so native doScroll can
        // never reach its session-only handleKeyCode when an app enters alt-screen.
        val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            private var remainder = 0f
            override fun onDown(e: MotionEvent): Boolean { remainder = 0f; return true }
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (emulator.isMouseTrackingActive) {
                    val c = terminalView.getColumnAndRow(e, false)
                    emulator.sendMouseEvent(TerminalEmulator.MOUSE_LEFT_BUTTON, c[0] + 1, c[1] + 1, true)
                    emulator.sendMouseEvent(TerminalEmulator.MOUSE_LEFT_BUTTON, c[0] + 1, c[1] + 1, false)
                } else showKeyboard()
                return true
            }
            override fun onLongPress(e: MotionEvent) { showSelection() }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                remainder += dy
                val lines = (remainder / terminalView.mRenderer.fontLineSpacing).toInt()
                remainder -= lines * terminalView.mRenderer.fontLineSpacing
                scrollRows(lines, e2); return true
            }
        })
        terminalView.setOnTouchListener { _, event ->
            when {
                event.isButtonPressed(MotionEvent.BUTTON_SECONDARY) -> { if (event.action == MotionEvent.ACTION_DOWN) terminalView.showContextMenu() }
                event.isButtonPressed(MotionEvent.BUTTON_TERTIARY) -> { if (event.action == MotionEvent.ACTION_DOWN) pasteClipboard() }
                event.isFromSource(InputDevice.SOURCE_MOUSE) && emulator.isMouseTrackingActive -> {
                    val c = terminalView.getColumnAndRow(event, false)
                    val button = if (event.action == MotionEvent.ACTION_MOVE) TerminalEmulator.MOUSE_LEFT_BUTTON_MOVED else TerminalEmulator.MOUSE_LEFT_BUTTON
                    emulator.sendMouseEvent(button, c[0] + 1, c[1] + 1, event.action != MotionEvent.ACTION_UP)
                }
                else -> gestures.onTouchEvent(event)
            }
            true
        }
        terminalView.setOnGenericMotionListener { _, event ->
            if (event.action == MotionEvent.ACTION_SCROLL) { scrollRows(-(event.getAxisValue(MotionEvent.AXIS_VSCROLL) * 3).toInt(), event); true } else false
        }
        terminalView.setOnCreateContextMenuListener { menu, _, _ ->
            menu.add("选择文本").setOnMenuItemClickListener { showSelection(); true }
            menu.add("粘贴").setOnMenuItemClickListener { pasteClipboard(); true }
        }
        addView(terminalView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        setTheme(TerminalTheme.DARK)
    }
    private fun createEmulator() = TerminalEmulator(output, columns, rows, 5000, emulatorClient)
    fun bind(stream: PtyHandle) {
        handle = stream
        stream.resize(columns, rows)
        while (pending.isNotEmpty()) stream.write(pending.removeFirst())
        pendingBytes = 0
    }
    fun unbind(stream: PtyHandle) { if (handle === stream) { handle = null; pending.clear(); pendingBytes = 0 } }
    /** Posted before replay bytes by the PTY actor, preserving reset/replay order. */
    fun onReady() { handler.post {
        emulator = createEmulator(); terminalView.mEmulator = emulator; terminalView.topRow = 0
        setTheme(theme); terminalView.onScreenUpdated()
    } }
    fun onData(bytes: ByteArray) { handler.post { emulator.append(bytes, bytes.size); terminalView.onScreenUpdated() } }
    private fun send(bytes: ByteArray) {
        val stream = handle
        if (stream != null) stream.write(bytes)
        else if (pendingBytes + bytes.size <= PtyClient.MAX_PENDING_INPUT) { pending.add(bytes); pendingBytes += bytes.size }
    }
    fun control(enabled: Boolean) { ctrl = enabled }
    private fun consumeControl(): Boolean = ctrl.also { if (it) { ctrl = false; onControlConsumed() } }
    fun text(value: String) {
        value.codePoints().forEach { send(terminalCodePoint(it, consumeControl())) }
    }
    fun special(keyCode: Int) {
        val modifier = if (consumeControl()) KeyHandler.KEYMOD_CTRL else 0
        KeyHandler.getCode(keyCode, modifier, emulator.isCursorKeysApplicationMode, emulator.isKeypadApplicationMode)?.let { send(it.toByteArray()) }
    }
    private fun handleKey(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) return false
        if (event.isCtrlPressed && event.isShiftPressed) {
            if (keyCode == KeyEvent.KEYCODE_V) { pasteClipboard(); return true }
            if (keyCode == KeyEvent.KEYCODE_C) { showSelection(); return true }
        }
        val ctrlDown = event.isCtrlPressed || consumeControl()
        val modifier = (if (ctrlDown) KeyHandler.KEYMOD_CTRL else 0) or (if (event.isAltPressed) KeyHandler.KEYMOD_ALT else 0) or (if (event.isShiftPressed) KeyHandler.KEYMOD_SHIFT else 0)
        val sequence = KeyHandler.getCode(keyCode, modifier, emulator.isCursorKeysApplicationMode, emulator.isKeypadApplicationMode)
        if (sequence != null) { send(sequence.toByteArray()); return true }
        if (event.action == KeyEvent.ACTION_MULTIPLE && event.characters != null) { text(event.characters); return true }
        val codePoint = event.getUnicodeChar(event.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK).inv())
        if (codePoint != 0) { send(terminalCodePoint(codePoint, ctrlDown, event.isAltPressed)); return true }
        return false
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = handleKey(keyCode, event) || super.onKeyDown(keyCode, event)
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = if (keyCode == KeyEvent.KEYCODE_BACK) super.onKeyUp(keyCode, event) else true
    override fun onCheckIsTextEditor() = true
    override fun onCreateInputConnection(attrs: EditorInfo): InputConnection {
        attrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        attrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, true) {
            private val composing = SpannableStringBuilder()
            override fun getEditable(): Editable = composing
            override fun commitText(value: CharSequence?, cursor: Int): Boolean {
                super.commitText(value, cursor); text(composing.toString()); composing.clear(); return true
            }
            override fun finishComposingText(): Boolean {
                super.finishComposingText(); text(composing.toString()); composing.clear(); return true
            }
            override fun deleteSurroundingText(before: Int, after: Int): Boolean { repeat(before.coerceAtMost(256)) { send(byteArrayOf(127)) }; composing.clear(); return true }
            override fun sendKeyEvent(event: KeyEvent): Boolean { if (event.action == KeyEvent.ACTION_DOWN) return handleKey(event.keyCode, event); return true }
            override fun performEditorAction(action: Int): Boolean { send(byteArrayOf(13)); return true }
        }
    }
    fun showKeyboard() { requestFocus(); (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(this, InputMethodManager.SHOW_IMPLICIT) }
    fun pasteClipboard() { clipboard().primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.let { emulator.paste(it) } }
    private fun clipboard() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    fun showSelection() {
        val transcript = emulator.screen.transcriptText
        val selectable = TextView(context).apply {
            text = transcript; typeface = Typeface.MONOSPACE; textSize = 12f; setTextIsSelectable(true)
            setTextColor(emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_FOREGROUND])
            setBackgroundColor(emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND]); setPadding(16, 16, 16, 16)
        }
        val scroll = ScrollView(context).apply { addView(selectable) }
        AlertDialog.Builder(context).setTitle("选择终端文本").setView(scroll)
            .setPositiveButton("粘贴") { _, _ -> pasteClipboard(); showKeyboard() }
            .setNeutralButton("复制全部") { _, _ -> output.onCopyTextToClipboard(transcript) }
            .setNegativeButton("关闭", null).create().apply {
                show(); window?.setLayout((resources.displayMetrics.widthPixels * .94f).toInt(), (resources.displayMetrics.heightPixels * .8f).toInt())
            }
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val renderer = terminalView.mRenderer ?: return
        val cols = max(4, (w / renderer.fontWidth).toInt())
        val lines = max(4, h / renderer.fontLineSpacing)
        if (cols != columns || lines != rows) {
            columns = cols; rows = lines; emulator.resize(cols, lines); terminalView.onScreenUpdated(); handle?.resize(cols, lines)
        }
    }
    private fun scrollRows(amount: Int, event: MotionEvent) {
        if (amount == 0) return
        when {
            emulator.isMouseTrackingActive -> {
                val coords = terminalView.getColumnAndRow(event, false)
                repeat(kotlin.math.abs(amount)) { emulator.sendMouseEvent(if (amount < 0) TerminalEmulator.MOUSE_WHEELUP_BUTTON else TerminalEmulator.MOUSE_WHEELDOWN_BUTTON, coords[0] + 1, coords[1] + 1, true) }
            }
            emulator.isAlternateBufferActive -> repeat(kotlin.math.abs(amount)) { special(if (amount < 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN) }
            else -> { terminalView.topRow = (terminalView.topRow + amount).coerceIn(-emulator.screen.activeTranscriptRows, 0); terminalView.invalidate() }
        }
    }
    fun setTheme(value: TerminalTheme) {
        theme = value
        val dark = value == TerminalTheme.DARK
        val colors = if (dark) arrayOf("#3a352e", "#ff6b6b", "#8bd17c", "#e6c07b", "#7aa2f7", "#c99bdd", "#6fd0d6", "#d6ccbd", "#6f6659", "#ff8a8a", "#a7e39a", "#f2d493", "#9db8ff", "#dbb4ee", "#96e2e7", "#fbf6ee") else arrayOf("#2e2419", "#b3261e", "#2f7d31", "#8a6100", "#1f5fa8", "#8a3f9e", "#0f7a86", "#6f6252", "#6f6252", "#e4002b", "#1f6b21", "#6f4e00", "#154a86", "#71307f", "#0a616b", "#2e2419")
        // Termux's OSC 104/110/111 and RIS restore COLOR_SCHEME defaults.
        // Theme is an app-wide preference; update the library's default palette
        // together with this emulator, so a shell reset retains the chosen theme.
        fun color(index: Int, value: String) {
            val argb = Color.parseColor(value)
            TerminalColors.COLOR_SCHEME.mDefaultColors[index] = argb
            emulator.mColors.mCurrentColors[index] = argb
        }
        colors.forEachIndexed { i, value -> color(i, value) }
        color(TextStyle.COLOR_INDEX_FOREGROUND, if (dark) "#e8e0d4" else "#2e2419")
        color(TextStyle.COLOR_INDEX_BACKGROUND, if (dark) "#1c1a17" else "#ffffff")
        color(TextStyle.COLOR_INDEX_CURSOR, if (dark) "#ff5c78" else "#e4002b")
        setBackgroundColor(emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND]); terminalView.invalidate()
    }
}

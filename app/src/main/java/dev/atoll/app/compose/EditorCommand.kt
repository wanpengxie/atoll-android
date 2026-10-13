package dev.atoll.app.compose

/** Only explicit edits (clear/load/mention/newline) advance this version.
 * Native typing reports a draft mirror and never issues an editor command.
 */
data class EditorCommand(val version: Long = 0, val text: String = "", val selectionStart: Int = text.length, val selectionEnd: Int = selectionStart)

internal class EditorWriteAuthority {
    private var appliedVersion = Long.MIN_VALUE
    fun accept(command: EditorCommand): Boolean {
        if (command.version <= appliedVersion) return false
        appliedVersion = command.version
        return true
    }
}

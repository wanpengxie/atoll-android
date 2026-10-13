package dev.atoll.app.terminal

import dev.atoll.app.net.KeyValueStore
import kotlinx.coroutines.flow.*

enum class TerminalTheme { DARK, LIGHT }
class TerminalPreferences(private val store: KeyValueStore) {
    private val mutableTheme = MutableStateFlow(if (store.get(KEY) == "light") TerminalTheme.LIGHT else TerminalTheme.DARK)
    val theme: StateFlow<TerminalTheme> = mutableTheme.asStateFlow()
    fun toggle() {
        mutableTheme.value = if (mutableTheme.value == TerminalTheme.DARK) TerminalTheme.LIGHT else TerminalTheme.DARK
        store.put(KEY, if (mutableTheme.value == TerminalTheme.LIGHT) "light" else "dark")
    }
    companion object { private const val KEY = "atoll.terminal.theme" }
}
data class TerminalDevice(val id: String, val name: String = id, val online: Boolean? = null)
fun preferredTerminalDevice(devices: List<TerminalDevice>, selected: String = ""): String =
    selected.takeIf { choice -> devices.any { it.id == choice } } ?: devices.firstOrNull { it.id == "local-device" }?.id ?: devices.singleOrNull()?.id.orEmpty()

/** UTF-8 bytes, with the same ASCII control mapping as a physical Ctrl key. */
internal fun terminalCodePoint(codePoint: Int, ctrl: Boolean, alt: Boolean = false): ByteArray {
    val mapped = if (!ctrl) codePoint else when (codePoint) {
        in 97..122 -> codePoint - 96
        in 64..95 -> codePoint - 64
        32, 50 -> 0
        51 -> 27; 52 -> 28; 53 -> 29; 54 -> 30
        47, 55 -> 31; 56, 63 -> 127
        else -> codePoint
    }
    val text = String(Character.toChars(mapped))
    return ((if (alt) "\u001b" else "") + text).toByteArray(Charsets.UTF_8)
}

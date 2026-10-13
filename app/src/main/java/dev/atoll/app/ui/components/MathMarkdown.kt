package dev.atoll.app.ui.components

/** Extract math before CommonMark unescapes LaTeX; code spans/fences stay literal. */
data class MathFragment(val source: String, val display: Boolean)
data class PreparedMath(val markdown: String, val fragments: Map<String, MathFragment>)
fun prepareMath(source: String): PreparedMath {
    val mask = BooleanArray(source.length)
    var offset = 0
    var fence: Char? = null
    var fenceLength = 0
    source.splitToSequence('\n').forEach { line ->
        val marker = Regex("^ {0,3}([`~]{3,})").find(line)?.groupValues?.get(1)
        val end = (offset + line.length + 1).coerceAtMost(source.length)
        val inside = fence != null || line.startsWith("    ") || line.startsWith('\t')
        if (inside || marker != null) for (i in offset until end) mask[i] = true
        if (marker != null && marker.all { it == marker.first() }) {
            if (fence == null) { fence = marker.first(); fenceLength = marker.length }
            else if (fence == marker.first() && marker.length >= fenceLength && line.trim() == marker) fence = null
        }
        offset = end
    }
    var i = 0
    while (i < source.length) {
        if (source[i] != '`' || mask[i]) { i++; continue }
        val length = source.substring(i).takeWhile { it == '`' }.length
        val delimiter = "`".repeat(length)
        val close = source.indexOf(delimiter, i + length)
        if (close >= 0) { for (j in i until close + length) mask[j] = true; i = close + length } else i += length
    }
    val fragments = linkedMapOf<String, MathFragment>()
    val output = StringBuilder()
    i = 0
    while (i < source.length) {
        if (mask[i] || (i > 0 && source[i - 1] == '\\')) { output.append(source[i++]); continue }
        val opening = when {
            source.startsWith("\\[", i) -> "\\["
            source.startsWith("\\(", i) -> "\\("
            source.startsWith("$$", i) -> "$$"
            source[i] == '$' -> "$"
            else -> null
        }
        if (opening == null) { output.append(source[i++]); continue }
        val closing = when (opening) { "\\[" -> "\\]"; "\\(" -> "\\)"; else -> opening }
        val close = source.indexOf(closing, i + opening.length)
        if (close < 0 || (i + opening.length until close).any { mask[it] }) { output.append(source[i++]); continue }
        val math = source.substring(i + opening.length, close)
        // Single-dollar spans cannot span lines or have empty/space-delimited edges.
        if (math.isBlank() || (opening == "$" && ('\n' in math || math.first().isWhitespace() || math.last().isWhitespace()))) {
            output.append(source[i++]); continue
        }
        val token = "ATOLLMATH${fragments.size}TOKEN"
        val display = opening == "$$" || opening == "\\["
        fragments[token] = MathFragment(math, display)
        if (display) output.append("\n\n$token\n\n") else output.append(token)
        i = close + closing.length
    }
    return PreparedMath(output.toString(), fragments)
}

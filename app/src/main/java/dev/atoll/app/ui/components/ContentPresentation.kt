package dev.atoll.app.ui.components

import kotlin.math.ceil

fun estimateVisualLines(source: String, columns: Int): Int = source.lines().sumOf { line ->
    val units = line.fold(0) { total, char -> total + if (char.code > 0x2ff) 2 else 1 }
    ceil(units.toDouble() / columns.coerceAtLeast(1)).toInt().coerceAtLeast(1)
}.coerceAtLeast(1)
/** The source with every fenced block cut to [max] lines, to size a body whose code folds on its own. */
fun capFences(source: String, max: Int): String {
    if ("```" !in source) return source
    val out = StringBuilder(); var inFence = false; var count = 0
    for (line in source.lines()) {
        if (line.trimStart().startsWith("```")) { inFence = !inFence; count = 0; out.append(line).append('\n'); continue }
        if (inFence && ++count > max + 1) continue
        out.append(line).append('\n')
    }
    return out.toString()
}
fun shouldFoldBody(source: String, visualLines: Int, latest: Boolean) = !latest && (visualLines > 18 || source.length > 1200)
fun boundedToolOutput(value: String): String {
    val bytes = value.toByteArray(Charsets.UTF_8)
    if (bytes.size <= 4096) return value
    var end = 4096
    while (end > 0 && bytes[end].toInt() and 0xc0 == 0x80) end--
    return bytes.copyOf(end).toString(Charsets.UTF_8)
}
enum class CodeTone { Plain, Comment, String, Keyword, Number }
data class CodeToken(val text: String, val tone: CodeTone = CodeTone.Plain)
object CodeHighlighter {
    private val aliases = mapOf("js" to "javascript", "mjs" to "javascript", "cjs" to "javascript", "ts" to "typescript",
        "py" to "python", "golang" to "go", "kt" to "kotlin", "rs" to "rust", "yml" to "yaml", "c++" to "cpp", "html" to "markup", "xml" to "markup")
    private val supported = setOf("javascript", "typescript", "jsx", "tsx", "python", "go", "kotlin", "java", "rust", "swift", "json", "json5", "sql", "yaml", "css", "c", "cpp", "graphql", "bash", "sh", "markup", "markdown")
    private val keywords = setOf("val", "var", "fun", "class", "object", "data", "interface", "if", "else", "when", "for", "while", "return", "import", "package", "private", "public", "override", "suspend", "null", "true", "false", "const", "let", "function", "async", "await", "new", "export", "from", "def", "self", "None", "True", "False", "in", "and", "or", "try", "catch", "throw", "finally", "SELECT", "FROM", "WHERE", "INSERT", "UPDATE", "DELETE", "func", "type", "struct", "defer", "fn", "mut", "impl", "use", "enum", "extends", "static", "void")
    private val pattern = Regex("//[^\\n]*|/\\*[\\s\\S]*?\\*/|#[^\\n]*|\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|`(?:\\\\.|[^`\\\\])*`|\\b\\d+(?:\\.\\d+)?\\b|\\b[A-Za-z_][A-Za-z_0-9]*\\b")
    fun language(name: String): String = name.trim().lowercase().let { aliases[it] ?: it }.let { if (it in supported) it else "plain" }
    fun highlight(source: String, language: String): List<CodeToken> {
        val lang = language(language)
        if (lang == "plain") return listOf(CodeToken(source))
        return buildList {
            var cursor = 0
            pattern.findAll(source).forEach { match ->
                if (match.range.first > cursor) add(CodeToken(source.substring(cursor, match.range.first)))
                val text = match.value
                val tone = when {
                    text.startsWith("//") || text.startsWith("/*") || (text.startsWith("#") && lang in setOf("python", "yaml", "bash", "sh")) -> CodeTone.Comment
                    text.first() in listOf('"', '\'', '`') -> CodeTone.String
                    text.first().isDigit() -> CodeTone.Number
                    text in keywords -> CodeTone.Keyword
                    else -> CodeTone.Plain
                }
                add(CodeToken(text, tone)); cursor = match.range.last + 1
            }
            if (cursor < source.length) add(CodeToken(source.substring(cursor)))
        }
    }
}

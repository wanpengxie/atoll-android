package dev.atoll.app.files

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*

internal fun highlightSource(line: String): AnnotatedString = buildAnnotatedString {
    append(line)
    val token = Regex("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|//.*$|#.*$|\\b(?:fun|val|var|class|object|interface|if|else|return|import|package|def|let|const|function|true|false|null|for|while|when|public|private|async|await|suspend)\\b|\\b[0-9]+(?:\\.[0-9]+)?\\b")
    token.findAll(line).forEach { match ->
        val value = match.value
        val color = when { value.startsWith("//") || value.startsWith('#') -> Color(0xff73877d); value.startsWith('"') || value.startsWith('\'') -> Color(0xffa15b34); value.first().isDigit() -> Color(0xff8a5ba8); else -> Color(0xff286fa5) }
        addStyle(SpanStyle(color = color), match.range.first, match.range.last + 1)
    }
}

package dev.atoll.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/** Timeline long-press belongs to its action sheet; other Markdown remains selectable. */
val LocalMarkdownSelectionEnabled = staticCompositionLocalOf { true }

@Composable
internal fun MarkdownSelection(modifier: Modifier, content: @Composable () -> Unit) {
    if (LocalMarkdownSelectionEnabled.current) SelectionContainer(modifier, content)
    else Box(modifier) { content() }
}

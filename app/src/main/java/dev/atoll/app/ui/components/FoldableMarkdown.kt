package dev.atoll.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.atoll.app.ui.theme.AtollColors

@Composable
fun FoldableMarkdown(messageId: String, source: String, latest: Boolean = false, onLink: ((String) -> Unit)? = null) {
    var expanded by rememberSaveable(messageId) { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val lines = remember(source, maxWidth) { estimateVisualLines(source, (maxWidth.value / 7).toInt().coerceAtLeast(12)) }
        val foldable = shouldFoldBody(source, lines, latest)
        Column {
            val frame = if (foldable && !expanded) Modifier.height(294.dp).clipToBounds() else Modifier
            Box(frame) { Markdown(messageId, source, Modifier.wrapContentHeight(align = Alignment.Top, unbounded = foldable && !expanded), onLink) }
            if (foldable) TextButton({ expanded = !expanded }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                Text(if (expanded) "收起" else "展开全文 · $lines 行", fontSize = 12.sp, color = AtollColors.Muted)
            }
        }
    }
}

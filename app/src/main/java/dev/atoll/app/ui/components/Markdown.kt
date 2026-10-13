package dev.atoll.app.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.atoll.app.ui.theme.AtollColors

val LocalMarkdownCache = staticCompositionLocalOf { MarkdownCache() }

@Composable
fun Markdown(messageId: String, source: String, modifier: Modifier = Modifier, onLink: ((String) -> Unit)? = null) {
    val cache = LocalMarkdownCache.current
    val blocks by produceState<List<MarkdownBlock>?>(null, messageId, source, cache) {
        value = null
        value = cache.get(messageId, source)
    }
    val uri = LocalUriHandler.current
    val linkAction: (String) -> Unit = onLink ?: { address ->
        // File paths and daemon:// links are passed to the host's preview callback.
        if (address.startsWith("https://") || address.startsWith("http://") || address.startsWith("mailto:")) {
            runCatching { uri.openUri(address) }
        }
    }
    SelectionContainer(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            blocks?.let { RenderBlocks(it, linkAction) } ?: Text(source, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
@Composable
private fun RenderBlocks(blocks: List<MarkdownBlock>, onLink: (String) -> Unit) {
    blocks.forEach { block ->
        when (block) {
            is MarkdownBlock.Paragraph -> {
                val size = when (block.heading) { 1 -> 23; 2 -> 20; 3 -> 18; 4, 5, 6 -> 16; else -> 14 }
                Text(richText(block.runs, onLink), fontSize = size.sp, lineHeight = (size * 1.5).sp,
                    fontWeight = if (block.heading > 0) FontWeight.SemiBold else FontWeight.Normal)
            }
            is MarkdownBlock.Code -> CodeBlock(block)
            is MarkdownBlock.Quote -> Row(Modifier.fillMaxWidth().background(AtollColors.MutedSurface)) {
                Box(Modifier.width(3.dp).heightIn(min = 32.dp).background(AtollColors.ControlLine))
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { RenderBlocks(block.blocks, onLink) }
            }
            is MarkdownBlock.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                block.items.forEachIndexed { index, item -> Row {
                    Text(block.start?.let { "${it + index}. " } ?: "• ", modifier = Modifier.widthIn(min = 22.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) { RenderBlocks(item, onLink) }
                } }
            }
            is MarkdownBlock.Table -> Column(Modifier.horizontalScroll(rememberScrollState()).border(1.dp, AtollColors.Line)) {
                block.rows.forEachIndexed { index, row -> Row(Modifier.background(if (index == 0) AtollColors.Code else Color.Transparent)) {
                    row.forEach { cell -> Text(richText(cell, onLink), Modifier.width(150.dp).padding(10.dp),
                        fontSize = 13.sp, fontWeight = if (index == 0) FontWeight.SemiBold else FontWeight.Normal) }
                }; HorizontalDivider(color = AtollColors.Line) }
            }
            MarkdownBlock.Rule -> HorizontalDivider(color = AtollColors.Line)
        }
    }
}
private fun richText(runs: List<MarkdownRun>, onLink: (String) -> Unit): AnnotatedString = buildAnnotatedString {
    runs.forEach { run ->
        val style = SpanStyle(
            fontWeight = if (run.bold) FontWeight.Bold else null,
            fontStyle = if (run.italic) FontStyle.Italic else null,
            fontFamily = if (run.code) FontFamily.Monospace else null,
            background = if (run.code) AtollColors.Code else Color.Unspecified,
            color = if (run.link != null) AtollColors.Agent else Color.Unspecified,
            textDecoration = if (run.strike) TextDecoration.LineThrough else null,
        )
        withStyle(style) {
            if (run.link != null) withLink(LinkAnnotation.Clickable(run.link, TextLinkStyles(SpanStyle(color = AtollColors.Agent))) {
                onLink(run.link)
            }) { append(run.text) } else append(run.text)
        }
    }
}
@Composable
private fun CodeBlock(block: MarkdownBlock.Code) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(block.text) { mutableStateOf(false) }
    Surface(color = AtollColors.Code, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(block.language.ifEmpty { "代码" }, Modifier.padding(top = 12.dp), fontSize = 11.sp, color = AtollColors.Muted)
                TextButton(onClick = { clipboard.setText(AnnotatedString(block.text)); copied = true }) { Text(if (copied) "已复制" else "复制", fontSize = 11.sp) }
            }
            Text(block.text, Modifier.horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp)
        }
    }
}

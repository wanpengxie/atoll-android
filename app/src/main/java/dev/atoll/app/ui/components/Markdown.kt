package dev.atoll.app.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.shape.RoundedCornerShape
import coil3.compose.SubcomposeAsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Alignment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.atoll.app.ui.theme.ChatColors

val LocalMarkdownCache = staticCompositionLocalOf { MarkdownCache() }

/** Conversation surfaces provide the v2 chat type and colours; other screens keep the defaults. */
@Immutable
data class MarkdownStyle(val bodySize: Int = 14, val bodyLineHeight: Float = 21f, val link: Color = ChatColors.Link, val code: Color = ChatColors.Bar,
    /** ChatStates ⑧: longer code folds to this many lines with a fade; 0 never folds. */
    val codeMaxLines: Int = 0)
val LocalMarkdownStyle = staticCompositionLocalOf { MarkdownStyle() }

@Composable
fun Markdown(messageId: String, source: String, modifier: Modifier = Modifier, onLink: ((String) -> Unit)? = null, previewHeight: Dp? = null) {
    val cache = LocalMarkdownCache.current
    var blocks by remember(messageId, source, cache) { mutableStateOf(cache.peek(messageId, source)) }
    LaunchedEffect(messageId, source, cache) {
        if (blocks == null) blocks = cache.get(messageId, source)
    }
    val uri = LocalUriHandler.current
    val linkAction: (String) -> Unit = onLink ?: { address ->
        // File paths and daemon:// links are passed to the host's preview callback.
        if (address.startsWith("https://") || address.startsWith("http://") || address.startsWith("mailto:")) {
            runCatching { uri.openUri(address) }
        }
    }
    MarkdownSelection(modifier) {
        if (previewHeight != null) {
            // A collapsed reply must not measure its invisible paragraphs.
            LazyColumn(Modifier.fillMaxWidth().height(previewHeight), userScrollEnabled = false,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val document = blocks
                if (document == null) item { MarkdownPlaceholder(source) }
                else itemsIndexed(document, key = { index, _ -> "$messageId:block:$index" },
                    contentType = { _, block -> block.javaClass }) { _, block -> RenderBlocks(listOf(block), linkAction) }
            }
        } else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            blocks?.let { RenderBlocks(it, linkAction) } ?: MarkdownPlaceholder(source)
        }
    }
}
@Composable
private fun RenderBlocks(blocks: List<MarkdownBlock>, onLink: (String) -> Unit) {
    blocks.forEach { block ->
        when (block) {
            is MarkdownBlock.Paragraph -> {
                val size = when (block.heading) { 1 -> 23; 2 -> 20; 3 -> 18; 4, 5, 6 -> 16; else -> LocalMarkdownStyle.current.bodySize }
                if (block.runs.any { it.image != null }) {
                    block.runs.forEach { run ->
                        if (run.image != null) MarkdownImage(run.image, run.text, onLink)
                        else ParagraphText(listOf(run), size, block.heading, onLink)
                    }
                } else ParagraphText(block.runs, size, block.heading, onLink)
            }
            is MarkdownBlock.Code -> if (block.language.lowercase() == "mermaid") MermaidBlock(block.text) else CodeBlock(block)
            is MarkdownBlock.Math -> RichBitmap(if (block.display) "math-display" else "math", block.source)
            is MarkdownBlock.Quote -> Row(Modifier.fillMaxWidth().background(ChatColors.Bar)) {
                Box(Modifier.width(3.dp).heightIn(min = 32.dp).background(ChatColors.Line))
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { RenderBlocks(block.blocks, onLink) }
            }
            is MarkdownBlock.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                block.items.forEachIndexed { index, item -> Row {
                    val checked = block.checks.getOrNull(index)
                    if (checked == null) Text(block.start?.let { "${it + index}. " } ?: "• ", modifier = Modifier.widthIn(min = 22.dp))
                    else Checkbox(checked = checked, onCheckedChange = null, enabled = false,
                        modifier = Modifier.width(22.dp).height(20.dp).semantics {
                            role = Role.Checkbox
                            toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
                            disabled()
                        }, colors = CheckboxDefaults.colors(disabledCheckedColor = ChatColors.Link,
                            disabledUncheckedColor = ChatColors.Gray, checkmarkColor = ChatColors.Block))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) { RenderBlocks(item, onLink) }
                } }
            }
            is MarkdownBlock.Table -> Column(Modifier.horizontalScroll(rememberScrollState()).border(1.dp, ChatColors.LineInWhite)) {
                block.rows.forEachIndexed { index, row -> Row(Modifier.background(if (index == 0) ChatColors.Bar else Color.Transparent)) {
                    row.forEach { cell -> Text(richText(cell, onLink, LocalMarkdownStyle.current), Modifier.width(150.dp).padding(10.dp),
                        fontSize = 13.sp, fontWeight = if (index == 0) FontWeight.SemiBold else FontWeight.Normal) }
                }; HorizontalDivider(color = ChatColors.LineInWhite) }
            }
            MarkdownBlock.Rule -> HorizontalDivider(color = ChatColors.LineInWhite)
            MarkdownBlock.Error -> Text("这条消息暂时无法显示。内容更新后会自动重试。",
                Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = ChatColors.Red, fontSize = 12.sp)
        }
    }
}
private fun richText(runs: List<MarkdownRun>, onLink: (String) -> Unit, markdown: MarkdownStyle): AnnotatedString = buildAnnotatedString {
    runs.forEachIndexed { index, run ->
        if (run.math != null) { appendInlineContent("math-$index", run.text); return@forEachIndexed }
        val style = SpanStyle(
            fontWeight = if (run.bold) FontWeight.Bold else null,
            fontStyle = if (run.italic) FontStyle.Italic else null,
            fontFamily = if (run.code) FontFamily.Monospace else null,
            background = if (run.code) markdown.code else Color.Unspecified,
            color = if (run.link != null) markdown.link else Color.Unspecified,
            textDecoration = if (run.strike) TextDecoration.LineThrough else null,
        )
        withStyle(style) {
            if (run.link != null) withLink(LinkAnnotation.Clickable(run.link, TextLinkStyles(SpanStyle(color = markdown.link))) {
                onLink(run.link)
            }) { append(run.text) } else append(run.text)
        }
    }
}
@Composable
fun CodeBlock(block: MarkdownBlock.Code) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(block.text) { mutableStateOf(false) }
    val style = LocalMarkdownStyle.current
    var expanded by remember(block.text) { mutableStateOf(false) }
    val foldable = style.codeMaxLines > 0 && block.lineCount > style.codeMaxLines
    val folded = foldable && !expanded
    val codeLine = if (style.codeMaxLines > 0) 19.sp else 18.sp
    val foldHeight = with(LocalDensity.current) { (codeLine * style.codeMaxLines).toDp() } + 10.dp
    Surface(color = style.code, shape = RoundedCornerShape(4.dp), modifier = Modifier.fillMaxWidth()) {
        Column {
            // Chat code (ChatStates ⑧) is a plain grey mono block; copy lives in the long-press menu.
            if (style.codeMaxLines == 0) Row(Modifier.fillMaxWidth().padding(start = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(block.language.ifEmpty { "text" }, Modifier.padding(top = 12.dp), fontSize = 11.sp, color = ChatColors.Gray)
                TextButton(onClick = { clipboard.setText(AnnotatedString(block.text)); copied = true }) { Text(if (copied) "已复制" else "复制", fontSize = 11.sp) }
            }
            Row(Modifier.then(if (folded) Modifier.height(foldHeight).clipToBounds().drawWithContent {
                    drawContent()
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, style.code), startY = size.height - 28.dp.toPx(), endY = size.height))
                } else Modifier).horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = if (style.codeMaxLines == 0) 0.dp else 10.dp,
                    bottom = if (foldable) 0.dp else 10.dp)) {
                if (block.lineCount >= 5 && style.codeMaxLines == 0) Text(block.lineNumbers,
                    Modifier.padding(end = 12.dp), color = ChatColors.Gray, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp)
                Text(highlightedText(block.tokens.ifEmpty { listOf(CodeToken(block.text)) }), fontFamily = FontFamily.Monospace,
                    fontSize = if (style.codeMaxLines > 0) 13.sp else 12.sp, lineHeight = codeLine)
            }
            if (foldable) Text(if (expanded) "收起" else "展开全部 · ${block.lineCount} 行 ›", Modifier.fillMaxWidth().heightIn(min = 40.dp)
                .clickable { expanded = !expanded }.padding(horizontal = 12.dp).wrapContentHeight(), fontSize = 13.sp, color = ChatColors.Gray)
        }
    }
}

@Composable
private fun MarkdownPlaceholder(source: String) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val lines = estimateVisualLines(source, (maxWidth.value / 7).toInt().coerceAtLeast(12))
        Box(Modifier.fillMaxWidth().height((lines * 21).dp).background(ChatColors.Bar.copy(alpha = .5f)))
    }
}

private fun highlightedText(tokens: List<CodeToken>) = buildAnnotatedString {
    tokens.forEach { token ->
        val color = when (token.tone) {
            CodeTone.Plain -> ChatColors.Ink; CodeTone.Comment -> ChatColors.Gray
            CodeTone.String -> Color(0xFF18713F); CodeTone.Keyword -> Color(0xFF6750A4); CodeTone.Number -> Color(0xFFA94700)
        }
        withStyle(SpanStyle(color = color)) { append(token.text) }
    }
}
@Composable
private fun ParagraphText(runs: List<MarkdownRun>, size: Int, heading: Int, onLink: (String) -> Unit) {
    val density = LocalDensity.current
    // Inline placeholders also use cached dimensions on their first frame.
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val availableWidth = with(density) { maxWidth.roundToPx() }
        val inlineContent = runs.mapIndexedNotNull { index, run -> run.math?.let { fragment ->
            val result = rememberRichResult("math", fragment.source, availableWidth)
            val bitmap = result?.bitmap
            val scale = bitmap?.let { minOf(1f, availableWidth.toFloat() / it.width) } ?: 1f
            val width = bitmap?.let { with(density) { (it.width * scale).toSp() } } ?: (fragment.source.length * 9).coerceIn(40, 180).sp
            val height = bitmap?.let { with(density) { (it.height * scale).toSp() } } ?: 28.sp
            "math-$index" to InlineTextContent(Placeholder(width, height, PlaceholderVerticalAlign.Center)) {
                if (bitmap != null) Image(bitmap.asImageBitmap(), "数学公式", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                else Text(if (result?.error != null) "公式语法有误" else "…", fontSize = 11.sp, color = ChatColors.Gray)
            }
        } }.toMap()
        val style = LocalMarkdownStyle.current
        Text(richText(runs, onLink, style), inlineContent = inlineContent, fontSize = size.sp,
            lineHeight = if (heading == 0) style.bodyLineHeight.sp else (size * 1.5).sp,
            fontWeight = if (heading > 0) FontWeight.SemiBold else FontWeight.Normal)
    }
}
@Composable
fun MermaidBlock(source: String) {
    var showSource by rememberSaveable(source) { mutableStateOf(false) }
    Surface(shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, ChatColors.LineInWhite)) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("mermaid", Modifier.weight(1f), fontSize = 11.sp, color = ChatColors.Gray)
                TextButton({ showSource = !showSource }) { Text(if (showSource) "查看图表" else "查看源码", fontSize = 12.sp) }
            }
            if (showSource) CodeBlock(MarkdownBlock.Code(source, "mermaid")) else RichBitmap("mermaid", source)
        }
    }
}
@Composable
fun MarkdownImage(source: String, alt: String, onOpen: (String) -> Unit = {}) {
    Surface(Modifier.fillMaxWidth().aspectRatio(16f / 9).clickable { onOpen(source) }, shape = RoundedCornerShape(8.dp), color = ChatColors.Bar) {
        SubcomposeAsyncImage(model = source, contentDescription = alt.ifEmpty { "图片" }, contentScale = ContentScale.Fit,
            loading = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("正在加载图片…", color = ChatColors.Gray, fontSize = 12.sp) } },
            error = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("图片无法加载", color = ChatColors.Gray, fontSize = 12.sp) } })
    }
}

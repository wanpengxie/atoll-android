package dev.atoll.app.ui.components

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.commonmark.node.*
import org.commonmark.parser.Parser
import org.commonmark.ext.gfm.tables.*
import org.commonmark.ext.gfm.strikethrough.*
import org.commonmark.ext.autolink.AutolinkExtension

/** Pure display tree. CommonMark nodes never escape the background parser. */
data class MarkdownRun(val text: String, val bold: Boolean = false, val italic: Boolean = false,
    val code: Boolean = false, val strike: Boolean = false, val link: String? = null)
sealed interface MarkdownBlock {
    data class Paragraph(val runs: List<MarkdownRun>, val heading: Int = 0) : MarkdownBlock
    data class Code(val text: String, val language: String) : MarkdownBlock
    data class Quote(val blocks: List<MarkdownBlock>) : MarkdownBlock
    data class ListBlock(val items: List<List<MarkdownBlock>>, val start: Int? = null) : MarkdownBlock
    data class Table(val rows: List<List<List<MarkdownRun>>>, val alignments: List<String?>) : MarkdownBlock
    data object Rule : MarkdownBlock
}
class MarkdownParser {
    private val parser = Parser.builder().extensions(listOf(TablesExtension.create(), StrikethroughExtension.create(), AutolinkExtension.create())).build()
    fun parse(text: String): List<MarkdownBlock> = blocks(parser.parse(text))
    private fun children(node: Node): List<Node> = buildList {
        var child = node.firstChild
        while (child != null) { add(child); child = child.next }
    }
    private fun blocks(parent: Node): List<MarkdownBlock> = children(parent).flatMap { n ->
        when (n) {
            is Paragraph -> listOf(MarkdownBlock.Paragraph(inline(n)))
            is Heading -> listOf(MarkdownBlock.Paragraph(inline(n), n.level))
            is FencedCodeBlock -> listOf(MarkdownBlock.Code(n.literal.trimEnd('\n'), n.info.orEmpty().substringBefore(' ')))
            is IndentedCodeBlock -> listOf(MarkdownBlock.Code(n.literal.trimEnd('\n'), ""))
            is BlockQuote -> listOf(MarkdownBlock.Quote(blocks(n)))
            is BulletList -> listOf(MarkdownBlock.ListBlock(children(n).map(::blocks)))
            is OrderedList -> listOf(MarkdownBlock.ListBlock(children(n).map(::blocks), n.markerStartNumber))
            is ThematicBreak -> listOf(MarkdownBlock.Rule)
            is TableBlock -> {
                val rows = children(n).flatMap(::children)
                val alignment = rows.firstOrNull()?.let(::children)?.map { (it as TableCell).alignment?.name }
                listOf(MarkdownBlock.Table(rows.map { row -> children(row).map(::inline) }, alignment.orEmpty()))
            }
            is HtmlBlock -> listOf(MarkdownBlock.Paragraph(listOf(MarkdownRun(n.literal))))
            else -> blocks(n)
        }
    }
    private fun inline(parent: Node, style: MarkdownRun = MarkdownRun("")): List<MarkdownRun> = children(parent).flatMap { n ->
        when (n) {
            is Text -> listOf(style.copy(text = n.literal))
            is Code -> listOf(style.copy(text = n.literal, code = true))
            is SoftLineBreak -> listOf(style.copy(text = "\n"))
            is HardLineBreak -> listOf(style.copy(text = "\n"))
            is StrongEmphasis -> inline(n, style.copy(bold = true))
            is Emphasis -> inline(n, style.copy(italic = true))
            is Strikethrough -> inline(n, style.copy(strike = true))
            is Link -> inline(n, style.copy(link = n.destination))
            is Image -> inline(n, style.copy(link = n.destination)).map { it.copy(text = "图片：${it.text}") }
            is HtmlInline -> listOf(style.copy(text = n.literal))
            else -> inline(n, style)
        }
    }
}
/** Bounded per-message LRU; edits replace the previous parsed text for that id. */
class MarkdownCache(private val maxEntries: Int = 250, private val maxCharacters: Int = 1_000_000) {
    private data class Entry(val source: String, val blocks: List<MarkdownBlock>)
    private val cache = LinkedHashMap<String, Entry>(16, .75f, true)
    private val mutex = Mutex()
    private var characters = 0
    init { require(maxEntries > 0 && maxCharacters > 0) }
    suspend fun get(messageId: String, source: String): List<MarkdownBlock> = withContext(Dispatchers.Default) {
        mutex.withLock {
            cache[messageId]?.takeIf { it.source == source }?.blocks
        }?.let { return@withContext it }
        // Parsing unrelated messages must not hold the cache lock.
        val blocks = MarkdownParser().parse(source)
        mutex.withLock {
            cache[messageId]?.takeIf { it.source == source }?.let { return@withLock it.blocks }
            cache.remove(messageId)?.let { characters -= it.source.length }
            if (source.length <= maxCharacters) {
                cache[messageId] = Entry(source, blocks)
                characters += source.length
                while (cache.size > maxEntries || characters > maxCharacters) {
                    val oldest = cache.entries.iterator()
                    characters -= oldest.next().value.source.length
                    oldest.remove()
                }
            }
            blocks
        }
    }
}

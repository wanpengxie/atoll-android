package dev.atoll.app.ui

import dev.atoll.app.ui.components.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class MarkdownTest {
    @Test fun parsesStructuredMarkdownAndPreservesFileTargets() {
        val blocks = MarkdownParser().parse("# Title\n\n**strong** *em* `code` ~~gone~~ [file](/tmp/file.kt:12)\n\n> quote\n\n1. first\n2. second\n\n```kotlin\nval a = 1\n```\n\n| A | B |\n| --- | --- |\n| one | two |")
        assertEquals(1, (blocks[0] as MarkdownBlock.Paragraph).heading)
        val spans = (blocks[1] as MarkdownBlock.Paragraph).runs
        assertTrue(spans.any { it.bold && it.text == "strong" })
        assertTrue(spans.any { it.italic && it.text == "em" })
        assertTrue(spans.any { it.code && it.text == "code" })
        assertTrue(spans.any { it.strike && it.text == "gone" })
        assertEquals("/tmp/file.kt:12", spans.single { it.link != null }.link)
        assertTrue(blocks[2] is MarkdownBlock.Quote)
        assertEquals(2, (blocks[3] as MarkdownBlock.ListBlock).items.size)
        assertEquals("kotlin", (blocks[4] as MarkdownBlock.Code).language)
        assertEquals(2, (blocks[5] as MarkdownBlock.Table).rows.size)
    }
    @Test fun cacheReusesUnchangedMessagesReplacesEditsAndEvictsOldest() = runTest {
        val cache = MarkdownCache(maxEntries = 2)
        val first = cache.get("a", "hello")
        assertSame(first, cache.get("a", "hello"))
        assertNotSame(first, cache.get("a", "edited"))
        val edited = cache.get("a", "edited")
        cache.get("b", "second")
        cache.get("c", "third")
        assertNotSame(edited, cache.get("a", "edited"))
    }
    @Test fun hugeMessagesAreParsedWithoutBeingRetained() = runTest {
        val cache = MarkdownCache(maxCharacters = 4)
        val first = cache.get("a", "too long")
        assertNotSame(first, cache.get("a", "too long"))
    }
}

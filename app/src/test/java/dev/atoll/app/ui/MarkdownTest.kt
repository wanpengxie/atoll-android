package dev.atoll.app.ui

import dev.atoll.app.ui.components.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class MarkdownTest {
    @Test fun failedParseIsCachedLocallyAndAnEditRetriesIt() = runTest {
        var calls = 0
        val cache = MarkdownCache(parseDocument = { source ->
            calls++
            if (source == "broken") throw IllegalArgumentException("Malformed document")
            MarkdownParser().parse(source)
        })
        val fallback = cache.get("bad", "broken")
        assertEquals(listOf(MarkdownBlock.Error), fallback)
        assertSame(fallback, cache.peek("bad", "broken"))
        assertSame(fallback, cache.get("bad", "broken"))
        assertEquals(1, calls)
        assertTrue(cache.get("neighbor", "readable").single() is MarkdownBlock.Paragraph)
        assertTrue(cache.get("bad", "fixed").single() is MarkdownBlock.Paragraph)
        assertEquals(3, calls)
        assertNull(cache.peek("bad", "broken"))
    }
    @Test fun cancelledParseIsNotTreatedAsBadContentOrCached() = runTest {
        val cache = MarkdownCache(parseDocument = { throw CancellationException("Cancelled") })
        try { cache.get("a", "content"); fail("Cancellation must escape the parser boundary") }
        catch (_: CancellationException) { /* coroutine lifecycle remains intact */ }
        assertNull(cache.peek("a", "content"))
    }
    @Test fun taskMarkersBelongToTheirOwnItemAndPreserveRichText() {
        val list = MarkdownParser().parse("- [x] **done**\n- [ ] pending\n- normal\n  - [X] nested\n  - [ ] child").single() as MarkdownBlock.ListBlock
        assertEquals(listOf(true, false, null), list.checks)
        val done = list.items[0].single() as MarkdownBlock.Paragraph
        assertEquals("done", done.runs.joinToString("") { it.text })
        assertTrue(done.runs.single { it.text.isNotEmpty() }.bold)
        val nested = list.items[2][1] as MarkdownBlock.ListBlock
        assertEquals(listOf(true, false), nested.checks)
    }
    @Test fun orderedTasksKeepNumberingAndCodeIsNotATaskMarker() {
        val list = MarkdownParser().parse("4. [ ] todo\n5. `[x] literal`\n6. ordinary").single() as MarkdownBlock.ListBlock
        assertEquals(4, list.start)
        assertEquals(listOf(false, null, null), list.checks)
        val literal = (list.items[1].single() as MarkdownBlock.Paragraph).runs.single()
        assertTrue(literal.code)
        assertEquals("[x] literal", literal.text)
        val paragraph = MarkdownParser().parse("[x] outside a list").single() as MarkdownBlock.Paragraph
        assertEquals("[x] outside a list", paragraph.runs.joinToString("") { it.text })
    }
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

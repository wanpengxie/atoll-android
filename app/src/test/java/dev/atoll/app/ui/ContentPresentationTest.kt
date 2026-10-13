package dev.atoll.app.ui

import dev.atoll.app.ui.components.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ContentPresentationTest {
    @Test fun mathDelimitersPreserveCodeAndEscapedCurrency() {
        val parsed = MarkdownParser().parse("Inline \\(x^2\\) and \$y\$\n\n\\[\\frac{1}{2}\\]\n\n`\$z\$`\n\n```kotlin\nval s = \"\$k\$\"\n```")
        val runs = (parsed[0] as MarkdownBlock.Paragraph).runs
        assertEquals(listOf("x^2", "y"), runs.mapNotNull { it.math?.source })
        assertEquals("\\frac{1}{2}", (parsed[1] as MarkdownBlock.Math).source)
        assertTrue((parsed[2] as MarkdownBlock.Paragraph).runs.any { it.code && it.text == "$" + "z$" })
        assertTrue((parsed[3] as MarkdownBlock.Code).text.contains("$" + "k$"))
        assertTrue(prepareMath("escaped \\$" + "x$ and unmatched $" + "z").fragments.isEmpty())
    }
    @Test fun codeHighlightingPreservesExactTextAndHasPlainFallback() {
        val source = "// note\nval count = 3\nreturn \"ok\""
        val tokens = CodeHighlighter.highlight(source, "kt")
        assertEquals(source, tokens.joinToString("") { it.text })
        assertTrue(tokens.any { it.tone == CodeTone.Keyword && it.text == "val" })
        assertTrue(tokens.any { it.tone == CodeTone.String })
        assertTrue(tokens.any { it.tone == CodeTone.Number })
        assertEquals(listOf(CodeToken(source)), CodeHighlighter.highlight(source, "unknown"))
    }
    @Test fun imageTargetsStayOutOfTextLinks() {
        val runs = (MarkdownParser().parse("![图示](https://example.test/image.png)")[0] as MarkdownBlock.Paragraph).runs
        assertEquals("https://example.test/image.png", runs.single().image)
        assertNull(runs.single().link)
    }
    @Test fun cacheCanSynchronouslyServeRevisitsWithoutStaleEdits() = runTest {
        val cache = MarkdownCache()
        assertNull(cache.peek("id", "text"))
        val parsed = cache.get("id", "text")
        assertSame(parsed, cache.peek("id", "text"))
        assertNull(cache.peek("id", "edited"))
        val edited = cache.get("id", "edited")
        assertSame(edited, cache.peek("id", "edited"))
        assertNull(cache.peek("id", "text"))
    }
    @Test fun foldingKeepsLatestAndBoundsUtf8ToolOutput() {
        val text = "长正文\n".repeat(32)
        assertTrue(shouldFoldBody(text, 32, false))
        assertFalse(shouldFoldBody(text, 32, true))
        assertFalse(shouldFoldBody("short", 1, false))
        val output = boundedToolOutput("界面测试".repeat(2000))
        assertTrue(output.toByteArray().size <= 4096)
        assertFalse(output.contains('\uFFFD'))
        assertEquals("接收方已不可用", failureTitle("receiver_unavailable"))
        assertEquals("请求失败", failureTitle("unknown"))
    }
}

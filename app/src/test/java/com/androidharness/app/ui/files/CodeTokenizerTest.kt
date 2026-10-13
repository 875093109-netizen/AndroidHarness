package com.androidharness.app.ui.files

import org.junit.Assert.*
import org.junit.Test

class CodeTokenizerTest {
    @Test fun `css class selectors and partial dots always consume input`() {
        for (line in listOf(".hero { color: green; }", ".", "..", ".hero:hover", ".".repeat(500))) {
            val tokens = CodeTokenizer.tokenizeLine(CodeTokenizer.langFor("styles.css"), line)
            assertTrue(tokens.isNotEmpty())
            assertTrue(tokens.size <= line.length)
            assertTrue(tokens.all { it.start >= 0 && it.end > it.start && it.end <= line.length })
        }
        val css = ".hero { color: green; }"
        val tokens = CodeTokenizer.tokenizeLine(CodeTokenizer.langFor("styles.css"), css)
        assertEquals(".hero", css.substring(tokens.first().start, tokens.first().end))
        assertTrue(tokens.any { it.type == TokenType.ATTRIBUTE_NAME && css.substring(it.start, it.end) == "color" })
    }
}

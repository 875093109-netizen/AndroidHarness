package com.androidharness.app.ui.files

import org.junit.Assert.*
import org.junit.Test

class SelectedCodeTest {
    @Test fun capturesExactPartialLinesAndExcludesUnselectedFinalLine() {
        val text = "before\n  first()\n  second()\nafter"
        val start = text.indexOf("first")
        val end = text.indexOf("after")
        val selected = SelectedCode.capture("src/example.kt", text, start, end, false)!!
        assertEquals("first()\n  second()\n", selected.text)
        assertEquals(2, selected.startLine)
        assertEquals(3, selected.endLine)
        assertTrue(selected.prompt(CodeAction.FIX).contains("Lines: 2–3"))
        assertFalse(selected.prompt(CodeAction.FIX).contains("before"))
        assertFalse(selected.prompt(CodeAction.FIX).contains("after"))
    }

    @Test fun handlesCrlfUnicodeAndSelectionsWithoutTrailingNewline() {
        val text = "one\r\n💡 two\r\nthree"
        val selected = SelectedCode.capture("test.txt", text, 5, text.length, false)!!
        assertEquals(2, selected.startLine)
        assertEquals(3, selected.endLine)
        assertEquals("💡 two\r\nthree", selected.text)
    }

    @Test fun neverSilentlyTruncatesOrAcceptsInvalidSelection() {
        val text = "x".repeat(SelectedCode.MAX_CHARS + 1)
        assertNull(SelectedCode.capture("test", text, 0, text.length, false))
        assertNull(SelectedCode.capture("test", text, 5, 5, false))
        assertNull(SelectedCode.capture("test", text, -1, 3, false))
        assertNull(SelectedCode.capture("test", text, 0, text.length + 1, false))
        assertEquals(SelectedCode.MAX_CHARS, SelectedCode.capture("test", text, 0, SelectedCode.MAX_CHARS, false)!!.text.length)
    }

    @Test fun preservesEmbeddedMarkdownFencesAndUnsavedBufferContext() {
        val text = "```kotlin\nval x = 1\n```"
        val selection = SelectedCode.capture("readme.md", text, 0, text.length, true)!!
        CodeAction.entries.forEach { action ->
            val prompt = selection.prompt(action)
            assertTrue(prompt.contains("unsaved editor buffer"))
            assertTrue(prompt.contains("````\n$text\n````"))
            assertTrue(prompt.startsWith(action.instruction))
        }
        assertTrue(selection.prompt(CodeAction.EXPLAIN).contains("Do not edit files"))
    }
}

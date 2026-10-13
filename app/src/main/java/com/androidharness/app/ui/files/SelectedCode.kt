package com.androidharness.app.ui.files

data class SelectedCode(val path: String, val startLine: Int, val endLine: Int, val text: String, val unsaved: Boolean) {
    val location: String get() = if (startLine == endLine) "$path · line $startLine" else "$path · lines $startLine–$endLine"

    fun prompt(action: CodeAction): String {
        val fence = "`".repeat(maxOf(3, (Regex("`+").findAll(text).maxOfOrNull { it.value.length } ?: 0) + 1))
        return "${action.instruction}\n\nFile: $path\nLines: $startLine–$endLine\n" +
            (if (unsaved) "This is a selection from an unsaved editor buffer. Check the current file before making changes.\n" else "") +
            "Keep the work focused on this selection; read surrounding code if needed.\n\n$fence\n$text\n$fence"
    }

    companion object {
        const val MAX_CHARS = 64_000
        fun capture(path: String, text: String, start: Int, end: Int, unsaved: Boolean): SelectedCode? {
            if (start < 0 || end > text.length || start >= end || end - start > MAX_CHARS) return null
            var first = 1
            for (index in 0 until start) if (text[index] == '\n') first++
            var last = first
            for (index in start until end) if (text[index] == '\n') last++
            if (text[end - 1] == '\n') last--
            return SelectedCode(path, first, last.coerceAtLeast(first), text.substring(start, end), unsaved)
        }
    }
}

enum class CodeAction(val label: String, val description: String, val instruction: String) {
    EXPLAIN("Explain", "Understand what this code does", "Explain the selected code and any important assumptions. Do not edit files."),
    FIX("Fix", "Find and fix problems in the selection", "Find and fix bugs in the selected code. Explain the issue and verify the fix."),
    REFACTOR("Refactor", "Improve clarity while keeping its behavior", "Refactor the selected code for clarity while preserving its behavior. Verify the result."),
    TESTS("Write tests", "Cover this code's behavior and edge cases", "Write meaningful tests for the selected code, including relevant edge cases. Run the tests if possible."),
}

/** A targeted composer handoff; an unrelated mounted chat must never consume it. */
data class CodeChatDraft(val sessionId: String, val prompt: String)

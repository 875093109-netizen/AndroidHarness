package com.androidharness.app.agent

import com.androidharness.app.core.ToolCallData
import com.androidharness.app.llm.StreamEvent
import com.androidharness.app.workspace.FileFs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LiveCodeTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `incomplete escaped strings decode without exposing JSON or false paths`() = runBlocking {
        val raw = """{"path":"src/Main.kt","content":"val s = \"path\": \"fake.kt\"\n// \u263a\nval x = \""""
        val draft = LiveCodePreview.build("a", "write_file", raw, false)!!
        assertEquals("src/Main.kt", draft.files.single().path)
        assertTrue(draft.files.single().lines.any { it.text == "// ☺" })
        assertEquals(2, draft.files.single().added)
        assertTrue(draft.files.single().lines.last().text.contains("val x"))
        assertEquals(CodeDraftStage.GENERATING, draft.stage)
    }

    @Test fun `every complete generated line increments once and final unterminated line counts`() = runBlocking {
        for (n in 1..5) {
            val raw = "{\"path\":\"a.py\",\"content\":\"" + "print(1)\\n".repeat(n)
            assertEquals(n, LiveCodePreview.build("a", "write_file", raw, false)!!.files.single().added)
        }
        val raw = """{"path":"a.py","content":"one\ntwo"}"""
        assertEquals(2, LiveCodePreview.build("a", "write_file", raw, true)!!.files.single().added)
    }

    @Test fun `partial rewrite does not call the ungenerated tail deleted`() = runBlocking {
        val raw = """{"path":"a.kt","content":"same\n"""
        val file = LiveCodePreview.build("a", "write_file", raw, false, "same\nlater\n")!!.files.single()
        assertEquals(0, file.added)
        assertEquals(0, file.removed)
        val done = LiveCodePreview.build("a", "write_file", "$raw\"}", true, "same\nlater\n")!!.files.single()
        assertEquals(1, done.removed)
    }

    @Test fun `replacement shows added and removed lines and unchanged replacement is zero`() = runBlocking {
        val raw = buildJsonObject {
            put("path", JsonPrimitive("a.ts")); put("old_string", JsonPrimitive("old1\nold2\nold3\nold4"))
            put("new_string", JsonPrimitive("new1\nnew2"))
        }.toString()
        val file = LiveCodePreview.build("a", "edit_file", raw, true)!!.files.single()
        assertEquals(2, file.added); assertEquals(4, file.removed)
        val unchanged = raw.replace("new1\\nnew2", "old1\\nold2\\nold3\\nold4")
        val same = LiveCodePreview.build("a", "edit_file", unchanged, true)!!.files.single()
        assertEquals(0, same.added); assertEquals(0, same.removed)
    }

    @Test fun `patch separates files and ignores headers when counting changes`() = runBlocking {
        val patch = "--- a/one.kt\n+++ b/one.kt\n@@ -1,1 +1,2 @@\n-old\n+new\n+extra\n--- a/two.py\n+++ b/two.py\n@@ -1,2 +1,1 @@\n-a\n-b\n+c\n"
        val raw = buildJsonObject { put("patch", JsonPrimitive(patch)) }.toString()
        val files = LiveCodePreview.build("p", "apply_patch", raw, true)!!.files
        assertEquals(listOf("one.kt", "two.py"), files.map { it.path })
        assertEquals(listOf(2, 1), files.map { it.added })
        assertEquals(listOf(1, 2), files.map { it.removed })
    }

    @Test fun `tracker retains throttled fragments and resets object snapshots`() = runBlocking {
        var time = 1L
        val tracker = LiveCodeTracker { time }
        val fs = FileFs(temp.root)
        tracker.delta(StreamEvent.ToolCallDelta("a", "write_file", "{\"path\":\"a.kt\",\"content\":\"one\\n"), fs)
        assertNull(tracker.delta(StreamEvent.ToolCallDelta("a", "write_file", "two\\n"), fs))
        time += 100_000_000
        val draft = tracker.delta(StreamEvent.ToolCallDelta("a", "write_file", "three\\n"), fs)!!
        assertEquals(3, draft.files.single().added)
        time += 100_000_000
        val replacement = tracker.delta(StreamEvent.ToolCallDelta("a", "write_file", """{"path":"b.kt","content":"other\n"}""", true), fs)!!
        assertEquals("b.kt", replacement.files.single().path)
        assertEquals(1, replacement.files.single().added)
        assertFalse(temp.root.resolve("a.kt").exists())
    }

    @Test fun `parallel calls and attempt reset never mix drafts or write files`() = runBlocking {
        val tracker = LiveCodeTracker()
        val fs = FileFs(temp.root)
        val a = tracker.ready(ToolCallData("a", "write_file", """{"path":"a.kt","content":"a\n"}"""), fs)!!
        val b = tracker.ready(ToolCallData("b", "write_file", """{"path":"b.py","content":"b\nb\n"}"""), fs)!!
        assertEquals(1, a.files.single().added); assertEquals(2, b.files.single().added)
        tracker.clear()
        assertNull(tracker.delta(StreamEvent.ToolCallDelta("c", "shell", "touch c"), fs))
        assertTrue(temp.root.listFiles().orEmpty().isEmpty())
    }

    @Test fun `partial unicode escape is held until complete`() {
        assertEquals("", LiveCodePreview.fields("{\"content\":\"\\u26").single().text)
        assertEquals("☺", LiveCodePreview.fields("{\"content\":\"\\u263a").single().text)
    }

    @Test fun `patch body that resembles a header remains a changed code line`() = runBlocking {
        val patch = "--- a/a.kt\n+++ b/a.kt\n@@ -1,1 +1,1 @@\n--- comment\n+++ comment\n"
        val raw = buildJsonObject { put("patch", JsonPrimitive(patch)) }.toString()
        val file = LiveCodePreview.build("p", "apply_patch", raw, true)!!.files.single()
        assertEquals("a.kt", file.path); assertEquals(1, file.added); assertEquals(1, file.removed)
    }

    @Test fun `fragmented tool name retains early arguments and nested metadata cannot choose the file`() = runBlocking {
        val tracker = LiveCodeTracker()
        val fs = FileFs(temp.root)
        assertNull(tracker.delta(StreamEvent.ToolCallDelta("a", "write_", "{\"metadata\":{\"path\":\"wrong.kt\"},\"path\":\"right.kt\",\"content\":\"one\\n"), fs))
        val draft = tracker.delta(StreamEvent.ToolCallDelta("a", "write_file", "two\\n"), fs)!!
        assertEquals("right.kt", draft.files.single().path)
        assertEquals(2, draft.files.single().added)
    }
}

package com.androidharness.app.llm

import com.androidharness.app.core.*
import org.junit.Assert.*
import org.junit.Test

class CompatHistoryTest {
    private fun call(id: String) = ToolCallData(id, "shell", "{}")
    private fun result(id: String) = ChatMessage(role = Role.TOOL, toolCallId = id, text = "result-$id")
    @Test fun `complete parallel exchange stays unchanged`() {
        val messages = listOf(ChatMessage(role = Role.ASSISTANT, toolCalls = listOf(call("a"), call("b"))), result("b"), result("a"))
        assertEquals(messages, CompatHistory.normalize(messages))
    }
    @Test fun `missing duplicate and malformed calls become recorded text without changing originals`() {
        for (calls in listOf(listOf(call("a"), call("b")), listOf(call("a"), call("a")),
            listOf(call("a").copy(argumentsJson = "unfinished")))) {
            val original = listOf(ChatMessage(role = Role.ASSISTANT, toolCalls = calls), result("a"))
            val repaired = CompatHistory.normalize(original)
            assertTrue(repaired.all { it.role != Role.TOOL && it.toolCalls.isEmpty() })
            assertTrue(repaired.first().text.contains("result-a")); assertEquals(calls, original.first().toolCalls)
        }
    }
    @Test fun `orphan results and reused IDs cannot produce invalid wire history`() {
        val original = listOf(result("orphan"), ChatMessage(role = Role.ASSISTANT, toolCalls = listOf(call("a"))), result("a"),
            ChatMessage(role = Role.ASSISTANT, toolCalls = listOf(call("a"))), result("a"))
        val out = CompatHistory.normalize(original)
        assertEquals(Role.USER, out.first().role); assertEquals(1, out.count { it.toolCalls.isNotEmpty() })
        assertTrue(out.last().text.contains("result-a"))
    }
}

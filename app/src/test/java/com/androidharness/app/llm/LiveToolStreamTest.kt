package com.androidharness.app.llm

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LiveToolStreamTest {
    @Test fun responsesDeltasStayDisplayOnlyUntilCompleted() {
        val parser = OpenAiResponsesProvider(okhttp3.OkHttpClient(), ProviderFactory.json)
        val acc = linkedMapOf<String, Triple<String, String, StringBuilder>>()
        fun event(raw: String) = parser.parseEvent(ProviderFactory.json.parseToJsonElement(raw), acc)
        val started = event("""{"type":"response.output_item.added","item":{"id":"item","call_id":"call","type":"function_call","name":"write_file"}}""")
        assertEquals(StreamEvent.ToolCallDelta("call", "write_file", ""), started.single())
        val delta = event("""{"type":"response.function_call_arguments.delta","item_id":"item","delta":"{\"path\":\"a.kt\",\"content\":\"one\\n\"}"}""")
        assertTrue(delta.single() is StreamEvent.ToolCallDelta)
        assertFalse((started + delta).any { it is StreamEvent.ToolCallReady })
        val done = event("""{"type":"response.completed","response":{"usage":{"input_tokens":10,"output_tokens":20}}}""")
        assertEquals("call", done.filterIsInstance<StreamEvent.ToolCallReady>().single().call.id)
    }

    @Test fun anthropicArgumentFragmentsReachPreviewBeforeToolReady() = runBlocking {
        val parser = AnthropicProvider(okhttp3.OkHttpClient(), ProviderFactory.json)
        val raw = listOf(
            """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"call","name":"write_file"}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"path\":\"a.kt\",\"content\":\"one\\n\"}"}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"message_stop"}""",
        ).map { ProviderFactory.json.parseToJsonElement(it) }
        val events = parser.parseStream(flowOf(*raw.toTypedArray())).toList()
        assertEquals(2, events.filterIsInstance<StreamEvent.ToolCallDelta>().size)
        assertTrue(events.indexOfLast { it is StreamEvent.ToolCallDelta } < events.indexOfFirst { it is StreamEvent.ToolCallReady })
        assertEquals("call", events.filterIsInstance<StreamEvent.ToolCallReady>().single().call.id)
    }
}

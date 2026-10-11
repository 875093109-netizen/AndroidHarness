package com.androidharness.app.llm

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Repair only the request view of interrupted/legacy tool exchanges, leaving saved history intact. */
internal object CompatHistory {
    fun normalize(messages: List<ChatMessage>): List<ChatMessage> {
        val out = mutableListOf<ChatMessage>()
        val seen = mutableSetOf<String>()
        var i = 0
        while (i < messages.size) {
            val m = messages[i++]
            if (m.role == Role.ASSISTANT && m.toolCalls.isNotEmpty()) {
                val results = mutableListOf<ChatMessage>()
                while (i < messages.size && messages[i].role == Role.TOOL) results += messages[i++]
                val ids = m.toolCalls.map { it.id }
                val valid = ids.all { it.isNotBlank() && it !in seen } && ids.distinct().size == ids.size &&
                    m.toolCalls.all { it.name.isNotBlank() && runCatching { Json.parseToJsonElement(it.argumentsJson) is JsonObject }.getOrDefault(false) } &&
                    results.size == ids.size && results.map { it.toolCallId }.toSet() == ids.toSet()
                seen += ids
                if (valid) { out += m; out += results }
                else {
                    out += m.copy(toolCalls = emptyList(), text = buildString {
                        append(m.text)
                        append("\n[Earlier tool activity; recorded outcomes only, do not repeat automatically]\n")
                        m.toolCalls.forEach { append(it.name).append(": ").append(it.argumentsJson).append('\n') }
                        results.forEach { append(it.toolName ?: "tool").append(": ").append(it.text).append('\n') }
                        if (results.size < ids.size) append("Some outcomes are unknown; inspect current state before acting.\n")
                    })
                }
            } else if (m.role == Role.TOOL) {
                out += m.copy(role = Role.USER, toolCallId = null, toolName = null,
                    text = "[Recorded ${m.toolName ?: "tool"} result from earlier activity]\n${m.text}")
            } else out += m
        }
        return out
    }
}

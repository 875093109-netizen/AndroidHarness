package com.androidharness.app.data

import com.androidharness.app.llm.ProviderConfig
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** One order covers API connections, connected accounts, built-in and installed providers. */
object ProviderListPreferences {
    fun decodeOrder(raw: String?): List<String> = raw?.let {
        runCatching { Json.decodeFromString<List<String>>(it).distinct() }.getOrDefault(emptyList())
    }.orEmpty()

    fun encodeOrder(order: List<String>): String = Json.encodeToString(order.distinct())

    fun apply(providers: List<ProviderConfig>, order: List<String>, disabled: Set<String>): List<ProviderConfig> {
        val positions = order.distinct().withIndex().associate { it.value to it.index }
        return providers.distinctBy { it.id }.sortedBy { positions[it.id] ?: Int.MAX_VALUE }
            .map { it.copy(enabled = it.id !in disabled) }
    }

    fun move(order: List<String>, available: List<String>, visible: List<String>, id: String, direction: Int): List<String> {
        val complete = (order + available).distinct().toMutableList()
        val positions = complete.withIndex().associate { it.value to it.index }
        val shown = visible.distinct().filter { it in available }.sortedBy { positions[it] }
        val index = shown.indexOf(id)
        if (index < 0 || direction !in listOf(-1, 1)) return complete
        val neighbor = shown.getOrNull(index + direction) ?: return complete
        val from = complete.indexOf(id)
        val to = complete.indexOf(neighbor)
        complete[from] = neighbor
        complete[to] = id
        return complete
    }
}

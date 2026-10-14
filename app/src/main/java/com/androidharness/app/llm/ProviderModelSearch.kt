package com.androidharness.app.llm

import com.androidharness.app.data.ModelShortcut

data class ProviderModelResult(val provider: ProviderConfig, val model: ModelEntry, val available: Boolean) {
    val shortcut get() = ModelShortcut(provider.id, model.id)
}

object ProviderModelSearch {
    fun models(provider: ProviderConfig, catalog: List<ModelEntry>): List<ModelEntry> {
        val managed = com.androidharness.app.chatgpt.ChatGptProtocol.isProvider(provider.id)
        return (if (managed) catalog else listOf(catalog.firstOrNull { it.id == provider.model }
            ?: ModelEntry(provider.model)) + catalog).distinctBy { it.id }.filter { it.id.isNotBlank() }
    }

    fun results(
        providers: List<ProviderConfig>, catalogs: Map<String, List<ModelEntry>>, query: String,
        collection: String = "All", favourites: List<ModelShortcut> = emptyList(),
        recent: List<ModelShortcut> = emptyList(), thinkingOnly: Boolean = false,
    ): List<ProviderModelResult> {
        val clean = query.trim()
        val included = providers.filter { it.enabled || clean.isNotEmpty() }
        val available = included.flatMap { provider ->
            models(provider, catalogs[provider.id].orEmpty()).map { ProviderModelResult(provider, it, true) }
        }
        val byKey = available.associateBy { it.shortcut }
        val rows = if (collection == "All") available.sortedBy { if (it.shortcut in favourites) 0 else 1 }
        else (if (collection == "Favourites") favourites else recent.take(10)).mapNotNull { key ->
            byKey[key] ?: included.firstOrNull { it.id == key.providerId }?.let {
                ProviderModelResult(it, ModelEntry(key.modelId), false)
            }
        }.distinctBy { it.shortcut }
        return rows.filter { matches(it.provider, it.model, clean) &&
            (!thinkingOnly || (it.model.reasoning ?: reasoningCapable(it.model.id))) }
    }

    fun matches(provider: ProviderConfig, model: ModelEntry, query: String): Boolean =
        query.isBlank() || listOf(provider.name, model.id, model.displayName.orEmpty()).any { it.contains(query.trim(), ignoreCase = true) }

    fun providerMatches(provider: ProviderConfig, catalog: List<ModelEntry>, query: String): Boolean =
        query.isBlank() || provider.name.contains(query.trim(), ignoreCase = true) ||
            models(provider, catalog).any { matches(provider, it, query) }
}

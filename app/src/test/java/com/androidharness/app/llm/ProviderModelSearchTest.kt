package com.androidharness.app.llm

import com.androidharness.app.data.ModelShortcut
import org.junit.Assert.*
import org.junit.Test

class ProviderModelSearchTest {
    private val a = ProviderConfig("a", "Primary", ProviderType.OPENAI_COMPAT, "https://example.test", "shared")
    private val b = a.copy(id = "b", name = "Backup", enabled = false)
    private val catalogs = mapOf("a" to listOf(ModelEntry("shared", reasoning = true, displayName = "Shared Coder")),
        "b" to listOf(ModelEntry("shared", reasoning = false), ModelEntry("only-backup")))

    @Test fun matchingModelsKeepTheirProviderIdentityAndHiddenResultsAreSearchable() {
        val results = ProviderModelSearch.results(listOf(a, b), catalogs, " SHARED ")
        assertEquals(listOf("a", "b"), results.map { it.provider.id })
        assertEquals(2, results.map { it.shortcut }.distinct().size)
        assertTrue(ProviderModelSearch.results(listOf(a, b), catalogs, "").all { it.provider.id == "a" })
        assertEquals("b", ProviderModelSearch.results(listOf(a, b), catalogs, "only-backup").single().provider.id)
    }

    @Test fun searchSupportsProviderNamesAndDisplayNamesAndReasoningFilter() {
        assertEquals(2, ProviderModelSearch.results(listOf(a, b), catalogs, "BACKUP").size)
        assertEquals("a", ProviderModelSearch.results(listOf(a, b), catalogs, "Coder").single().provider.id)
        assertEquals("a", ProviderModelSearch.results(listOf(a, b), catalogs, "shared", thinkingOnly = true).single().provider.id)
        assertTrue(ProviderModelSearch.providerMatches(b, catalogs.getValue("b"), "only-backup"))
        assertTrue(ProviderModelSearch.providerMatches(b.copy(model = ""), emptyList(), "backup"))
    }

    @Test fun favouritesAndRecentModelsAreScopedToTheConnection() {
        val favourite = ModelShortcut("b", "shared")
        val rows = ProviderModelSearch.results(listOf(a, b), catalogs, "shared", "Favourites", listOf(favourite))
        assertEquals(listOf(favourite), rows.map { it.shortcut })
        val stale = ModelShortcut("a", "retired")
        val recent = ProviderModelSearch.results(listOf(a, b), catalogs, "", "Recent", recent = listOf(stale))
        assertEquals(stale, recent.single().shortcut)
        assertFalse(recent.single().available)
    }

    @Test fun accountCatalogDoesNotInventAnUnavailableDefault() {
        val managed = a.copy(id = "chatgpt:fixture", model = "retired")
        assertEquals(listOf("current"), ProviderModelSearch.models(managed, listOf(ModelEntry("current"))).map { it.id })
        assertTrue(ProviderModelSearch.models(managed, emptyList()).isEmpty())
    }
}

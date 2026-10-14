package com.androidharness.app.data

import com.androidharness.app.llm.ProviderConfig
import com.androidharness.app.llm.ProviderType
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.Json

class ProviderListPreferencesTest {
    private fun provider(id: String) = ProviderConfig(id, id, ProviderType.OPENAI_COMPAT, "https://example.test", "model")

    @Test fun orderCoversManagedProvidersAndAppendsNewConnections() {
        val list = listOf("chatgpt:account", "harness", "api", "local-model:installed", "new").map(::provider)
        val ordered = ProviderListPreferences.apply(list, listOf("api", "local-model:installed", "harness", "chatgpt:account"), setOf("api"))
        assertEquals(listOf("api", "local-model:installed", "harness", "chatgpt:account", "new"), ordered.map { it.id })
        assertFalse(ordered.first().enabled)
        assertTrue(ordered.drop(1).all { it.enabled })
        assertEquals(list.map { it.id }, ProviderListPreferences.apply(list, emptyList(), emptySet()).map { it.id })
    }

    @Test fun moveSkipsHiddenRowsAndPreservesDisconnectedProviderPositions() {
        val order = listOf("a", "hidden", "disconnected", "b", "c")
        val moved = ProviderListPreferences.move(order, listOf("a", "hidden", "b", "c"), listOf("a", "b", "c"), "b", -1)
        assertEquals(listOf("b", "hidden", "disconnected", "a", "c"), moved)
        assertEquals(moved, ProviderListPreferences.move(moved, moved, listOf("b", "a", "c"), "b", -1))
        assertEquals(order, ProviderListPreferences.move(order, order, order, "missing", 1))
    }

    @Test fun oldAndMalformedPreferencesRemainUsable() {
        assertEquals(emptyList<String>(), ProviderListPreferences.decodeOrder(null))
        assertEquals(emptyList<String>(), ProviderListPreferences.decodeOrder("invalid"))
        assertEquals(listOf("a", "b"), ProviderListPreferences.decodeOrder(ProviderListPreferences.encodeOrder(listOf("a", "a", "b"))))
        val old = Json.decodeFromString<AppSettings>("{}")
        assertTrue(old.providerOrder.isEmpty())
        assertTrue(old.disabledProviderIds.isEmpty())
    }

    @Test fun portableBackupFiltersDeviceProvidersAndRetainsListPreferences() {
        val settings = AppSettings(providerOrder = listOf("api", "harness", "local-model:x", "chatgpt:x"),
            disabledProviderIds = setOf("api", "local-model:x", "chatgpt:x"))
        val exported = SettingsBackupPolicy.exportSettings(settings, setOf("api", "harness"))
        assertEquals(listOf("api", "harness"), exported.providerOrder)
        assertEquals(setOf("api"), exported.disabledProviderIds)
        val decoded = Json.decodeFromString<AppSettings>(Json.encodeToString(AppSettings.serializer(), exported))
        assertEquals(exported.providerOrder, decoded.providerOrder)
        assertEquals(exported.disabledProviderIds, decoded.disabledProviderIds)
    }
}

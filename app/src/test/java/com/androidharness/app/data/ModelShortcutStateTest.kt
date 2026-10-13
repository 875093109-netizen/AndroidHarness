package com.androidharness.app.data

import org.junit.Assert.*
import org.junit.Test

class ModelShortcutStateTest {
    @Test fun sameModelAcrossProvidersHasIndependentFavourites() {
        val a = ModelShortcut("account-a", "same-model")
        val b = ModelShortcut("account-b", "same-model")
        val saved = ModelShortcutState().toggle(a).toggle(b).toggle(a)
        assertEquals(listOf(b), saved.favourites)
        assertTrue(saved.recent.isEmpty())
    }

    @Test fun recentSelectionMovesToFrontWithoutDuplicatesOrChangingFavourites() {
        val a = ModelShortcut("a", "model")
        val b = ModelShortcut("b", "model")
        val saved = ModelShortcutState().toggle(a).selected(a).selected(b).selected(a)
        assertEquals(listOf(a, b), saved.recent)
        assertEquals(listOf(a), saved.favourites)
    }

    @Test fun recentHistoryIsBoundedAcrossProviders() {
        var saved = ModelShortcutState()
        repeat(70) { saved = saved.selected(ModelShortcut("p${it % 3}", "m$it")) }
        assertEquals(50, saved.recent.size)
        assertEquals("m69", saved.recent.first().modelId)
        assertEquals("m20", saved.recent.last().modelId)
    }
}

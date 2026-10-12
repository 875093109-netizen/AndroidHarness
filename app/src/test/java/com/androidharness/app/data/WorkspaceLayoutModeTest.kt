package com.androidharness.app.data

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class WorkspaceLayoutModeTest {
    @Test fun phoneLayoutAlwaysUsesSeparateScreens() {
        for (width in listOf(360f, 800f, 1280f, 2560f)) assertFalse(useWorkspacePanes(WorkspaceLayoutMode.PHONE, width, 1f))
    }
    @Test fun autoAdaptsToWindowWidthAndEmulatorFitsLandscapePhones() {
        assertFalse(useWorkspacePanes(WorkspaceLayoutMode.AUTO, 839f, 1f))
        assertTrue(useWorkspacePanes(WorkspaceLayoutMode.AUTO, 840f, 1f))
        assertFalse(useWorkspacePanes(WorkspaceLayoutMode.EMULATOR, 360f, 1f))
        assertFalse(useWorkspacePanes(WorkspaceLayoutMode.EMULATOR, 719f, 1f))
        assertTrue(useWorkspacePanes(WorkspaceLayoutMode.EMULATOR, 720f, 1f))
    }
    @Test fun largeTextRequiresEnoughSpaceForBothPanes() {
        assertFalse(useWorkspacePanes(WorkspaceLayoutMode.AUTO, 1000f, 1.5f))
        assertTrue(useWorkspacePanes(WorkspaceLayoutMode.AUTO, 1260f, 1.5f))
        assertFalse(useWorkspacePanes(WorkspaceLayoutMode.EMULATOR, 800f, 1.5f))
        assertTrue(useWorkspacePanes(WorkspaceLayoutMode.EMULATOR, 1080f, 1.5f))
    }
    @Test fun preferenceSurvivesBackupAndOlderSettingsDefaultToAuto() {
        val json = Json { encodeDefaults = true }
        for (mode in WorkspaceLayoutMode.entries) {
            val settings = AppSettings(workspaceLayout = mode)
            assertEquals(mode, json.decodeFromString(AppSettings.serializer(), json.encodeToString(AppSettings.serializer(), settings)).workspaceLayout)
        }
        assertEquals(WorkspaceLayoutMode.AUTO, json.decodeFromString(AppSettings.serializer(), "{}").workspaceLayout)
    }
}

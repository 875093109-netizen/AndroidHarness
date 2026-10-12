package com.androidharness.app.data

import kotlinx.serialization.json.JsonNames

enum class WorkspaceLayoutMode(val label: String, val description: String) {
    AUTO("Auto", "Use the desktop workspace when the window is wide enough."),
    PHONE("Phone", "Open chat and workspace tools as separate screens."),
    @JsonNames("EMULATOR")
    DESKTOP("Desktop", "Desktop workspace with project navigation, files, changes and a terminal panel.");

    companion object {
        /** "EMULATOR" is the old name for DESKTOP; stored settings may still carry it. */
        fun fromStored(name: String?): WorkspaceLayoutMode? = when (name) {
            null -> null
            "EMULATOR" -> DESKTOP
            else -> entries.firstOrNull { it.name == name }
        }
    }
}

/** Width comes from the app window, not the device's orientation or pixel resolution. */
fun useWorkspacePanes(mode: WorkspaceLayoutMode, widthDp: Float, fontScale: Float): Boolean {
    val minimum = when (mode) {
        WorkspaceLayoutMode.PHONE -> return false
        WorkspaceLayoutMode.AUTO -> 840f
        WorkspaceLayoutMode.DESKTOP -> 720f
    }
    return widthDp >= minimum * fontScale.coerceAtLeast(1f)
}

package com.androidharness.app.data

enum class WorkspaceLayoutMode(val label: String, val description: String) {
    AUTO("Auto", "Use the desktop workspace when the window is wide enough."),
    PHONE("Phone", "Open chat and workspace tools as separate screens."),
    EMULATOR("Emulator", "Desktop workspace with project navigation, files, changes and a terminal panel."),
}

/** Width comes from the app window, not the device's orientation or pixel resolution. */
fun useWorkspacePanes(mode: WorkspaceLayoutMode, widthDp: Float, fontScale: Float): Boolean {
    val minimum = when (mode) {
        WorkspaceLayoutMode.PHONE -> return false
        WorkspaceLayoutMode.AUTO -> 840f
        WorkspaceLayoutMode.EMULATOR -> 720f
    }
    return widthDp >= minimum * fontScale.coerceAtLeast(1f)
}

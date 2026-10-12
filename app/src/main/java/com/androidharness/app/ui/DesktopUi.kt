package com.androidharness.app.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.androidharness.app.ui.files.EditorNavigationGuard

/** Shared desktop chrome for the conversation, navigation, and embedded workspace tools. */
internal val LocalDesktopLayout = staticCompositionLocalOf { false }
internal val LocalDesktopNavigationGuard = staticCompositionLocalOf<EditorNavigationGuard?> { null }

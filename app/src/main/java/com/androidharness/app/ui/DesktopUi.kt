package com.androidharness.app.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.androidharness.app.ui.files.EditorNavigationGuard

/** Shared desktop chrome for the conversation, navigation, and embedded workspace tools. */
internal val LocalDesktopLayout = staticCompositionLocalOf { false }
internal val LocalDesktopNavigationGuard = staticCompositionLocalOf<EditorNavigationGuard?> { null }

/** The richer composer is opt-in with Emulator mode. Auto and Phone retain their input UI. */
internal val LocalEmulatorMode = staticCompositionLocalOf { false }
internal val LocalComposerFocus = staticCompositionLocalOf<androidx.compose.ui.focus.FocusRequester?> { null }

/** Root shortcuts also work before a user focuses a pane or text field. */
internal class DesktopKeyboardRouter {
    private var owner: Any? = null
    var handler: ((androidx.compose.ui.input.key.KeyEvent) -> Boolean)? = null
        private set
    fun register(owner: Any, handler: (androidx.compose.ui.input.key.KeyEvent) -> Boolean) {
        this.owner = owner
        this.handler = handler
    }
    fun unregister(owner: Any) {
        if (this.owner === owner) { this.owner = null; handler = null }
    }
}
internal val LocalDesktopKeyboardRouter = staticCompositionLocalOf<DesktopKeyboardRouter?> { null }

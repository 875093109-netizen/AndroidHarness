package com.androidharness.app.ui.files

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel

/** Keeps the active editor's draft in memory across activity rotation, without large Bundles. */
internal class EditorBufferViewModel : ViewModel() {
    private var owner: String? = null
    var buffer = EditorBuffer()
        private set

    fun acquire(key: String): EditorBuffer {
        if (owner != key) { owner = key; buffer = EditorBuffer() }
        return buffer
    }
}

internal class EditorBuffer {
    val text = mutableStateOf<String?>(null)
    val baseline = mutableStateOf("")
    val saved = mutableStateOf("")
    val editing = mutableStateOf(false)
    val dirty = mutableStateOf(false)
}

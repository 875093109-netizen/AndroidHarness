package com.androidharness.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ModelShortcut(val providerId: String, val modelId: String)

@Serializable
data class ModelShortcutState(
    val favourites: List<ModelShortcut> = emptyList(),
    val recent: List<ModelShortcut> = emptyList(),
) {
    fun toggle(key: ModelShortcut): ModelShortcutState = copy(favourites =
        if (key in favourites) favourites - key else favourites + key)

    fun selected(key: ModelShortcut): ModelShortcutState = copy(recent =
        (listOf(key) + recent.filterNot { it == key }).take(50))
}

/** Device-local picker shortcuts. IDs are scoped to a provider, never credentials. */
class ModelShortcuts(context: Context, name: String = "model-shortcuts") {
    private val preferences = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val mutable = MutableStateFlow(runCatching {
        json.decodeFromString<ModelShortcutState>(preferences.getString("state", null) ?: "{}")
    }.getOrDefault(ModelShortcutState()))
    val state: StateFlow<ModelShortcutState> = mutable

    @Synchronized fun toggle(key: ModelShortcut) = update(mutable.value.toggle(key))
    @Synchronized fun selected(key: ModelShortcut) = update(mutable.value.selected(key))
    @Synchronized fun clearRecent(providerId: String) = update(mutable.value.copy(
        recent = mutable.value.recent.filterNot { it.providerId == providerId }))

    private fun update(value: ModelShortcutState) {
        preferences.edit().putString("state", json.encodeToString(value)).apply()
        mutable.value = value
    }

    companion object {
        @Volatile private var instance: ModelShortcuts? = null
        fun get(context: Context): ModelShortcuts = instance ?: synchronized(this) {
            instance ?: ModelShortcuts(context).also { instance = it }
        }
    }
}

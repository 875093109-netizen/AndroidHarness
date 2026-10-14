package com.androidharness.app.llm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

@Serializable
data class FallbackTarget(val providerId: String, val model: String)

@Serializable
data class FallbackPolicy(
    val enabled: Boolean = false,
    val targets: List<FallbackTarget> = emptyList(),
    val retries: Int = 3,
    val sameProviderOnly: Boolean = false,
) {
    fun valid(): Boolean = retries in 0..5 && targets.size <= MAX_TARGETS &&
        targets.distinct().size == targets.size && targets.all {
            it.providerId.isNotBlank() && it.providerId.length <= 500 && it.model.isNotBlank() && it.model.length <= 500
        }
    companion object {
        const val MAX_TARGETS = 4
        private val json = Json { ignoreUnknownKeys = true }
        fun decode(raw: String?): FallbackPolicy = raw?.let {
            runCatching { json.decodeFromString(serializer(), it).also { policy -> require(policy.valid()) } }.getOrNull()
        } ?: FallbackPolicy()
        fun encode(policy: FallbackPolicy): String {
            require(policy.valid()) { "Choose up to four unique models and zero to five retries." }
            return json.encodeToString(serializer(), policy)
        }
    }
}

@Serializable
enum class FallbackMode { DEFAULT, CUSTOM, OFF }

/** Null preserves opt-in ChatGPT account rotation when no chain was configured. */
fun effectiveFallback(default: FallbackPolicy, mode: FallbackMode, custom: FallbackPolicy?): FallbackPolicy? = when (mode) {
    FallbackMode.DEFAULT -> default.takeIf { it.enabled && it.valid() }
    FallbackMode.CUSTOM -> custom?.takeIf { it.valid() } ?: FallbackPolicy()
    FallbackMode.OFF -> FallbackPolicy()
}

/** Run-owned consent; reread before switching so disabling routing takes effect immediately. */
class FallbackRun(val primary: ProviderConfig? = null, val policy: suspend () -> FallbackPolicy?) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<FallbackRun>
}

package com.androidharness.app.data.env

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Owns one bind attempt at a time; late callbacks cannot revive an expired attempt. */
internal class ShizukuServiceBinding<T>(
    private val scope: CoroutineScope,
    private val timeoutMs: Long = 15_000,
    private val isAlive: (T) -> Boolean,
    private val bind: (connected: (T?) -> Unit, disconnected: () -> Unit) -> ((remove: Boolean) -> Unit),
    private val onDisconnected: () -> Unit = {},
) {
    private class Attempt {
        var timeout: Job? = null
        var detach: ((Boolean) -> Unit)? = null
    }
    private var current: Attempt? = null
    private var connectedService: T? = null
    private val mutableState = MutableStateFlow(UserServiceState.NOT_BOUND)
    val state: StateFlow<UserServiceState> = mutableState.asStateFlow()

    val service: T?
        @Synchronized get() {
            val value = connectedService ?: return null
            if (runCatching { isAlive(value) }.getOrDefault(false)) return value
            clear(UserServiceState.BIND_FAILED)
            return null
        }

    @Synchronized fun connect() {
        if (service != null || current != null) return
        val attempt = Attempt()
        current = attempt
        mutableState.value = UserServiceState.BINDING
        attempt.timeout = scope.launch {
            delay(timeoutMs)
            synchronized(this@ShizukuServiceBinding) {
                if (current === attempt && connectedService == null) clear(UserServiceState.BIND_FAILED)
            }
        }
        try {
            val detach = bind({ value ->
                synchronized(this) {
                    if (current === attempt) {
                        if (value != null && runCatching { isAlive(value) }.getOrDefault(false)) {
                            connectedService = value
                            attempt.timeout?.cancel()
                            mutableState.value = UserServiceState.BOUND_READY
                        } else clear(UserServiceState.BIND_FAILED)
                    }
                }
            }, {
                val disconnected = synchronized(this) {
                    if (current !== attempt) false else {
                        clear(UserServiceState.NOT_BOUND)
                        true
                    }
                }
                if (disconnected) onDisconnected()
            })
            if (current === attempt) attempt.detach = detach else runCatching { detach(false) }
        } catch (_: Exception) {
            if (current === attempt) clear(UserServiceState.BIND_FAILED)
        }
    }

    @Synchronized fun disconnect(remove: Boolean = false) = clear(UserServiceState.NOT_BOUND, remove)

    @Synchronized fun unavailable() = clear(UserServiceState.BIND_FAILED)

    /** Detach callbacks without killing a healthy server during a timeout or binder loss. */
    private fun clear(state: UserServiceState, remove: Boolean = false) {
        val previous = current
        current = null
        connectedService = null
        previous?.timeout?.cancel()
        previous?.detach?.let { detach -> runCatching { detach(remove) } }
        mutableState.value = state
    }
}

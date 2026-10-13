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
    private val bind: (connected: (T?) -> Unit, disconnected: (ShizukuFailureReason) -> Unit) -> ((remove: Boolean) -> Unit),
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
    private val mutableFailure = MutableStateFlow<ShizukuConnectionFailure?>(null)
    val failure: StateFlow<ShizukuConnectionFailure?> = mutableFailure.asStateFlow()

    val service: T?
        @Synchronized get() {
            val value = connectedService ?: return null
            if (checkAlive(value)) return value
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
                if (current === attempt && connectedService == null) {
                    fail(ShizukuConnectionFailure(ShizukuFailureReason.TIMEOUT,
                        "Waited ${timeoutMs} ms for onServiceConnected. " +
                            "Shizuku did not provide a startup error to the app. " +
                            "Device logcat around this time is needed to identify the server-side cause."))
                }
            }
        }
        try {
            val detach = bind({ value ->
                synchronized(this) {
                    if (current === attempt) {
                        if (value == null) fail(ShizukuConnectionFailure(ShizukuFailureReason.NULL_BINDER))
                        else if (checkAlive(value)) {
                            connectedService = value
                            attempt.timeout?.cancel()
                            mutableFailure.value = null
                            mutableState.value = UserServiceState.BOUND_READY
                        }
                    }
                }
            }, { reason ->
                val disconnected = synchronized(this) {
                    if (current !== attempt) false else {
                        fail(ShizukuConnectionFailure(reason))
                        true
                    }
                }
                if (disconnected) onDisconnected()
            })
            if (current === attempt) attempt.detach = detach else runCatching { detach(false) }
        } catch (e: Exception) {
            if (current === attempt) fail(ShizukuConnectionFailure.fromException(ShizukuFailureReason.BIND_EXCEPTION, e))
        }
    }

    @Synchronized fun disconnect(remove: Boolean = false, failure: ShizukuConnectionFailure? = null) {
        if (failure != null) mutableFailure.value = failure
        clear(UserServiceState.NOT_BOUND, remove)
    }

    @Synchronized fun unavailable(failure: ShizukuConnectionFailure) = fail(failure)

    private fun checkAlive(value: T): Boolean = try {
        if (isAlive(value)) true else {
            fail(ShizukuConnectionFailure(ShizukuFailureReason.DEAD_BINDER))
            false
        }
    } catch (e: Exception) {
        fail(ShizukuConnectionFailure.fromException(ShizukuFailureReason.BINDER_CHECK_FAILED, e))
        false
    }

    private fun fail(failure: ShizukuConnectionFailure) {
        mutableFailure.value = failure
        clear(UserServiceState.BIND_FAILED)
    }

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

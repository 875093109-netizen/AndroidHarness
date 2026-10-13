package com.androidharness.app.data.env

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class ShizukuServiceBindingTest {
    private class Runner(var alive: Boolean = true)
    private class Fixture {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val connected = mutableListOf<(Runner?) -> Unit>()
        val disconnected = mutableListOf<(ShizukuFailureReason) -> Unit>()
        val detached = mutableListOf<Boolean>()
        var throwsOnBind = false
        var disconnectNotices = 0
        val binding = ShizukuServiceBinding(scope, timeoutMs = 30, isAlive = { runner: Runner -> runner.alive },
            bind = { ready, lost ->
                if (throwsOnBind) throw IllegalStateException("bind failed")
                connected += ready
                disconnected += lost
                val detach: (Boolean) -> Unit = { remove -> detached += remove }
                detach
            }, onDisconnected = { disconnectNotices++ })
    }

    private fun fixture(block: suspend (Fixture) -> Unit) = runBlocking {
        val f = Fixture()
        try { block(f) } finally { f.scope.cancel() }
    }

    @Test fun missingCallbackTimesOutAndRecheckCanRetry() = fixture { f ->
        f.binding.connect()
        assertEquals(UserServiceState.BINDING, f.binding.state.value)
        f.binding.connect()
        assertEquals(1, f.connected.size)
        withTimeout(2_000) { f.binding.state.first { it == UserServiceState.BIND_FAILED } }
        assertNull(f.binding.service)
        assertEquals(listOf(false), f.detached)
        val failure = requireNotNull(f.binding.failure.value)
        assertEquals(ShizukuFailureReason.TIMEOUT, failure.reason)
        assertTrue(failure.detail.contains("30 ms"))
        assertTrue(failure.detail.contains("did not provide a startup error"))
        f.binding.connect()
        assertSame(failure, f.binding.failure.value)
        val runner = Runner()
        f.connected.last()(runner)
        assertSame(runner, f.binding.service)
        assertEquals(UserServiceState.BOUND_READY, f.binding.state.value)
        assertNull(f.binding.failure.value)
    }

    @Test fun lateCallbacksFromExpiredAttemptCannotReplaceNewConnection() = fixture { f ->
        f.binding.connect()
        withTimeout(2_000) { f.binding.state.first { it == UserServiceState.BIND_FAILED } }
        f.binding.connect()
        val runner = Runner()
        f.connected[1](runner)
        f.connected[0](Runner())
        f.disconnected[0](ShizukuFailureReason.SERVICE_DISCONNECTED)
        assertSame(runner, f.binding.service)
        assertEquals(UserServiceState.BOUND_READY, f.binding.state.value)
        assertEquals(0, f.disconnectNotices)
        assertNull(f.binding.failure.value)
    }

    @Test fun nullOrDeadCallbackReleasesRetryGuard() = fixture { f ->
        for (runner in listOf(null, Runner(alive = false))) {
            f.binding.connect()
            f.connected.last()(runner)
            assertEquals(UserServiceState.BIND_FAILED, f.binding.state.value)
            assertNull(f.binding.service)
            assertEquals(if (runner == null) ShizukuFailureReason.NULL_BINDER else ShizukuFailureReason.DEAD_BINDER,
                f.binding.failure.value?.reason)
        }
        f.binding.connect()
        f.connected.last()(Runner())
        assertEquals(UserServiceState.BOUND_READY, f.binding.state.value)
        assertEquals(3, f.connected.size)
    }

    @Test fun bindExceptionAllowsNextAttempt() = fixture { f ->
        f.throwsOnBind = true
        f.binding.connect()
        assertEquals(UserServiceState.BIND_FAILED, f.binding.state.value)
        assertEquals(ShizukuFailureReason.BIND_EXCEPTION, f.binding.failure.value?.reason)
        assertTrue(requireNotNull(f.binding.failure.value).detail.contains("IllegalStateException: bind failed"))
        f.throwsOnBind = false
        f.binding.connect()
        f.connected.single()(Runner())
        assertEquals(UserServiceState.BOUND_READY, f.binding.state.value)
        assertNull(f.binding.failure.value)
    }

    @Test fun connectedRunnerOutlivesBindTimeoutAndDuplicateRefreshes() = fixture { f ->
        f.binding.connect()
        f.connected.single()(Runner())
        kotlinx.coroutines.delay(60)
        f.binding.connect()
        assertEquals(1, f.connected.size)
        assertEquals(UserServiceState.BOUND_READY, f.binding.state.value)
        assertTrue(f.detached.isEmpty())
    }

    @Test fun serverOrPermissionLossInvalidatesPendingCallbacks() = fixture { f ->
        f.binding.connect()
        f.binding.disconnect()
        f.connected.single()(Runner())
        assertNull(f.binding.service)
        assertEquals(UserServiceState.NOT_BOUND, f.binding.state.value)
        f.binding.connect()
        f.connected.last()(Runner())
        f.binding.disconnect()
        assertNull(f.binding.service)
        assertEquals(UserServiceState.NOT_BOUND, f.binding.state.value)
        assertEquals(listOf(false, false), f.detached)
    }

    @Test fun deadConnectedBinderIsNotReportedReadyAndCanReconnect() = fixture { f ->
        val runner = Runner()
        f.binding.connect()
        f.connected.single()(runner)
        runner.alive = false
        assertNull(f.binding.service)
        assertEquals(UserServiceState.BIND_FAILED, f.binding.state.value)
        assertEquals(ShizukuFailureReason.DEAD_BINDER, f.binding.failure.value?.reason)
        f.binding.connect()
        f.connected.last()(Runner())
        assertEquals(UserServiceState.BOUND_READY, f.binding.state.value)
    }

    @Test fun disconnectRequestsRecoveryOnlyForTheCurrentAttempt() = fixture { f ->
        f.binding.connect()
        f.connected.single()(Runner())
        f.disconnected.single()(ShizukuFailureReason.BINDING_DIED)
        f.disconnected.single()(ShizukuFailureReason.SERVICE_DISCONNECTED)
        assertEquals(1, f.disconnectNotices)
        assertEquals(UserServiceState.BIND_FAILED, f.binding.state.value)
        assertEquals(ShizukuFailureReason.BINDING_DIED, f.binding.failure.value?.reason)
    }

    @Test fun binderProbeExceptionIsCapturedAndCleanupDoesNotReplaceIt() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        var disconnected: ((ShizukuFailureReason) -> Unit)? = null
        try {
            val binding = ShizukuServiceBinding<Runner>(scope, isAlive = {
                throw IllegalStateException("binder probe failed", SecurityException("denied"))
            }, bind = { ready, lost ->
                disconnected = lost
                ready(Runner())
                val detach: (Boolean) -> Unit = { lost(ShizukuFailureReason.SERVICE_DISCONNECTED) }
                detach
            })
            binding.connect()
            assertEquals(UserServiceState.BIND_FAILED, binding.state.value)
            val failure = requireNotNull(binding.failure.value)
            assertEquals(ShizukuFailureReason.BINDER_CHECK_FAILED, failure.reason)
            assertTrue(failure.report().contains("IllegalStateException: binder probe failed"))
            assertTrue(failure.report().contains("Caused by: java.lang.SecurityException: denied"))
            disconnected?.invoke(ShizukuFailureReason.BINDING_DIED)
            assertSame(failure, binding.failure.value)
        } finally { scope.cancel() }
    }

    @Test fun serverLossIsRetainedAcrossStatusChecksAndClearedOnRecovery() = fixture { f ->
        f.binding.connect()
        f.connected.single()(Runner())
        val failure = ShizukuConnectionFailure(ShizukuFailureReason.SERVER_LOST)
        f.binding.disconnect(failure = failure)
        f.binding.disconnect()
        assertSame(failure, f.binding.failure.value)
        assertEquals(UserServiceState.NOT_BOUND, f.binding.state.value)
        f.binding.connect()
        f.connected.last()(Runner())
        assertNull(f.binding.failure.value)
    }
}

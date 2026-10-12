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
        val disconnected = mutableListOf<() -> Unit>()
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
        f.binding.connect()
        val runner = Runner()
        f.connected.last()(runner)
        assertSame(runner, f.binding.service)
        assertEquals(UserServiceState.BOUND_READY, f.binding.state.value)
    }

    @Test fun lateCallbacksFromExpiredAttemptCannotReplaceNewConnection() = fixture { f ->
        f.binding.connect()
        withTimeout(2_000) { f.binding.state.first { it == UserServiceState.BIND_FAILED } }
        f.binding.connect()
        val runner = Runner()
        f.connected[1](runner)
        f.connected[0](Runner())
        f.disconnected[0]()
        assertSame(runner, f.binding.service)
        assertEquals(UserServiceState.BOUND_READY, f.binding.state.value)
        assertEquals(0, f.disconnectNotices)
    }

    @Test fun nullOrDeadCallbackReleasesRetryGuard() = fixture { f ->
        for (runner in listOf(null, Runner(alive = false))) {
            f.binding.connect()
            f.connected.last()(runner)
            assertEquals(UserServiceState.BIND_FAILED, f.binding.state.value)
            assertNull(f.binding.service)
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
        f.throwsOnBind = false
        f.binding.connect()
        f.connected.single()(Runner())
        assertEquals(UserServiceState.BOUND_READY, f.binding.state.value)
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
        f.binding.connect()
        f.connected.last()(Runner())
        assertEquals(UserServiceState.BOUND_READY, f.binding.state.value)
    }

    @Test fun disconnectRequestsRecoveryOnlyForTheCurrentAttempt() = fixture { f ->
        f.binding.connect()
        f.connected.single()(Runner())
        f.disconnected.single()()
        f.disconnected.single()()
        assertEquals(1, f.disconnectNotices)
        assertEquals(UserServiceState.NOT_BOUND, f.binding.state.value)
    }
}

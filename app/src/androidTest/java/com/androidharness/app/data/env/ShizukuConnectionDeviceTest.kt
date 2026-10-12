package com.androidharness.app.data.env

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in: start Shizuku through ADB and grant this build access when prompted. */
@RunWith(AndroidJUnit4::class)
class ShizukuConnectionDeviceTest {
    @Test fun connectedRunnerExecutesAsShellAndRefreshPreservesIt() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("shizukuReady") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = (context.applicationContext as HarnessApp).container.shizuku
        manager.refresh()
        if (!manager.isGranted()) manager.requestPermission()
        withTimeout(60_000) { manager.state.first { it == ShizukuState.GRANTED } }
        withTimeout(20_000) { manager.serviceState.first { it == UserServiceState.BOUND_READY } }
        assertEquals(ShizukuState.GRANTED, manager.state.value)
        assertTrue(manager.isServiceReady())
        repeat(3) { manager.refresh() }
        val result = requireNotNull(manager.runPrivileged(arrayOf("/system/bin/id", "-u"), null, null, 5_000))
        assertEquals(result.stderr, 0, result.exitCode)
        assertEquals("2000", result.output.trim())
        assertFalse(result.timedOut)
        assertEquals(UserServiceState.BOUND_READY, manager.serviceState.value)
    }
}

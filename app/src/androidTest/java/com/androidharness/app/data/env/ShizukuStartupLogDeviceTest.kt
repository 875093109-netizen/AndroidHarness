package com.androidharness.app.data.env

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import rikka.shizuku.Shizuku

/** Opt-in: exercises a real startup exception while the normal user service is disconnected. */
@RunWith(AndroidJUnit4::class)
class ShizukuStartupLogDeviceTest {
    @Test fun startupExceptionCanBeCollectedWithoutConnectedRunner() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("shizukuReady") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = ShizukuManager(context,
            userServiceClassName = "${context.packageName}.MissingIdleDiagnosticService",
            userServiceTimeoutMs = 60_000, userServiceTag = "diagnostic-${UUID.randomUUID()}")
        manager.refresh()
        assumeTrue(manager.isGranted())
        manager.unbindUserService()
        assertFalse(manager.isServiceReady())
        val missingClass = "${context.packageName}.MissingStartupDiagnosticService"
        val args = Shizuku.UserServiceArgs(ComponentName(context.packageName, missingClass))
            .daemon(false).processNameSuffix("startup_test").version(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) = Unit
            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        val since = System.currentTimeMillis() - 1_000
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { Shizuku.bindUserService(args, connection) }
            val captured = withTimeout(20_000) {
                var result: Pair<String, String>
                do {
                    delay(500)
                    result = collectShizukuStartupLog(context.packageName, since)
                } while (!result.first.contains("ClassNotFoundException") || !result.first.contains(missingClass))
                result
            }
            assertEquals("Startup log captured", captured.second)
            assertTrue(captured.first.contains(missingClass))
            assertTrue(captured.first.contains("unable to start service"))
            assertTrue(manager.isGranted())
            assertFalse(manager.isServiceReady())
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { Shizuku.unbindUserService(args, connection, true) }
            manager.close()
        }
    }
}

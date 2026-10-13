package com.androidharness.app.data.env

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class ShizukuStartupLogTest {
    private fun log(pid: Int, tag: String, message: String) =
        "10-13 23:06:28.123 $pid $pid W $tag: $message"

    @Test fun startupExceptionAndRecordHistoryAreKeptWithoutOtherAppsLogs() {
        val token = "12345678-abcd-1234-abcd-123456789012-1780000000000"
        val otherToken = "aaaaaaaa-abcd-1234-abcd-123456789012-1780000000000"
        val lines = listOf(
            log(100, "UserServiceManager", "New service record com.androidharness.app:Runner ($token): version=1"),
            log(100, "UserServiceManager", "New service record other.app:Runner ($otherToken): version=1"),
            log(200, "ShizukuServiceStarter", "starting service com.androidharness.app/Runner..."),
            log(201, "ShizukuServiceStarter", "starting service other.app/Runner..."),
            log(200, "ShizukuServiceStarter", "unable to start service com.androidharness.app/Runner..."),
            log(200, "ShizukuServiceStarter", "Caused by: java.lang.NullPointerException: actual startup error"),
            log(200, "ShizukuServiceStarter", "\tat android.app.LoadedApk.makeApplicationInner(LoadedApk.java:1519)"),
            log(201, "ShizukuServiceStarter", "private other app error"),
            log(100, "UserServiceRecord", "Service record $token is not started in 30000 ms"),
            log(100, "UserServiceRecord", "Service record $otherToken is not started in 30000 ms"),
            log(300, "AndroidRuntime", "unrelated crash"),
        )
        val filtered = filterShizukuStartupLog(lines.joinToString("\n"), "com.androidharness.app")
        assertTrue(filtered.contains("actual startup error"))
        assertTrue(filtered.contains("LoadedApk.java:1519"))
        assertTrue(filtered.contains("not started in 30000 ms"))
        assertFalse(filtered.contains("other.app"))
        assertFalse(filtered.contains(otherToken))
        assertFalse(filtered.contains("unrelated crash"))
    }

    @Test fun debugBuildAndPackagePrefixMatchesDoNotLeakIntoReleaseReport() {
        val raw = listOf(
            log(200, "ShizukuServiceStarter", "starting service com.androidharness.app.debug/Runner..."),
            log(200, "ShizukuServiceStarter", "private debug error"),
            log(100, "UserServiceManager", "New service record com.androidharness.app.other:Runner"),
        ).joinToString("\n")
        assertEquals("", filterShizukuStartupLog(raw, "com.androidharness.app"))
    }

    @Test fun crashProcessHeaderIdentifiesOurHelperAndKeepsItsTrace() {
        val raw = listOf(
            log(200, "AndroidRuntime", "FATAL EXCEPTION: main"),
            log(200, "AndroidRuntime", "Process: com.androidharness.app:harness, PID: 200"),
            log(200, "AndroidRuntime", "java.lang.IllegalStateException: crash"),
            log(201, "AndroidRuntime", "Process: other.app:harness, PID: 201"),
            log(201, "AndroidRuntime", "private crash"),
        ).joinToString("\n")
        val filtered = filterShizukuStartupLog(raw, "com.androidharness.app")
        assertTrue(filtered.contains("FATAL EXCEPTION"))
        assertTrue(filtered.contains("IllegalStateException: crash"))
        assertFalse(filtered.contains("private crash"))
    }

    @Test fun unidentifiedStackTraceIsNotExportedAsOurFailure() {
        val raw = log(200, "ShizukuServiceStarter", "java.lang.IllegalStateException: unknown client")
        assertEquals("", filterShizukuStartupLog(raw, "com.androidharness.app"))
    }

    @Test fun oversizedPipeIsFullyDrainedAndKeepsRecentUnicodeText() {
        val text = "old log ".repeat(20_000) + "recent failure ✓"
        val stream = ByteArrayInputStream(text.toByteArray())
        assertEquals(text.takeLast(100), readStartupLogTail(stream, 100))
        assertEquals(0, stream.available())
    }
}

package com.androidharness.app.data.env

import org.junit.Assert.*
import org.junit.Test

class ShizukuCompatProtocolTest {
    @Test fun argumentsEnvironmentAndOutputSurviveWithoutShellInterpolation() {
        val request = CompatRequest(42, "exec", listOf("/system/bin/sh", "-c", "printf '%s' \"\$VALUE\"", "quote'\"\n✓"),
            listOf("VALUE=spaces ' \" \$dollars `ticks`\nnext", "EMPTY="), "/sdcard/folder with spaces", maxBytes = 1234, timeoutMs = 5678)
        val json = ShizukuCompatProtocol.json
        val encoded = json.encodeToString(CompatRequest.serializer(), request)
        assertEquals(1, encoded.lines().size)
        assertEquals(request, json.decodeFromString(CompatRequest.serializer(), encoded))
        val response = CompatResponse(42, "exit=23\ntimeout=0\nstdout\n<<<HARNESS_STDERR>>>\nstderr\n✓")
        assertEquals(response, json.decodeFromString(CompatResponse.serializer(), json.encodeToString(CompatResponse.serializer(), response)))
    }

    @Test fun launcherQuotesOnlyTheApkAndContainsNoRequestCredentials() {
        val path = "/data/app/a'b `ticks` \$cash/base.apk"
        val args = ShizukuCompatProtocol.launchCommand(path)
        assertEquals(listOf("/system/bin/sh", "-c"), args.take(2))
        assertTrue(args[2].startsWith("export CLASSPATH='/data/app/a'\\''b `ticks` \$cash/base.apk';"))
        assertTrue(args[2].endsWith("/system/bin/app_process /system/bin ${ShizukuCompatProtocol.MAIN_CLASS}"))
    }

    @Test fun readinessIncludesOnlyVerifiedBackends() {
        assertTrue(UserServiceState.BOUND_READY.isReady)
        assertTrue(UserServiceState.COMPAT_READY.isReady)
        assertFalse(UserServiceState.BINDING.isReady)
        assertFalse(UserServiceState.NOT_BOUND.isReady)
        assertFalse(UserServiceState.BIND_FAILED.isReady)
    }
}

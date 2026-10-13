package com.androidharness.app.agent

import com.androidharness.app.data.env.ShizukuState
import com.androidharness.app.data.env.UserServiceState
import org.junit.Assert.*
import org.junit.Test

class ShizukuAgentStatusTest {
    @Test fun permissionAloneNeverClaimsReady() {
        for (service in UserServiceState.entries) {
            val hint = shizukuAgentStatus(ShizukuState.GRANTED, service, false)!!
            assertFalse(hint, hint.contains("runner is ready"))
            assertTrue(hint, hint.contains("connecting") || hint.contains("unavailable") || hint.contains("available"))
        }
    }
    @Test fun normalAndCompatibilityRunnersRequireCallableService() {
        for (service in listOf(UserServiceState.BOUND_READY, UserServiceState.COMPAT_READY))
            assertTrue(shizukuAgentStatus(ShizukuState.GRANTED, service, true)!!.contains("runner is ready"))
        assertFalse(shizukuAgentStatus(ShizukuState.RUNNING_NO_PERMISSION, UserServiceState.BOUND_READY, true)!!.contains("runner is ready"))
    }
    @Test fun failuresHaveAnActionAndNoInstallationHasNoPrompt() {
        assertTrue(shizukuAgentStatus(ShizukuState.GRANTED, UserServiceState.BIND_FAILED, false)!!.contains("compatibility"))
        assertNull(shizukuAgentStatus(ShizukuState.NOT_INSTALLED, UserServiceState.NOT_BOUND, false))
    }
}

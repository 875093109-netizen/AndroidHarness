package com.androidharness.app.agent

import com.androidharness.app.data.env.ShizukuState
import com.androidharness.app.data.env.UserServiceState

internal fun shizukuAgentStatus(state: ShizukuState, service: UserServiceState, callable: Boolean): String? = when {
    state == ShizukuState.GRANTED && callable ->
        "Shizuku's privileged shell runner is ready. Use shell normally; the harness selects the execution tier."
    state == ShizukuState.GRANTED -> when (service) {
        UserServiceState.BINDING -> "Shizuku permission is granted, but its command runner is still connecting. Privileged commands are not ready. Use env_status to check readiness; do not assume ADB-shell access."
        UserServiceState.BIND_FAILED -> "Shizuku permission is granted, but its command runner failed to connect. Privileged commands are unavailable. Check env_status and guide the user to Settings → Terminal & environment for reconnect or compatibility mode."
        else -> "Shizuku permission is granted, but no callable privileged runner is available. Check env_status before using ADB-level commands."
    }
    state == ShizukuState.RUNNING_NO_PERMISSION -> "Shizuku is running without permission. For ADB-level commands, ask the user to grant access in Settings → Terminal & environment."
    state == ShizukuState.NOT_RUNNING -> "Shizuku is installed but stopped. For ADB-level commands, ask the user to start Shizuku and refresh its status in Settings → Terminal & environment."
    else -> null
}

package com.androidharness.app.data.env

import java.time.Instant

enum class ShizukuFailureReason(val summary: String) {
    TIMEOUT("Shizuku did not return a shell runner before the connection timed out."),
    BIND_EXCEPTION("The shell runner connection request failed."),
    NULL_BINDER("Shizuku returned no shell runner binder."),
    DEAD_BINDER("The shell runner binder is not responding."),
    BINDER_CHECK_FAILED("Checking the shell runner binder failed."),
    SERVICE_DISCONNECTED("Shizuku disconnected the shell runner."),
    BINDING_DIED("The shell runner binding died."),
    UNSUPPORTED_VERSION("This Shizuku server is too old to start the shell runner."),
    VERSION_CHECK_FAILED("The Shizuku server version could not be checked."),
    SERVER_LOST("The connection to the Shizuku server was lost."),
    COMPAT_START_FAILED("The compatibility shell runner could not start."),
    COMPAT_DISCONNECTED("The compatibility shell runner disconnected."),
}

/** Only connection diagnostics; never captures commands, environment variables or shell output. */
data class ShizukuConnectionFailure(
    val reason: ShizukuFailureReason,
    val detail: String = "",
    val occurredAtMillis: Long = System.currentTimeMillis(),
) {
    fun report(): String = buildString {
        appendLine("Last failure at: ${Instant.ofEpochMilli(occurredAtMillis)}")
        appendLine("Error: ${reason.name}")
        appendLine(reason.summary)
        if (detail.isNotBlank()) appendLine(detail)
    }.trimEnd()

    companion object {
        fun fromException(reason: ShizukuFailureReason, error: Exception) =
            ShizukuConnectionFailure(reason, error.stackTraceToString().take(6_000))
    }
}

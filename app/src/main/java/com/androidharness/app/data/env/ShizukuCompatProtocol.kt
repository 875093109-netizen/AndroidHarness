package com.androidharness.app.data.env

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Private stdin/stdout protocol. Arguments remain arrays; they are never evaluated as shell text. */
@Serializable
internal data class CompatRequest(
    val id: Long,
    val operation: String,
    val command: List<String> = emptyList(),
    val environment: List<String>? = null,
    val directory: String? = null,
    val logPath: String? = null,
    val maxBytes: Int = 60_000,
    val timeoutMs: Int = 30_000,
    val pid: Int = -1,
    val target: Long = -1,
)

@Serializable
internal data class CompatResponse(
    val id: Long,
    val result: String = "",
    val error: String? = null,
    val uid: Int = -1,
    val protocol: Int = 1,
)

internal object ShizukuCompatProtocol {
    val json = Json { ignoreUnknownKeys = false }
    const val MAIN_CLASS = "com.androidharness.app.data.env.ShizukuCompatMain"

    fun launchCommand(apkPath: String): Array<String> {
        // Only the installed APK path enters this fixed launcher. Normal command
        // arguments and credentials go through the private pipes, not this script.
        val quoted = "'" + apkPath.replace("'", "'\\''") + "'"
        return arrayOf("/system/bin/sh", "-c",
            "export CLASSPATH=$quoted; exec /system/bin/app_process /system/bin $MAIN_CLASS")
    }
}

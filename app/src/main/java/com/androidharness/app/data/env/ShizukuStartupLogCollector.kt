package com.androidharness.app.data.env

import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Fixed, read-only logcat command through the server binder; never requires HarnessUserService. */
internal suspend fun collectShizukuStartupLog(packageName: String, sinceMillis: Long): Pair<String, String> =
    withContext(Dispatchers.IO) {
        try {
            check(Shizuku.pingBinder()) { "Shizuku is not running. Start it and retry." }
            check(Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                "Grant AndroidHarness access in Shizuku, then retry."
            }
            // This legacy server transaction is available through API 13. Keep it limited
            // to this diagnostic read; normal commands continue to use the user service.
            check(Shizuku.getVersion() in 11..13) { "Startup log collection is unavailable with this Shizuku API." }
            val since = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date(sinceMillis))
            val command = arrayOf("/system/bin/logcat", "-d", "-v", "threadtime", "-T", since,
                "ShizukuServiceStarter:V", "UserServiceManager:V", "UserServiceRecord:V",
                "AndroidRuntime:E", "libc:F", "DEBUG:F", "*:S")
            coroutineScope {
                val server = IShizukuService.Stub.asInterface(Shizuku.getBinder())
                val process = requireNotNull(server.newProcess(command, null, null)) { "Shizuku did not start logcat." }
                var output: InputStream? = null
                var errors: InputStream? = null
                try {
                    val stdout = ParcelFileDescriptor.AutoCloseInputStream(process.inputStream).also { output = it }
                    val stderr = ParcelFileDescriptor.AutoCloseInputStream(process.errorStream).also { errors = it }
                    val out = async(Dispatchers.IO) { readStartupLogTail(stdout, 192_000) }
                    val err = async(Dispatchers.IO) { readStartupLogTail(stderr, 4_000) }
                    check(process.waitForTimeout(8, TimeUnit.SECONDS.name)) { "Reading the startup log timed out. Retry collection." }
                    val raw = out.await()
                    val error = err.await()
                    check(process.exitValue() == 0) { "Logcat exited with ${process.exitValue()}: $error" }
                    val filtered = filterShizukuStartupLog(raw, packageName)
                    if (filtered.isBlank()) {
                        "No matching startup log was found. Retry the connection, wait for the failure, then copy the startup log again." to "No startup entries found"
                    } else filtered to "Startup log captured"
                } finally {
                    runCatching { process.destroy() }
                    runCatching { output?.close() }
                    runCatching { errors?.close() }
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            "Startup log collection failed: ${e.stackTraceToString().take(6_000)}" to "Could not collect startup log"
        }
    }

package com.androidharness.app.data.env

import android.system.Os
import androidx.annotation.Keep
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Launches our existing runner without Shizuku's OEM-sensitive makeApplication() step. */
@Keep
object ShizukuCompatMain {
    @Keep
    @JvmStatic
    fun main(args: Array<String>) {
        val runner = HarnessUserService()
        val json = ShizukuCompatProtocol.json
        val output = System.out.bufferedWriter()
        val requests = ConcurrentHashMap<Long, FutureTask<Unit>>()
        val workers = Executors.newCachedThreadPool { task -> Thread(task, "HarnessCompatCommand").apply { isDaemon = true } }
        fun respond(response: CompatResponse) = synchronized(output) {
            output.write(json.encodeToString(CompatResponse.serializer(), response))
            output.newLine()
            output.flush()
        }
        respond(CompatResponse(0, uid = Os.getuid()))
        try {
            System.`in`.bufferedReader().use { input ->
                while (true) {
                    val line = input.readLine() ?: break
                    val request = json.decodeFromString(CompatRequest.serializer(), line)
                    if (request.operation == "cancel") {
                        requests.remove(request.target)?.cancel(true)
                        continue
                    }
                    if (request.operation == "close") break
                    val task = FutureTask<Unit> {
                        try {
                            val result = when (request.operation) {
                                "exec" -> runner.exec(request.command.toTypedArray(), request.environment?.toTypedArray(),
                                    request.directory, request.maxBytes, request.timeoutMs)
                                "spawn" -> runner.spawnDetached(request.command.toTypedArray(), request.environment?.toTypedArray(),
                                    request.directory, requireNotNull(request.logPath)).toString()
                                "alive" -> runner.isProcessAlive(request.pid).toString()
                                "kill" -> runner.killProcess(request.pid).toString()
                                else -> error("Unknown compatibility operation")
                            }
                            respond(CompatResponse(request.id, result))
                        } catch (e: Exception) {
                            respond(CompatResponse(request.id, error = e.toString()))
                        } finally { requests.remove(request.id) }
                    }
                    check(requests.putIfAbsent(request.id, task) == null) { "Duplicate request" }
                    workers.execute(task)
                }
            }
        } finally {
            requests.values.forEach { it.cancel(true) }
            workers.shutdownNow()
            workers.awaitTermination(2, TimeUnit.SECONDS)
        }
        // The runner's reaper is a daemon; no Android Application or binder loop is needed.
    }
}

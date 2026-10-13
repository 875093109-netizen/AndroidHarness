package com.androidharness.app.data.env

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.os.Bundle
import com.androidharness.app.HarnessApp
import com.androidharness.app.data.TerminalManager
import com.androidharness.app.workspace.FileFs
import java.io.File
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Real Shizuku matrix. A missing normal-service class exercises automatic startup recovery. */
@RunWith(AndroidJUnit4::class)
class ShizukuCompatibilityDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private suspend fun manager(compat: Boolean): ShizukuManager {
        assumeTrue(InstrumentationRegistry.getArguments().getString("shizukuReady") == "true")
        val manager = ShizukuManager(context,
            userServiceClassName = if (compat) "${context.packageName}.MissingCompatibilityTestService" else HarnessUserService::class.java.name,
            userServiceTimeoutMs = 2_000, userServiceTag = "test-${UUID.randomUUID()}")
        try {
            withTimeout(30_000) { manager.serviceState.first { it.isReady } }
            assertEquals(manager.connectionReport(), if (compat) UserServiceState.COMPAT_READY else UserServiceState.BOUND_READY,
                manager.serviceState.value)
            assertTrue(manager.isGranted())
            assertTrue(manager.isServiceReady())
            return manager
        } catch (e: Exception) { manager.close(); throw e }
    }

    private suspend fun matrix(block: suspend (ShizukuManager) -> Unit) {
        for (compat in listOf(false, true)) {
            val manager = manager(compat)
            try { block(manager) } finally { manager.close() }
        }
    }

    private suspend fun command(manager: ShizukuManager, script: String, timeout: Int = 5_000, max: Int = 60_000) =
        requireNotNull(manager.runPrivileged(arrayOf("/system/bin/sh", "-c", script), null, "/data/local/tmp", timeout, max))

    @Test fun bothBackendsPreserveIdentityDirectoryEnvironmentAndSeparateStreams() = runBlocking {
        matrix { manager ->
            val value = "spaces ' \" \$dollars `ticks`\nUnicode ✓"
            val result = requireNotNull(manager.runPrivileged(
                arrayOf("/system/bin/sh", "-c", "printf '%s' \"\$CHECK_TEXT\"; printf 'error-only' >&2; exit 23"),
                arrayOf("CHECK_TEXT=$value"), "/data/local/tmp", 5_000))
            assertEquals(23, result.exitCode)
            assertEquals(value, result.output)
            assertEquals("error-only", result.stderr)
            assertFalse(result.timedOut)
            assertEquals("2000", command(manager, "id -u").output.trim())
            assertEquals("/data/local/tmp", command(manager, "pwd").output.trim())
        }
    }

    @Test fun bothBackendsDrainLargeOutputAndRecoverFromLaunchErrors() = runBlocking {
        matrix { manager ->
            val result = command(manager,
                "head -c 120000 /dev/zero | tr '\\000' A; head -c 120000 /dev/zero | tr '\\000' B >&2", max = 2_048)
            assertEquals(0, result.exitCode)
            assertEquals(2_048, result.output.length)
            assertEquals(2_048, result.stderr.length)
            val bad = requireNotNull(manager.runPrivileged(arrayOf("/system/bin/sh", "-c", "true"), null,
                "/data/local/tmp/does-not-exist-${UUID.randomUUID()}", 2_000))
            assertEquals(-1, bad.exitCode)
            assertEquals("recovered", command(manager, "echo recovered").output.trim())
            assertTrue(manager.isServiceReady())
        }
    }

    @Test fun bothBackendsSupportConcurrentCommandsWithoutMixingReplies() = runBlocking {
        matrix { manager ->
            coroutineScope {
                val results = (1..8).map { id -> async {
                    command(manager, "sleep 0.1; printf 'output-$id'; printf 'error-$id' >&2; exit $id")
                } }.map { it.await() }
                results.forEachIndexed { index, result ->
                    assertEquals(index + 1, result.exitCode)
                    assertEquals("output-${index + 1}", result.output)
                    assertEquals("error-${index + 1}", result.stderr)
                }
            }
        }
    }

    @Test fun bothBackendsKillTimedOutChildGroupsAndStayUsable() = runBlocking {
        matrix { manager ->
            val marker = "/data/local/tmp/harness-timeout-${UUID.randomUUID()}"
            try {
                val result = command(manager, "sleep 30 & echo \$! > '$marker'; wait", timeout = 500)
                assertTrue(result.timedOut)
                val pid = command(manager, "cat '$marker'").output.trim().toInt()
                withTimeout(6_000) { while (manager.isProcessAlive(pid)) delay(100) }
                assertEquals("alive", command(manager, "echo alive").output.trim())
            } finally { command(manager, "rm -f '$marker'") }
        }
    }

    @Test fun compatibilityCancellationStopsOnlyItsOwnCommand() = runBlocking {
        val manager = manager(true)
        val marker = "/data/local/tmp/harness-cancel-${UUID.randomUUID()}"
        try {
            val running = launch { command(manager, "sleep 30 & echo \$! > '$marker'; wait", timeout = 30_000) }
            val pid = withTimeout(5_000) {
                var value: Int? = null
                while (value == null) {
                    delay(100)
                    value = command(manager, "cat '$marker' 2>/dev/null").output.trim().toIntOrNull()
                }
                value
            }
            running.cancel()
            running.join()
            withTimeout(6_000) { while (manager.isProcessAlive(pid)) delay(100) }
            assertEquals("still usable", command(manager, "echo 'still usable'").output.trim())
            assertEquals(UserServiceState.COMPAT_READY, manager.serviceState.value)
        } finally { command(manager, "rm -f '$marker'"); manager.close() }
    }

    @Test fun bothBackendsKeepDetachedJobsAcrossRunnerRecreationAndCanStopThem() = runBlocking {
        for (compat in listOf(false, true)) {
            var manager = manager(compat)
            val log = "/data/local/tmp/harness-background-${UUID.randomUUID()}.log"
            var pid = -1
            try {
                pid = requireNotNull(manager.spawnDetached(arrayOf("/system/bin/sh", "-c", "echo background-ready; sleep 60"),
                    null, "/data/local/tmp", log))
                assertTrue(pid > 0)
                withTimeout(5_000) { while (!command(manager, "cat '$log'").output.contains("background-ready")) delay(100) }
                assertTrue(manager.isProcessAlive(pid))
                manager.close()
                manager = manager(compat)
                assertTrue(manager.isProcessAlive(pid))
                assertTrue(manager.killProcess(pid))
                withTimeout(6_000) { while (manager.isProcessAlive(pid)) delay(100) }
            } finally {
                if (pid > 0) manager.killProcess(pid)
                command(manager, "rm -f '$log'")
                manager.close()
            }
        }
    }

    @Test fun refreshesKeepTheSelectedBackendAndExplicitUnbindCanReconnect() = runBlocking {
        matrix { manager ->
            val selected = manager.serviceState.value
            repeat(5) { manager.refresh() }
            assertEquals(selected, manager.serviceState.value)
            assertEquals("2000", command(manager, "id -u").output.trim())
            manager.unbindUserService()
            assertFalse(manager.isServiceReady())
            manager.refresh()
            withTimeout(30_000) { manager.serviceState.first { it.isReady } }
            assertEquals(selected, manager.serviceState.value)
            assertEquals("2000", command(manager, "id -u").output.trim())
        }
    }

    @Test fun bothBackendsSupportSystemFilesPackageCommandsAndLogcat() = runBlocking {
        matrix { manager ->
            val directory = "/data/local/tmp/harness-files-${UUID.randomUUID()}"
            val message = "harness-log-${UUID.randomUUID()}"
            try {
                assertEquals(0, command(manager, "mkdir '$directory'; echo data > '$directory/a'; chmod 600 '$directory/a'; cp '$directory/a' '$directory/b'; mv '$directory/b' '$directory/c'").exitCode)
                assertTrue(manager.privilegedCanAccess(directory))
                assertEquals("data", command(manager, "cat '$directory/c'").output.trim())
                assertTrue(command(manager, "pm list packages ${context.packageName}").output.contains("package:${context.packageName}"))
                command(manager, "log -t AndroidHarnessCompatTest '$message'")
                val log = requireNotNull(manager.runPrivileged(arrayOf("/system/bin/logcat", "-d", "-t", "50",
                    "AndroidHarnessCompatTest:I", "*:S"), null, null, 5_000))
                assertTrue(log.output, log.output.contains(message))
            } finally { command(manager, "rm -rf '$directory'") }
        }
    }

    @Test fun stoppingOneDetachedJobDoesNotStopAnotherInTheSameDirectory() = runBlocking {
        matrix { manager ->
            val log = "/data/local/tmp/harness-isolation-${UUID.randomUUID()}.log"
            val first = requireNotNull(manager.spawnDetached(arrayOf("/system/bin/sh", "-c", "echo first; sleep 60"), null,
                "/data/local/tmp", log))
            val second = requireNotNull(manager.spawnDetached(arrayOf("/system/bin/sh", "-c", "echo second; sleep 60"), null,
                "/data/local/tmp", "$log.other"))
            try {
                assertTrue(first > 0 && second > 0)
                assertTrue(manager.killProcess(first))
                withTimeout(6_000) { while (manager.isProcessAlive(first)) delay(100) }
                assertTrue("An unrelated job was stopped", manager.isProcessAlive(second))
                assertEquals("usable", command(manager, "echo usable").output.trim())
            } finally {
                manager.killProcess(first); manager.killProcess(second)
                command(manager, "rm -f '$log' '$log.other'")
            }
        }
    }

    @Test fun compatibilityHelperDeathRecoversAutomatically() = runBlocking {
        val manager = manager(true)
        try {
            val helperPid = command(manager, "echo \$PPID").output.trim().toInt()
            assertTrue(helperPid > 1)
            val server = IShizukuService.Stub.asInterface(Shizuku.getBinder())
            val kill = server.newProcess(arrayOf("/system/bin/kill", "-9", helperPid.toString()), null, null)
            try { assertTrue(kill.waitForTimeout(2, "SECONDS")) } finally { kill.destroy() }
            withTimeout(10_000) { manager.serviceState.first { !it.isReady } }
            withTimeout(30_000) { manager.serviceState.first { it.isReady } }
            assertEquals(UserServiceState.COMPAT_READY, manager.serviceState.value)
            assertEquals("2000", command(manager, "id -u").output.trim())
        } finally { manager.close() }
    }

    @Test fun compatibilityUnbindCleansForegroundCommandsAndKeepsDetachedJobs() = runBlocking {
        val manager = manager(true)
        val marker = "/data/local/tmp/harness-unbind-${UUID.randomUUID()}"
        val log = "$marker.log"
        var detached = -1
        try {
            detached = requireNotNull(manager.spawnDetached(arrayOf("/system/bin/sh", "-c", "sleep 60"), null,
                "/data/local/tmp", log))
            val running = async { command(manager, "sleep 60 & echo \$! > '$marker'; wait", timeout = 60_000) }
            val pid = withTimeout(5_000) {
                var value: Int? = null
                while (value == null) {
                    delay(100)
                    value = command(manager, "cat '$marker' 2>/dev/null").output.trim().toIntOrNull()
                }
                value
            }
            manager.unbindUserService()
            running.await()
            manager.refresh()
            withTimeout(30_000) { manager.serviceState.first { it.isReady } }
            withTimeout(6_000) { while (manager.isProcessAlive(pid)) delay(100) }
            assertTrue(manager.isProcessAlive(detached))
        } finally {
            if (detached > 0) manager.killProcess(detached)
            command(manager, "rm -f '$marker' '$log'")
            manager.close()
        }
    }

    @Test fun shellRoutingAndTerminalUseBothPrivilegedBackends() = runBlocking {
        val container = (context.applicationContext as HarnessApp).container
        matrix { manager ->
            val router = ShellTierRouter(context, manager, container.linuxEnv)
            val routed = router.run("id -u; printf routed-error >&2", File("/data/local/tmp"), 10_000, 6_000)
            assertEquals(ExecutionTier.PRIVILEGED, routed.tier)
            assertEquals("2000", routed.rawOutput.trim())
            assertEquals("routed-error", routed.rawStderr)
            val terminal = TerminalManager(context, container.linuxEnv, manager, container.runManager)
            try {
                terminal.useWorkspace(FileFs(File("/data/local/tmp")))
                withTimeout(5_000) { terminal.state.first { it.privileged } }
                terminal.send("id -u; echo terminal-error >&2")
                withTimeout(15_000) { terminal.state.first { !it.busy && it.lastExitCode != null } }
                withTimeout(5_000) { terminal.state.first { it.lines.any { line -> line == "2000" } } }
                assertEquals(0, terminal.state.value.lastExitCode)
                assertTrue(terminal.state.value.lines.contains("terminal-error"))
                terminal.send("cd /system; pwd")
                withTimeout(15_000) { terminal.state.first { !it.busy && it.cwd == "/system" } }
                assertTrue(terminal.state.value.privileged)
            } finally { terminal.stopTerminal() }
        }
    }

    @Test fun bothBackendsRunTheInstalledLinuxToolchain() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("shizukuReady") == "true")
        val container = (context.applicationContext as HarnessApp).container
        assertTrue("Install the emulator Linux environment before this check", container.linuxEnv.isReady)
        // Recreate an older installation's bad header and cached deployment.
        // The ordinary deployment path must repair it, preserving the JS body.
        val nodeScripts = listOf("npm", "npx").map { File(container.linuxEnv.prefix, "bin/$it").canonicalFile }
        nodeScripts.forEach { script ->
            val body = script.readText().substringAfter('\n')
            script.writeText("#!${container.linuxEnv.tmpPrefix}/bin/applets/env node\n$body")
        }
        container.linuxEnv.invalidateExternalToolDeploy()
        matrix { manager ->
            val router = ShellTierRouter(context, manager, container.linuxEnv)
            val result = router.run("""
                set -e
                git --version
                python3 -c 'import os; assert os.getuid() == 2000; print("python-ok")'
                node -e 'if (process.getuid() !== 2000) process.exit(8); console.log("node-ok")'
                npm --version
                npx --version
                pip --version
            """.trimIndent(), File("/data/local/tmp"), 30_000, 6_000)
            assertEquals("${manager.serviceState.value}\n${result.rawOutput}\n${result.rawStderr}", 0, result.exitCode)
            assertEquals(ExecutionTier.PRIVILEGED, result.tier)
            assertTrue(result.rawOutput, result.rawOutput.contains("git version"))
            assertTrue(result.rawOutput, result.rawOutput.contains("python-ok"))
            assertTrue(result.rawOutput, result.rawOutput.contains("node-ok"))
            assertFalse(result.timedOut)
            nodeScripts.forEach { script ->
                assertEquals("#!${container.linuxEnv.tmpPrefix}/bin/env node", script.useLines { it.first() })
            }
        }
    }

    @Test fun deniedPermissionNeverStartsAPrivilegedRunner() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("permissionDenied") == "true")
        val manager = ShizukuManager(context, userServiceTimeoutMs = 300)
        try {
            withTimeout(10_000) { manager.state.first { it == ShizukuState.RUNNING_NO_PERMISSION } }
            delay(500)
            assertFalse(manager.isGranted())
            assertFalse(manager.isServiceReady())
            assertEquals(UserServiceState.NOT_BOUND, manager.serviceState.value)
            assertNull(manager.runPrivileged(arrayOf("/system/bin/id", "-u"), null, null, 1_000))
        } finally { manager.close() }
    }

    /** Run separately; the host restarts Shizuku after each READY_FOR_SERVER_RESTART status. */
    @Test fun serverRestartRecoversBothBackends() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("restartShizuku") == "true")
        matrix { manager ->
            val selected = manager.serviceState.value
            InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
                putString("stream", "READY_FOR_SERVER_RESTART:$selected\n")
            })
            withTimeout(60_000) { manager.state.first { it == ShizukuState.NOT_RUNNING } }
            assertFalse(manager.isServiceReady())
            withTimeout(60_000) { manager.serviceState.first { it.isReady } }
            assertEquals(selected, manager.serviceState.value)
            assertEquals("2000", command(manager, "id -u").output.trim())
        }
    }

    /** Run before force-stopping the app; the next invocation verifies recovery. */
    @Test fun startDetachedJobForAppRestart() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("appRestartPhase") == "start")
        val manager = manager(true)
        try {
            val log = "/data/local/tmp/harness-app-recovery-${context.packageName}.log"
            val pid = requireNotNull(manager.spawnDetached(arrayOf("/system/bin/sh", "-c", "echo recovery-ready; sleep 300"),
                null, "/data/local/tmp", log))
            assertTrue(pid > 0)
            File(context.filesDir, "shizuku-recovery-test.txt").writeText("$pid\n$log")
            assertTrue(manager.isProcessAlive(pid))
        } finally { manager.close() }
    }

    @Test fun recoverDetachedJobAfterAppRestart() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("appRestartPhase") == "recover")
        val manager = manager(true)
        val file = File(context.filesDir, "shizuku-recovery-test.txt")
        try {
            val (pidText, log) = file.readLines()
            val pid = pidText.toInt()
            assertTrue("Detached job did not survive app restart", manager.isProcessAlive(pid))
            assertTrue(command(manager, "cat '$log'").output.contains("recovery-ready"))
            manager.killProcess(pid)
            withTimeout(6_000) { while (manager.isProcessAlive(pid)) delay(100) }
            command(manager, "rm -f '$log'")
            file.delete()
            Unit
        } finally { manager.close() }
    }
}

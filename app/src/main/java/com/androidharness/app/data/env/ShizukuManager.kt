package com.androidharness.app.data.env

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import com.androidharness.app.IHarnessService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

enum class ShizukuState {
    NOT_INSTALLED,
    NOT_RUNNING,
    RUNNING_NO_PERMISSION,
    GRANTED,
}

/** Whether the in-server [HarnessUserService] is available for exec calls. */
enum class UserServiceState {
    NOT_BOUND,
    BINDING,
    BOUND_READY,
    BIND_FAILED,
    COMPAT_READY,
    ;

    val isReady: Boolean get() = this == BOUND_READY || this == COMPAT_READY
}

/** Result of a privileged command executed inside Shizuku's server process. */
data class PrivilegedResult(
    val exitCode: Int,
    val timedOut: Boolean,
    val output: String,
    val stderr: String = "",
)

/**
 * Bridges AndroidHarness to Shizuku. Once Shizuku is running AND the user has
 * granted access, we bind an in-server "user service" ([HarnessUserService])
 * that executes shell commands as the shell (or root) uid, the only reliable
 * way to reach system paths, pm/am, and any folder on the device on modern
 * Android. Also deploys a copy of the app's Linux toolchain to
 * /data/local/tmp/androidharness (a location that uid can exec from).
 */
class ShizukuManager(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    private val userServiceClassName: String = HarnessUserService::class.java.name,
    private val userServiceTimeoutMs: Long = 15_000,
    private val userServiceTag: String? = null,
) {

    internal val workspaceWarning = shizukuWarningController(
        context.getSharedPreferences("shizuku_notices", Context.MODE_PRIVATE),
    )

    private val _state = MutableStateFlow(ShizukuState.NOT_INSTALLED)
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    /** This build's deployed copy; see [LinuxEnvironmentManager.deployedBaseFor]. */
    private val tmpBase: String = LinuxEnvironmentManager.deployedBaseFor(context.packageName)

    @Volatile private var tmpPrefixDeployed = false

    /**
     * Package-set hash plus staged-tarball content hash the deployed copy was
     * extracted from. Readiness is keyed to it: the tier's environment is built
     * from the APP prefix (PATH, the termux-exec preload, the CA bundle, the
     * OpenSSL config), so a copy that came from other bytes is missing exactly
     * the files that env points at, and binaries started in it die in the
     * linker. Installing a package, or any staging that lagged behind for a
     * moment, makes the copy stale on the spot instead of five minutes later
     * when the periodic verify comes round.
     */
    @Volatile private var deployedTag: String? = null

    /**
     * True while the deploy script is gutting and re-extracting
     * /data/local/tmp/androidharness: during that window the deployed prefix
     * is partially absent even though [isTmpPrefixDeployed] stays true (the
     * hash matched at the last deploy), so status tools must not trust it.
     */
    @Volatile private var deploying = false

    fun isDeployInProgress(): Boolean = deploying

    @Volatile private var deployCheckForced = false

    /**
     * Forces the next deploy check to compare hashes again (instead of
     * trusting the cached deployed-state flag): removing the deployed
     * .harness-hash or the staging marker then takes effect on the next
     * privileged command, without an app restart.
     */
    fun invalidateDeployState() {
        deployCheckForced = true
        tmpPrefixDeployed = false
        deployedTag = null
    }

    private val isDebuggable: Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private val appVersionCode: Int = runCatching {
        val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        (pInfo.lastUpdateTime and 0x7FFFFFFF).toInt()
    }.getOrDefault(1)

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, userServiceClassName),
    )
        .daemon(false)
        .processNameSuffix("harness")
        .debuggable(isDebuggable)
        .version(appVersionCode)
        .apply { userServiceTag?.let { tag(it) } }

    private val sdkHandler = Handler(Looper.getMainLooper())
    private val serviceBinding: ShizukuServiceBinding<IHarnessService> = ShizukuServiceBinding(
        scope = scope,
        timeoutMs = userServiceTimeoutMs,
        isAlive = { it.asBinder().pingBinder() },
        bind = { connected, disconnected ->
            val active = AtomicBoolean(true)
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    connected(binder?.let(IHarnessService.Stub::asInterface))
                }
                override fun onServiceDisconnected(name: ComponentName?) = disconnected(ShizukuFailureReason.SERVICE_DISCONNECTED)
                override fun onNullBinding(name: ComponentName?) = connected(null)
                override fun onBindingDied(name: ComponentName?) = disconnected(ShizukuFailureReason.BINDING_DIED)
            }
            // The SDK iterates mutable callback maps on the main looper. Register
            // on that looper and defer unregistering until its callback returns.
            sdkHandler.post {
                if (active.get()) {
                    try { Shizuku.bindUserService(userServiceArgs, connection) }
                    catch (e: Exception) {
                        if (active.get()) serviceBinding.unavailable(
                            ShizukuConnectionFailure.fromException(ShizukuFailureReason.BIND_EXCEPTION, e))
                        sdkHandler.post { runCatching { Shizuku.unbindUserService(userServiceArgs, connection, false) } }
                    }
                }
            }
            val detach: (Boolean) -> Unit = { remove ->
                active.set(false)
                sdkHandler.post { runCatching { Shizuku.unbindUserService(userServiceArgs, connection, remove) } }
            }
            detach
        },
        onDisconnected = {
            scope.launch {
                delay(1_500)
                refresh()
            }
        },
    )
    private val mutableServiceState = MutableStateFlow(UserServiceState.NOT_BOUND)
    val serviceState: StateFlow<UserServiceState> = mutableServiceState.asStateFlow()
    val connectionFailure: StateFlow<ShizukuConnectionFailure?> = serviceBinding.failure
    private val service: IHarnessService? get() = serviceBinding.service
    @Volatile private var compatibility: ShizukuCompatClient? = null
    private var compatibilityJob: Job? = null
    private var compatibilityGeneration = 0L
    @Volatile private var closed = false

    private val receivedListener = Shizuku.OnBinderReceivedListener { refresh() }
    private val deadListener = Shizuku.OnBinderDeadListener {
        clearCompatibility()
        serviceBinding.disconnect(failure = ShizukuConnectionFailure(ShizukuFailureReason.SERVER_LOST))
        mutableServiceState.value = UserServiceState.NOT_BOUND
        _state.value = ShizukuState.NOT_RUNNING
    }
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            workspaceWarning.onStatus(ShizukuState.GRANTED)
            _state.value = ShizukuState.GRANTED
            bindUserService()
        }
    }

    @Synchronized private fun clearCompatibility() {
        compatibilityGeneration++
        compatibilityJob?.cancel()
        compatibilityJob = null
        val previous = compatibility
        compatibility = null
        previous?.close()
    }

    @Synchronized private fun observeServiceState(value: UserServiceState) {
        if (closed) return
        when (value) {
            UserServiceState.BOUND_READY -> {
                clearCompatibility()
                mutableServiceState.value = value
            }
            UserServiceState.BIND_FAILED -> {
                mutableServiceState.value = value
                val reason = connectionFailure.value?.reason
                if (isGranted() && reason in setOf(ShizukuFailureReason.TIMEOUT,
                        ShizukuFailureReason.BIND_EXCEPTION, ShizukuFailureReason.NULL_BINDER)) {
                    startCompatibility()
                } else if (isGranted() && reason in setOf(ShizukuFailureReason.SERVICE_DISCONNECTED,
                        ShizukuFailureReason.BINDING_DIED, ShizukuFailureReason.DEAD_BINDER,
                        ShizukuFailureReason.BINDER_CHECK_FAILED)) {
                    scope.launch { delay(1_500); refresh() }
                }
            }
            else -> {
                clearCompatibility()
                mutableServiceState.value = value
            }
        }
    }

    @Synchronized private fun startCompatibility() {
        if (closed || compatibilityJob?.isActive == true) return
        val generation = ++compatibilityGeneration
        mutableServiceState.value = UserServiceState.BINDING
        compatibilityJob = scope.launch {
            var launched: ShizukuCompatClient? = null
            try {
                val client = ShizukuCompatClient.start(context.applicationInfo.sourceDir) {
                    synchronized(this@ShizukuManager) {
                        if (compatibility === launched && launched != null) {
                            clearCompatibility()
                            serviceBinding.unavailable(ShizukuConnectionFailure(ShizukuFailureReason.COMPAT_DISCONNECTED))
                            mutableServiceState.value = UserServiceState.BIND_FAILED
                            scope.launch { delay(1_500); refresh() }
                        }
                    }
                }
                launched = client
                synchronized(this@ShizukuManager) {
                    if (generation == compatibilityGeneration && isGranted() &&
                        serviceBinding.state.value == UserServiceState.BIND_FAILED) {
                        check(client.isAlive) { "Compatibility runner exited during startup" }
                        compatibility = client
                        mutableServiceState.value = UserServiceState.COMPAT_READY
                    } else client.close()
                }
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                synchronized(this@ShizukuManager) {
                    if (generation == compatibilityGeneration) {
                        serviceBinding.unavailable(ShizukuConnectionFailure.fromException(ShizukuFailureReason.COMPAT_START_FAILED, e))
                        mutableServiceState.value = UserServiceState.BIND_FAILED
                    }
                }
            } finally {
                synchronized(this@ShizukuManager) {
                    if (generation == compatibilityGeneration) compatibilityJob = null
                }
            }
        }
    }

    /** Releases a test/session-owned manager, including all SDK listeners and helper pipes. */
    internal fun close() {
        closed = true
        clearCompatibility()
        serviceBinding.disconnect(remove = true)
        runCatching { Shizuku.removeBinderReceivedListener(receivedListener) }
        runCatching { Shizuku.removeBinderDeadListener(deadListener) }
        runCatching { Shizuku.removeRequestPermissionResultListener(permissionListener) }
        scope.cancel()
    }

    /** Explicitly copied connection report, separate from the general report that excludes raw errors. */
    fun connectionReport(): String = buildString {
        appendLine("AndroidHarness Shizuku connection")
        appendLine("App: ${com.androidharness.app.BuildConfig.VERSION_NAME} (${com.androidharness.app.BuildConfig.VERSION_CODE})")
        appendLine("Package: ${context.packageName}")
        appendLine("Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        appendLine("Shizuku API: ${runCatching { Shizuku.getVersion().toString() }.getOrDefault("unavailable")}")
        appendLine("Shizuku server: ${runCatching { "${Shizuku.getVersion()}.${Shizuku.getServerPatchVersion()}" }.getOrDefault("unavailable")}")
        appendLine("Shizuku app: ${runCatching {
            val info = context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
            "${info.versionName} (${if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()})"
        }.getOrDefault("unavailable")}")
        appendLine("Shizuku status: ${state.value}")
        appendLine("Shell runner: ${serviceState.value}")
        appendLine("Service: $userServiceClassName; version: $appVersionCode")
        append(connectionFailure.value?.report() ?: "No connection failure recorded in this app session.")
    }

    suspend fun startupReport(): ShizukuStartupReport {
        val failure = connectionFailure.value
        val report = connectionReport()
        val now = System.currentTimeMillis()
        // Limit the search to recent startup attempts even if the last failure is old.
        val since = maxOf((failure?.occurredAtMillis ?: now) - 60_000, now - 300_000)
        val (log, status) = collectShizukuStartupLog(context.packageName, since)
        return ShizukuStartupReport("$report\n\nStartup log since ${java.time.Instant.ofEpochMilli(since)}\n$status\n$log", status)
    }

    init {
        scope.launch { serviceBinding.state.collect(::observeServiceState) }
        refresh()

        runCatching {
            Shizuku.addBinderReceivedListener(receivedListener)
        }
        runCatching {
            Shizuku.addBinderDeadListener(deadListener)
        }
        runCatching {
            Shizuku.addRequestPermissionResultListener(permissionListener)
        }

        // The Shizuku binder may not be connected yet during Application.onCreate.
        // Retry after a short delay and then once more a bit later.
        scope.launch {
            delay(2_000)
            refresh()
            if (_state.value == ShizukuState.NOT_RUNNING) {
                delay(5_000)
                refresh()
            }
        }
    }

    fun refresh() {
        if (closed) return
        val ping = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val hasPermission = ping && runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

        val s = when {
            hasPermission -> ShizukuState.GRANTED
            ping -> ShizukuState.RUNNING_NO_PERMISSION
            else -> ShizukuState.NOT_RUNNING
        }
        workspaceWarning.onStatus(s)
        _state.value = s
        if (s == ShizukuState.GRANTED) bindUserService() else {
            clearCompatibility()
            serviceBinding.disconnect()
            mutableServiceState.value = UserServiceState.NOT_BOUND
        }
    }

    fun isGranted(): Boolean = _state.value == ShizukuState.GRANTED

    fun requestPermission() {
        val granted = runCatching {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (granted) {
            workspaceWarning.onStatus(ShizukuState.GRANTED)
            _state.value = ShizukuState.GRANTED
            bindUserService()
            return
        }
        runCatching { Shizuku.requestPermission(0) }
    }

    /** Recheck the live binder instead of trusting the last displayed status. */
    internal fun checkWorkspaceWarning() {
        refresh()
        workspaceWarning.checkWorkspace(_state.value)
    }

    /**
     * Binds the in-server user service so privilege exec works. Requires Shizuku
     * API >= 11; records a connection failure otherwise (callers fall back).
     */
    fun bindUserService() {
        if (closed) return
        val hasPermission = runCatching {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (!hasPermission) {
            clearCompatibility()
            serviceBinding.disconnect()
            return
        }
        if (compatibility?.isAlive == true || compatibilityJob?.isActive == true) return
        val version = try {
            Shizuku.getVersion()
        } catch (e: Exception) {
            serviceBinding.unavailable(ShizukuConnectionFailure.fromException(ShizukuFailureReason.VERSION_CHECK_FAILED, e))
            return
        }
        if (version < 11) {
            serviceBinding.unavailable(ShizukuConnectionFailure(ShizukuFailureReason.UNSUPPORTED_VERSION,
                "Server API: $version; required: 11 or newer."))
            return
        }
        serviceBinding.connect()
    }

    fun unbindUserService() {
        clearCompatibility()
        serviceBinding.disconnect(remove = true)
        mutableServiceState.value = UserServiceState.NOT_BOUND
    }

    /** True when permission and a callable normal or compatibility runner are available. */
    fun isServiceReady(): Boolean = isGranted() && (service != null || compatibility?.isAlive == true)

    /**
     * Runs [cmd] inside the Shizuku server process (shell/root uid). Returns null
     * when the user service is unavailable so callers can fall back to the app uid.
     */
    suspend fun runPrivileged(
        cmd: Array<String>,
        env: Array<String>?,
        dir: String?,
        timeoutMs: Int,
        maxBytes: Int = 60_000,
    ): PrivilegedResult? = withContext(Dispatchers.IO) {
        val svc = service
        val raw = if (svc != null) {
            runCatching { svc.exec(cmd, withDeployPaths(env), dir, maxBytes, timeoutMs) }.getOrNull()
                ?: return@withContext null
        } else {
            val client = compatibility?.takeIf { it.isAlive } ?: return@withContext null
            try {
                client.request(CompatRequest(0, "exec", cmd.toList(), withDeployPaths(env).toList(), dir,
                    maxBytes = maxBytes, timeoutMs = timeoutMs))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                // A submitted command may already have changed files. Return a failure,
                // never null, so callers cannot replay it under the app UID.
                return@withContext PrivilegedResult(-1, false, "", "Compatibility command failed: ${e.message}")
            }
        }
        parseResult(raw)
    }

    /**
     * Adds this build's deployed paths to the environment of every privileged
     * command. The service runs inside Shizuku's process and has no way to know
     * which build called it, so it takes these from the request rather than
     * assuming the legacy shared directory, which another installed build may
     * well have deployed over.
     */
    private fun withDeployPaths(env: Array<String>?): Array<String> {
        val extra = arrayOf(
            "HARNESS_TMP_PREFIX=$tmpBase/linux",
            "HARNESS_TMP_LIB=$tmpBase/linux/lib",
            "HARNESS_TMP_BIN=$tmpBase/linux/bin",
        )
        if (env == null) return extra
        val kept = env.filterNot { entry ->
            extra.any { it.substringBefore('=') == entry.substringBefore('=') }
        }
        return (extra.asList() + kept).toTypedArray()
    }

    private fun parseResult(raw: String): PrivilegedResult {
        val lines = raw.split('\n', limit = 3)
        val exit = lines.getOrNull(0)?.removePrefix("exit=")?.toIntOrNull() ?: -1
        val timedOut = lines.getOrNull(1)?.removePrefix("timeout=")?.toIntOrNull() == 1
        val body = lines.getOrNull(2) ?: ""
        val sep = HarnessUserService.STDERR_SEPARATOR
        val sepIdx = body.indexOf(sep)
        return if (sepIdx >= 0) {
            val stdoutPart = body.substring(0, sepIdx).trimEnd('\n')
            val stderrPart = body.substring(sepIdx + sep.length).removePrefix("\n")
            PrivilegedResult(exit, timedOut, stdoutPart, stderrPart)
        } else {
            PrivilegedResult(exit, timedOut, body)
        }
    }

    /**
     * Deploys (or refreshes) the shell-user toolchain at
     * this build's own directory under /data/local/tmp/androidharness by
     * untarring the staging tarball
     * (shared storage, shell-readable) there. No-op when the deployed copy
     * already matches [tag], which is the package set plus the content hash of
     * the tarball it must have come from.
     */
    suspend fun ensureTmpPrefix(stagingTarPath: String, tag: String): Boolean {
        val base = tmpBase
        // Bug 1 fix: locate the staged CA bundle (next to the tarball) so the
        // deployed toolchain gets trust anchors; fall back gracefully.
        val stagingDir = File(stagingTarPath).parentFile
        val caSource = File(stagingDir, "etc/tls/cacert.pem")
        val caInstall = if (caSource.isFile) {
            "mkdir -p \"$base/linux/etc/tls\" && cp \"${caSource.absolutePath}\" " +
                "\"$base/linux/etc/tls/cacert.pem\" && "
        } else ""
        val check = runPrivileged(
            arrayOf("/system/bin/sh", "-c", "test -x \"$base/linux/bin/bash\" && cat \"$base/.harness-hash\""),
            env = null, dir = null, timeoutMs = 15_000, maxBytes = 2_000,
        ) ?: return false
        if (check.exitCode == 0 && check.output.trim() == tag) {
            tmpPrefixDeployed = true
            deployedTag = tag
            deployCheckForced = false
            return true
        }
        deploying = true
        try {
            val script = buildString {
                append("rm -rf \"$base\" && ")
                append("mkdir -p \"$base\" && ")
                append("tar -xzf \"$stagingTarPath\" -C \"$base\" && ")
                append(caInstall)
                append("chmod -R 755 \"$base\" && ")
                // /data/local/tmp is o+x-traversable and the blanket 755 leaves
                // everything group/other-readable: without this, ANY app that
                // guesses the path could read the token-bearing copies below.
                append("chmod 700 \"$base\" \"$base/linux\" && ")
                // The gh config dirs were left 0777 by the blanket 755; the token
                // FILES inside are 0600, but the dirs cost nothing to tighten.
                append("chmod 700 \"$base/linux/home/.config\" \"$base/linux/home/.config/gh\" 2>/dev/null; ")
                append("chmod 600 \"$base/linux/home/.gh-token\" " +
                    "\"$base/linux/home/.config/gh/hosts.yml\" " +
                    "\"$base/linux/etc/gitconfig\" 2>/dev/null; ")
                append("echo '$tag' > \"$base/.harness-hash\" && ")
                append("test -x \"$base/linux/bin/bash\" && ")
                append("echo DEPLOY_OK")
            }
            val r = runPrivileged(
                arrayOf("/system/bin/sh", "-c", script),
                env = null,
                dir = null,
                timeoutMs = 300_000,
                maxBytes = 20_000,
            )
            val ok = r != null && r.exitCode == 0 && r.output.contains("DEPLOY_OK")
            if (ok) {
                tmpPrefixDeployed = true
                deployedTag = tag
            }
            deployCheckForced = false
            return ok
        } finally {
            deploying = false
        }
    }

    /**
     * Whether the shell-user toolchain can be executed from /data/local/tmp.
     *
     * Pass [expectedTag] (the tag the caller's environment was built for, from
     * `LinuxEnvironmentManager.deployedTag()`) to also require the deployed copy
     * to have come from those exact bytes. Anything that runs the deployed
     * binaries with the app-side environment must ask the strict question: a
     * copy that only answers "bin/bash exists" can be missing the very
     * libraries that environment names, and the linker then refuses every
     * binary with `CANNOT LINK EXECUTABLE ... library "..." not found`.
     */
    suspend fun isTmpPrefixDeployed(expectedTag: String? = null): Boolean {
        // A cached success only counts for the tag it was deployed from;
        // anything else has to be re-checked (and re-deployed by
        // ensureTmpPrefix) before the tier may exec from the copy.
        if (tmpPrefixDeployed && !deployCheckForced &&
            (expectedTag == null || deployedTag == expectedTag)
        ) return true
        val base = tmpBase
        val script = if (expectedTag != null) {
            "test -x \"$base/linux/bin/bash\" && cat \"$base/.harness-hash\""
        } else {
            "test -x \"$base/linux/bin/bash\" && echo OK"
        }
        val r = runPrivileged(
            arrayOf("/system/bin/sh", "-c", script),
            env = null,
            dir = null,
            timeoutMs = 10_000,
            maxBytes = 2_000,
        )
        val matched = r != null && r.exitCode == 0 &&
            if (expectedTag != null) r.output.trim() == expectedTag else r.output.contains("OK")
        if (matched && !deployCheckForced) {
            // A forced check stays un-cached: callers must go through
            // ensureTmpPrefix so the redeploy actually happens.
            tmpPrefixDeployed = true
            if (expectedTag != null) deployedTag = expectedTag
        } else {
            tmpPrefixDeployed = false
            deployedTag = null
        }
        return tmpPrefixDeployed
    }

    /** Checks a path from the privileged side (existence + listable). */
    suspend fun privilegedCanAccess(path: String): Boolean {
        val r = runPrivileged(
            arrayOf("/system/bin/sh", "-c", "test -d '$path' && ls -l \"$path\" >/dev/null 2>&1 && echo OK"),
            env = null,
            dir = null,
            timeoutMs = 10_000,
            maxBytes = 2_000,
        )
        return r != null && r.exitCode == 0 && r.output.contains("OK")
    }

    // ------------------------------------------------------------------
    // Detached background processes (live in the Shizuku server process,
    // so they survive the app being killed)
    // ------------------------------------------------------------------

    /** Returns the pid, or null when the user service is unavailable. */
    suspend fun spawnDetached(
        cmd: Array<String>,
        env: Array<String>?,
        dir: String?,
        logPath: String,
    ): Int? = withContext(Dispatchers.IO) {
        val svc = service
        if (svc != null) runCatching { svc.spawnDetached(cmd, env, dir, logPath) }.getOrNull()
        else compatibility?.takeIf { it.isAlive }?.request(CompatRequest(0, "spawn", cmd.toList(),
            withDeployPaths(env).toList(), dir, logPath))?.toIntOrNull()
    }

    suspend fun isProcessAlive(pid: Int): Boolean = withContext(Dispatchers.IO) {
        val svc = service
        if (svc != null) runCatching { svc.isProcessAlive(pid) }.getOrDefault(false)
        else compatibility?.takeIf { it.isAlive }?.request(CompatRequest(0, "alive", pid = pid))?.toBooleanStrictOrNull() ?: false
    }

    suspend fun killProcess(pid: Int): Boolean = withContext(Dispatchers.IO) {
        val svc = service
        if (svc != null) runCatching { svc.killProcess(pid) }.getOrDefault(false)
        else compatibility?.takeIf { it.isAlive }?.request(CompatRequest(0, "kill", pid = pid))?.toBooleanStrictOrNull() ?: false
    }
}

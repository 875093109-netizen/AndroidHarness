package com.androidharness.app.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.BuildConfig
import com.androidharness.app.data.AppSettings
import com.androidharness.app.data.env.EnvState
import com.androidharness.app.data.env.ShizukuState
import com.androidharness.app.data.env.UserServiceState
import com.androidharness.app.llm.ModelCatalog
import com.androidharness.app.llm.effectiveApiKey
import com.androidharness.app.ui.common.SystemGrants
import com.androidharness.app.ui.common.rememberStorageAccess
import com.androidharness.app.workspace.WorkspaceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

@Composable
internal fun TroubleshootingSection(container: AppContainer, onOpen: (SettingsPage) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val storage = rememberStorageAccess()
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val providers by container.providers.providers.collectAsStateWithLifecycle(initialValue = emptyList())
    val project by container.workspace.currentProject.collectAsStateWithLifecycle(initialValue = null)
    val env by container.linuxEnv.state.collectAsStateWithLifecycle()
    val shizuku by container.shizuku.state.collectAsStateWithLifecycle()
    val service by container.shizuku.serviceState.collectAsStateWithLifecycle()
    val connectionFailure by container.shizuku.connectionFailure.collectAsStateWithLifecycle()
    val ssh by container.sshConnections.statuses.collectAsStateWithLifecycle()
    val servers by container.mcp.servers.collectAsStateWithLifecycle()
    val tools by container.mcp.statuses.collectAsStateWithLifecycle()
    val tampered by container.mcp.configTampered.collectAsStateWithLifecycle()
    val provider = providers.firstOrNull { it.id == settings.activeProviderId }
    var catalog by remember(provider) { mutableStateOf(CatalogCheck.NOT_CHECKED) }
    var folder by remember(project) { mutableStateOf(DiagnosticState.NOT_CHECKED) }
    var revision by remember { mutableIntStateOf(0) }
    var copied by remember { mutableStateOf(false) }
    var notifications by remember { mutableStateOf((SystemGrants.isPostNotificationsGranted(context) && androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled())) }
    val owner = LocalLifecycleOwner.current
    fun refresh() {
        storage.refresh(); container.shizuku.refresh()
        notifications = (SystemGrants.isPostNotificationsGranted(context) && androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled())
        revision++
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    suspend fun checkFolder(): DiagnosticState = withContext(Dispatchers.IO) {
        try {
            val selected = project ?: return@withContext DiagnosticState.ISSUE
            val fs = container.workspace.fsFor(selected)
            val node = fs.resolve(".")
            if (!node.exists || !node.isDirectory || fs.shellRoot?.canRead() == false) DiagnosticState.ISSUE
            else { node.list(); DiagnosticState.READY }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { DiagnosticState.ISSUE }
    }
    LaunchedEffect(project, revision, storage.granted) {
        folder = if (project?.kind == WorkspaceManager.KIND_SSH) DiagnosticState.NOT_CHECKED else {
            folder = DiagnosticState.CHECKING
            checkFolder()
        }
    }
    val providerState = when {
        provider == null -> DiagnosticState.ISSUE
        catalog == CatalogCheck.CHECKING -> DiagnosticState.CHECKING
        catalog == CatalogCheck.REACHABLE -> DiagnosticState.REACHABLE
        catalog == CatalogCheck.NOT_CHECKED -> DiagnosticState.NOT_CHECKED
        else -> DiagnosticState.ISSUE
    }
    val linuxState = when (env) {
        EnvState.NotInstalled -> DiagnosticState.OFF
        EnvState.Ready -> DiagnosticState.INSTALLED
        is EnvState.Failed -> DiagnosticState.ISSUE
        else -> DiagnosticState.CHECKING
    }
    val shizukuState = shizukuDiagnostic(shizuku == ShizukuState.GRANTED,
        service.isReady, service == UserServiceState.BINDING, service == UserServiceState.BIND_FAILED)
    val enabledServers = servers.filter { it.enabled }
    val mcpState = when {
        tampered -> DiagnosticState.ISSUE
        enabledServers.isEmpty() -> DiagnosticState.OFF
        enabledServers.any { tools[it.name]?.state in setOf("failed", "auth") } -> DiagnosticState.ISSUE
        enabledServers.any { tools[it.name]?.state == "connecting" } -> DiagnosticState.CHECKING
        enabledServers.all { tools[it.name]?.state == "connected" } -> DiagnosticState.READY
        else -> DiagnosticState.NOT_CHECKED
    }
    val sshState = when {
        ssh.isEmpty() -> DiagnosticState.NOT_CHECKED
        ssh.values.any { it.uncertain } -> DiagnosticState.ISSUE
        ssh.values.any { it.connected } -> DiagnosticState.READY
        else -> DiagnosticState.OFF
    }
    val states = linkedMapOf(DiagnosticArea.PROVIDER to providerState, DiagnosticArea.STORAGE to
        if (storage.granted) DiagnosticState.READY else DiagnosticState.ISSUE,
        DiagnosticArea.WORKSPACE to folder, DiagnosticArea.LINUX to linuxState, DiagnosticArea.SHIZUKU to shizukuState,
        DiagnosticArea.SSH to sshState, DiagnosticArea.MCP to mcpState,
        DiagnosticArea.NOTIFICATIONS to if (notifications) DiagnosticState.READY else DiagnosticState.OFF)
    val report = diagnosticReport(BuildConfig.VERSION_NAME, Build.VERSION.SDK_INT, states, providers.size, enabledServers.size)
    LaunchedEffect(report) { copied = false }
    Text("Checks for your default provider and workspace. Optional features can stay off.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = ::refresh) { Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Refresh status") }
        OutlinedButton(onClick = {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Troubleshooting", report))
            copied = true
        }) { Text(if (copied) "Copied" else "Copy report") }
        TextButton(onClick = { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, report)
        }, "Share troubleshooting report")) }) { Text("Share report") }
    }
    Text("Reports exclude credentials, addresses, file paths and chats.", style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    DiagnosticCard(DiagnosticArea.PROVIDER.label, providerState,
        if (provider == null) "Choose a provider to use the assistant." else "${provider.name}\n${catalog.detail}") {
        val local = provider?.let { com.androidharness.app.local.LocalModelCatalog.isLocal(it.id) } == true
        if (provider != null && !local) TextButton(enabled = catalog != CatalogCheck.CHECKING, onClick = {
            scope.launch {
                catalog = CatalogCheck.CHECKING
                try {
                    val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
                    val result = withTimeout(25_000) { ModelCatalog.listModels(provider,
                        provider.effectiveApiKey(container.providers.apiKey(provider.id)), client) }
                    catalog = when (result) {
                        is ModelCatalog.Result.Models -> if (result.models.isEmpty()) CatalogCheck.EMPTY else CatalogCheck.REACHABLE
                        is ModelCatalog.Result.Failed -> catalogFailure(result.message)
                    }
                } catch (_: TimeoutCancellationException) { catalog = CatalogCheck.TIMEOUT }
                catch (e: kotlinx.coroutines.CancellationException) { catalog = CatalogCheck.NOT_CHECKED; throw e }
                catch (_: Exception) { catalog = CatalogCheck.FAILED }
            }
        }) { Text("Check models") }
        TextButton(onClick = { onOpen(if (local) SettingsPage.LOCAL_MODELS else SettingsPage.MODELS) }) { Text("Manage provider") }
    }
    DiagnosticCard("Storage access · required", states.getValue(DiagnosticArea.STORAGE),
        if (storage.granted) "The app has storage permission." else "Grant storage access to complete setup and use device folders.") {
        if (!storage.granted) TextButton(onClick = storage.request) { Text("Grant access") }
    }
    DiagnosticCard(DiagnosticArea.WORKSPACE.label, folder, (project?.name?.let { "$it\n" } ?: "") + when (folder) {
        DiagnosticState.READY -> "The folder can be read. Write access has not been tested."
        DiagnosticState.ISSUE -> "The folder is unavailable. Choose it again or check its connection."
        DiagnosticState.CHECKING -> "Checking folder access…"
        else -> "Remote folder access has not been checked."
    }) {
        if (project?.kind == WorkspaceManager.KIND_SSH) TextButton(enabled = folder != DiagnosticState.CHECKING, onClick = {
            scope.launch { folder = DiagnosticState.CHECKING; folder = checkFolder() }
        }) { Text("Check folder") }
        TextButton(onClick = { onOpen(SettingsPage.WORKSPACE) }) { Text("Open workspaces") }
    }
    DiagnosticCard("Linux tools · optional", linuxState, when (linuxState) {
        DiagnosticState.INSTALLED -> "Toolchain installed. Individual commands have not been tested here."
        DiagnosticState.ISSUE -> "Installation needs attention. Open terminal settings to repair or retry."
        DiagnosticState.CHECKING -> "Installation is in progress."
        else -> "Install Linux tools to run local commands."
    }) { TextButton(onClick = { onOpen(SettingsPage.ENVIRONMENT) }) { Text("Open terminal settings") } }
    DiagnosticCard("Shizuku · optional", shizukuState, when {
        service == UserServiceState.COMPAT_READY -> "Permission granted. Commands are ready in compatibility mode."
        shizukuState == DiagnosticState.READY -> "Permission granted and command service ready."
        shizuku == ShizukuState.RUNNING_NO_PERMISSION -> "Shizuku is running. Grant permission in terminal settings."
        shizukuState == DiagnosticState.CHECKING -> "Connecting to the command service…"
        shizukuState == DiagnosticState.ISSUE -> "The command service failed to connect. Reconnect in terminal settings."
        shizuku == ShizukuState.GRANTED -> "Permission granted. The command service is not connected."
        else -> "Start Shizuku to use commands with system access."
    }, extraDetails = { ShizukuFailureDetails(connectionFailure, container.shizuku::connectionReport,
        container.shizuku::startupReport, recovered = service == UserServiceState.COMPAT_READY) }) {
        TextButton(onClick = { onOpen(SettingsPage.ENVIRONMENT) }) { Text("Open Shizuku settings") }
    }
    DiagnosticCard("SSH workspaces · optional", sshState, when (sshState) {
        DiagnosticState.READY -> "${ssh.values.count { it.connected }} connection(s) active. Check a folder separately to confirm access."
        DiagnosticState.ISSUE -> "A connection needs attention. Review it in Workspaces."
        else -> "No active SSH connections. Connect through Workspaces when needed."
    }) { TextButton(onClick = { onOpen(SettingsPage.WORKSPACE) }) { Text("Manage connections") } }
    DiagnosticCard("Connected tools · optional", mcpState, when (mcpState) {
        DiagnosticState.READY -> "All ${enabledServers.size} enabled servers are connected. Tool execution has not been tested here."
        DiagnosticState.ISSUE -> if (tampered) "Server configuration failed its integrity check. Review and save it in Connected tools." else "A server needs sign-in, reconnection or configuration review."
        DiagnosticState.OFF -> "No enabled MCP servers."
        DiagnosticState.CHECKING -> "A server is connecting…"
        else -> "Some enabled servers have not connected yet."
    }) { TextButton(onClick = { onOpen(SettingsPage.MCP) }) { Text("Manage tools") } }
    DiagnosticCard("Notifications · optional", states.getValue(DiagnosticArea.NOTIFICATIONS),
        if (notifications) "Notifications are allowed by Android." else "You can use the app with notifications off.") {
        TextButton(onClick = { context.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)) }) { Text("Notification settings") }
    }
}

@Composable
private fun DiagnosticCard(title: String, state: DiagnosticState, detail: String,
    extraDetails: @Composable () -> Unit = {}, actions: @Composable RowScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val tint = when (state) {
        DiagnosticState.ISSUE -> scheme.error
        DiagnosticState.READY, DiagnosticState.REACHABLE -> scheme.primary
        else -> scheme.onSurfaceVariant
    }
    Surface(shape = RoundedCornerShape(16.dp), color = scheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(when (state) {
                    DiagnosticState.ISSUE -> Icons.Outlined.ErrorOutline
                    DiagnosticState.READY, DiagnosticState.REACHABLE -> Icons.Outlined.CheckCircleOutline
                    else -> Icons.Outlined.Info
                }, null, Modifier.size(18.dp), tint = tint)
                Text(state.label, style = MaterialTheme.typography.labelMedium, color = tint)
            }
            Text(detail, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            extraDetails()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), content = actions)
        }
    }
}

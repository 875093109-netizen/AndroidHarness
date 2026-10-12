package com.androidharness.app.ui.settings

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.data.ChatModelState
import com.androidharness.app.telegram.*
import com.androidharness.app.ui.common.SecureDialogEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable internal fun TelegramSection(container: AppContainer) {
    val manager = container.telegram
    val setup by manager.setup.state.collectAsStateWithLifecycle()
    val projects by container.workspace.projects.collectAsStateWithLifecycle(initialValue = emptyList())
    val sessions by container.sessions.sessions.collectAsStateWithLifecycle(initialValue = emptyList())
    val models by remember(setup.sessionId) { container.chatModels.observe(setup.sessionId) }.collectAsStateWithLifecycle(initialValue = null)
    TelegramSettingsContent(manager, projects.map { TelegramChoiceItem(it.id, it.name) },
        sessions.filter { it.projectId == setup.projectId }.map { TelegramChoiceItem(it.id, it.title.ifBlank { "Untitled chat" }) }, models,
        onOpenChat = { setup.sessionId?.let { container.pendingSessionId.tryEmit(it) } },
        onPermissions = { container.pendingSettingsScroll.value = "agent" })
}

@Composable internal fun TelegramSettingsContent(manager: TelegramManager, projects: List<TelegramChoiceItem>, chats: List<TelegramChoiceItem>,
    modelState: ChatModelState? = null, onOpenChat: () -> Unit = {}, onPermissions: () -> Unit = {}) {
    val context = LocalContext.current
    val setup by manager.setup.state.collectAsStateWithLifecycle()
    val runtime by manager.runtime.collectAsStateWithLifecycle()
    val pairing by manager.pairing.collectAsStateWithLifecycle()
    val saved by manager.store.state.collectAsStateWithLifecycle()
    val serviceError by manager.error.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var tokenDialog by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var disconnectDialog by remember { mutableStateOf(false) }
    var helpDialog by remember { mutableStateOf(false) }
    var showAllSaved by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    LaunchedEffect(message, failed) {
        if (message != null && !failed) { delay(3_000); message = null }
    }
    val canConfigure = saved.requests.none { it.active || it.status == TelegramRequestStatus.QUEUED ||
        it.status == TelegramRequestStatus.RECEIVED && it.callback == null && !it.text.startsWith('/') }
    fun action(success: String, block: suspend () -> Unit) {
        busy = true; message = null; failed = false
        scope.launch {
            try { block(); message = success }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { failed = true; message = com.androidharness.app.tools.SecretRedactor.redact(e.message ?: "Could not finish this action. Please retry.") }
            finally { busy = false }
        }
    }
    fun openBot(username: String, code: String? = null) {
        val link = "https://t.me/$username" + (code?.let { "?start=pair_$it" } ?: "")
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: Exception) { failed = true; message = "Open @$username in Telegram." }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        action("Telegram enabled.") { manager.enable() }
    }
    fun enable() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else action("Telegram enabled.") { manager.enable() }
    }
    TelegramPanel {
        val canEnable = setup.ownerId != null && !busy && pairing.challenge == null && manager.store.loadError == null
        val status = when {
            serviceError != null || manager.store.loadError != null -> "Needs attention"
            pairing.challenge != null -> "Pairing"
            setup.enabled -> runtime.label
            else -> "Off"
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.weight(1f).semantics { contentDescription = "Enable Telegram" }
                .toggleable(value = setup.enabled, enabled = canEnable, role = Role.Switch,
                    onValueChange = { checked -> if (checked) enable() else action("Telegram paused.") { manager.pause() } }),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(38.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Outlined.Send, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(22.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Remote control", style = MaterialTheme.typography.titleSmall)
                    Text(status, style = MaterialTheme.typography.labelMedium,
                        color = if (serviceError != null || manager.store.loadError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
                Switch(checked = setup.enabled, enabled = canEnable, onCheckedChange = null)
            }
            Box {
                IconButton(onClick = { moreMenu = true }) { Icon(Icons.Outlined.MoreVert, "More options") }
                DropdownMenu(moreMenu, { moreMenu = false }) {
                    if (setup.botUsername != null) {
                        DropdownMenuItem(text = { Text("Change token") }, enabled = !busy && canConfigure,
                            onClick = { moreMenu = false; token = ""; visible = false; tokenDialog = true })
                        DropdownMenuItem(text = { Text(if (setup.ownerId == null) "Pair account" else "Pair again") },
                            enabled = !busy && canConfigure && pairing.challenge == null,
                            onClick = { moreMenu = false; action("Pairing started.") { manager.beginPairing() } })
                    }
                    DropdownMenuItem(text = { Text("Send test message") }, enabled = setup.ownerId != null && !busy,
                        onClick = { moreMenu = false; action("Test message sent.") { manager.sendConnectionTest() } })
                    DropdownMenuItem(text = { Text("Stop task") }, enabled = setup.sessionId != null && !busy,
                        onClick = { moreMenu = false; action("Task stopped.") { manager.stopTasks() } })
                    DropdownMenuItem(text = { Text("Battery settings") }, onClick = {
                        moreMenu = false
                        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    })
                    if (setup.ownerId != null) DropdownMenuItem(text = { Text("Unpair account") }, enabled = !busy,
                        onClick = { moreMenu = false; action("Account unpaired.") { manager.unpair() } })
                    if (setup.botUsername != null) DropdownMenuItem(text = { Text("Disconnect bot", color = MaterialTheme.colorScheme.error) }, enabled = !busy,
                        onClick = { moreMenu = false; disconnectDialog = true })
                }
            }
        }
        Text("Chat and edit your projects from Telegram.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (setup.ownerId != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { setup.botUsername?.let { openBot(it) } }, enabled = !busy) { Text("Open bot") }
                if (!canConfigure) OutlinedButton(enabled = !busy, onClick = { action("Task stopped.") { manager.stopTasks() } }) { Text("Stop task") }
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        (serviceError ?: manager.store.loadError ?: pairing.error ?: if (failed) message else runtime.message)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = if (failed || serviceError != null || pairing.error != null || manager.store.loadError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!failed) message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        runtime.action?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    }
    TelegramGroup("Connection", Icons.Outlined.AccountCircle) {
        TelegramDetail("Bot", setup.botUsername?.let { "@$it" } ?: "Not connected", setup.botUsername != null)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
        TelegramDetail("Account", setup.ownerName?.let { "$it · Paired" } ?: "Not paired", setup.ownerId != null)
        if (setup.botUsername == null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy, onClick = { token = ""; visible = false; tokenDialog = true }) { Text("Connect bot") }
                TextButton(enabled = !busy, onClick = { openBot("BotFather") }) { Text("Create bot") }
            }
        } else if (setup.ownerId == null) {
            Button(enabled = !busy && canConfigure && pairing.challenge == null,
                onClick = { action("Pairing started.") { manager.beginPairing() } }) { Text("Pair account") }
        }
    }
    TelegramGroup("Workspace", Icons.Outlined.FolderOpen) {
        TelegramChoice("Project", projects.firstOrNull { it.id == setup.projectId }?.label ?: "Choose project", projects, !busy && canConfigure && projects.isNotEmpty()) { id -> action("Project selected.") { manager.selectDestination(id, null) } }
        TelegramChoice("Chat", chats.firstOrNull { it.id == setup.sessionId }?.label ?: "New Telegram chat", listOf(TelegramChoiceItem("", "New Telegram chat")) + chats, !busy && canConfigure && setup.projectId != null) { id -> action("Chat selected.") { manager.selectDestination(requireNotNull(setup.projectId), id.ifBlank { null }) } }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = setup.sessionId != null && !busy, onClick = onOpenChat) { Text("Open chat") }
            TextButton(enabled = setup.projectId != null && !busy && canConfigure, onClick = { action("New chat ready.") { manager.newChat() } }) { Text("New chat") }
        }
    }
    val activeChoice = if (modelState?.settings?.planningModelsEnabled == true) {
        (if (setup.mode == "PLAN") modelState.selection.planning else modelState.selection.execution) ?: modelState.selection.active
    } else modelState?.selection?.active
    var providerChoice by remember(activeChoice?.providerId) { mutableStateOf(activeChoice?.providerId) }
    var modelChoices by remember { mutableStateOf(emptyList<TelegramChoiceItem>()) }
    var providers by remember { mutableStateOf(emptyList<TelegramChoiceItem>()) }
    var loadingModels by remember { mutableStateOf(false) }
    var modelError by remember { mutableStateOf<String?>(null) }
    var refreshModels by remember { mutableIntStateOf(0) }
    LaunchedEffect(setup.sessionId, activeChoice?.providerId) {
        try { providers = manager.bridge.providers(); if (providerChoice == null) providerChoice = manager.target()?.providerId }
        catch (e: CancellationException) { throw e } catch (_: Exception) { }
    }
    LaunchedEffect(providerChoice, refreshModels) {
        val id = providerChoice ?: return@LaunchedEffect
        loadingModels = true; modelError = null
        try { modelChoices = manager.bridge.models(id) }
        catch (e: CancellationException) { throw e } catch (e: Exception) { modelError = e.message ?: "Could not refresh models. Please retry." }
        finally { loadingModels = false }
    }
    TelegramGroup("Agent", Icons.Outlined.Tune) {
        if (modelState?.settings?.sameModelAcrossChats == true) Text("Shared across chats", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TelegramChoice("Provider", providers.firstOrNull { it.id == providerChoice }?.label ?: "Choose provider", providers, !busy && canConfigure && setup.sessionId != null) { providerChoice = it }
        val selectedModel = if (providerChoice == activeChoice?.providerId) activeChoice?.model else null
        TelegramChoice("Model", selectedModel ?: "Choose model", modelChoices, !busy && !loadingModels && canConfigure && setup.sessionId != null) { id -> action("Model selected.") { manager.selectModel(requireNotNull(providerChoice), id) } }
        if (loadingModels) LinearProgressIndicator(Modifier.fillMaxWidth())
        modelError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        TextButton(enabled = !loadingModels && !busy, onClick = { refreshModels++ }) { Text("Refresh models") }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = setup.mode == "PLAN", enabled = !busy && canConfigure && setup.ownerId != null, onClick = { action("Plan mode selected.") { manager.setMode("PLAN") } }, label = { Text("Plan") })
            FilterChip(selected = setup.mode == "ACT", enabled = !busy && canConfigure && setup.ownerId != null, onClick = { action("Act mode selected.") { manager.setMode("ACT") } }, label = { Text("Act") })
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onPermissions).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Outlined.Security, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f)) {
                Text("Permissions", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(modelState?.settings?.permissionMode?.label ?: "App default", style = MaterialTheme.typography.bodyMedium)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    TextButton(onClick = { helpDialog = true }) {
        Icon(Icons.AutoMirrored.Outlined.HelpOutline, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Help & commands")
    }
    val requests = saved.requests.filter { it.retryable && it.sessionId != null && it.callback == null }.asReversed()
    val deliveries = saved.deliveries.filter { it.status in listOf(TelegramDeliveryStatus.FAILED, TelegramDeliveryStatus.UNCERTAIN) }.asReversed()
    if (requests.isNotEmpty() || deliveries.isNotEmpty()) TelegramGroup("Saved work", Icons.Outlined.Tune) {
        (if (showAllSaved) requests else requests.take(5)).forEach { request ->
            Text("Request #${request.id}", style = MaterialTheme.typography.titleSmall)
            Text(request.text.take(160), style = MaterialTheme.typography.bodySmall)
            Text(request.error ?: "Interrupted", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            TextButton(enabled = !busy, onClick = { action("Task queued.") { manager.retry(request.id) } }) { Text("Retry task") }
            HorizontalDivider()
        }
        (if (showAllSaved) deliveries else deliveries.take(5)).forEach { delivery ->
            Text(delivery.text.take(160), style = MaterialTheme.typography.bodySmall)
            Text(delivery.error ?: "Reply delivery failed. Check Telegram before resending.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            TextButton(enabled = !busy, onClick = { action("Reply queued.") { manager.resend(delivery.key) } }) { Text("Resend reply") }
            HorizontalDivider()
        }
        if (requests.size > 5 || deliveries.size > 5) TextButton(onClick = { showAllSaved = !showAllSaved }) { Text(if (showAllSaved) "Show fewer" else "Show all saved work") }
    }
    if (tokenDialog) AlertDialog(onDismissRequest = { if (!busy) { tokenDialog = false; token = "" } }, title = { Text("Connect your bot") }, text = {
        SecureDialogEffect()
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Paste the token from BotFather.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(token, { token = it }, singleLine = true, label = { Text("Bot token") }, visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(), trailingIcon = {
                IconButton(onClick = { visible = !visible }) { Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (visible) "Hide bot token" else "Show bot token") }
            }, modifier = Modifier.fillMaxWidth())
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (failed) message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = { TextButton(enabled = !busy && token.isNotBlank(), onClick = { action("Bot verified.") { manager.connect(token); tokenDialog = false; token = "" } }) { Text("Verify bot") } }, dismissButton = { TextButton(enabled = !busy, onClick = { tokenDialog = false; token = "" }) { Text("Cancel") } })
    val challenge = pairing.challenge
    if (challenge != null && pairing.candidate == null) {
        var remaining by remember(challenge) { mutableLongStateOf((challenge.expiresAt - System.currentTimeMillis()).coerceAtLeast(0)) }
        LaunchedEffect(challenge) { while (remaining > 0) { delay(1_000); remaining = (challenge.expiresAt - System.currentTimeMillis()).coerceAtLeast(0) } }
        AlertDialog(onDismissRequest = { manager.cancelPairing() }, title = { Text("Pair your account") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Open @${setup.botUsername} and tap Start, or copy this code.")
                Text("/pair ${challenge.code}", style = MaterialTheme.typography.titleMedium)
                Text("Expires in ${remaining / 60_000}:${((remaining / 1_000) % 60).toString().padStart(2, '0')}", style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Telegram pairing", "/pair ${challenge.code}")) }) { Text("Copy code") }
            }
        }, confirmButton = { TextButton(onClick = { setup.botUsername?.let { openBot(it, challenge.code) } }) { Text("Open bot and pair") } }, dismissButton = { TextButton(onClick = { manager.cancelPairing() }) { Text("Cancel") } })
    }
    pairing.candidate?.let { candidate -> AlertDialog(onDismissRequest = { manager.cancelPairing() }, title = { Text("Pair this account?") }, text = {
        Text("${candidate.account.name}\nUser ID ${candidate.account.userId}\n\nAllow this account to control your chats?")
    }, confirmButton = { TextButton(enabled = !busy, onClick = { action("Account paired.") { manager.confirmPairing() } }) { Text("Pair account") } }, dismissButton = { TextButton(onClick = { manager.cancelPairing() }) { Text("Cancel") } }) }
    if (disconnectDialog) AlertDialog(onDismissRequest = { disconnectDialog = false }, title = { Text("Disconnect bot?") }, text = { Text("Stop Telegram and keep your chats and files?") },
        confirmButton = { TextButton(enabled = !busy, onClick = { disconnectDialog = false; action("Bot disconnected.") { manager.disconnect() } }) { Text("Disconnect") } }, dismissButton = { TextButton(onClick = { disconnectDialog = false }) { Text("Cancel") } })
    if (helpDialog) AlertDialog(onDismissRequest = { helpDialog = false }, title = { Text("Telegram commands") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Send a message to chat, then use buttons to approve tools or answer questions.", style = MaterialTheme.typography.bodySmall)
            listOf("/projects" to "Projects", "/chats" to "Chats", "/new" to "New chat", "/model" to "Model",
                "/mode plan" to "Plan", "/mode act" to "Act", "/status" to "Status", "/stop" to "Stop task",
                "/retry" to "Retry / resume", "/pause" to "Pause Telegram").forEach { (command, title) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(command, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text("Keep your phone online; the notification has Pause and Stop controls.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { helpDialog = false }) { Text("Done") } })
}

@Composable private fun TelegramPanel(content: @Composable ColumnScope.() -> Unit) = SettingsPanel(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
}
@Composable private fun TelegramGroup(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.padding(start = 4.dp).semantics(mergeDescendants = true) { heading() },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TelegramPanel(content)
    }
}
@Composable private fun TelegramDetail(title: String, value: String, connected: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (connected) Icon(Icons.Outlined.Check, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun TelegramChoice(label: String, value: String, options: List<TelegramChoiceItem>, enabled: Boolean, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded, { if (enabled) expanded = !expanded }) {
        OutlinedTextField(value, {}, readOnly = true, enabled = enabled, singleLine = true, shape = MaterialTheme.shapes.medium, label = { Text(label) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled).fillMaxWidth())
        ExposedDropdownMenu(expanded, { expanded = false }) { options.forEach { item -> DropdownMenuItem(text = { Text(item.label) }, onClick = { expanded = false; onSelect(item.id) }) } }
    }
}

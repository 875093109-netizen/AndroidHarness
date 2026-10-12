package com.androidharness.app.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.AppContainer
import com.androidharness.app.telegram.*
import com.androidharness.app.ui.common.SecureDialogEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
internal fun TelegramSection(container: AppContainer) {
    val repository = remember(container) { TelegramSetupRepository(container.keys::telegramConnection, container.keys::putTelegramConnection) }
    val kiloTester = remember { TelegramKiloReplyTest() }
    val projects by container.workspace.projects.collectAsStateWithLifecycle(initialValue = emptyList())
    val sessions by container.sessions.sessions.collectAsStateWithLifecycle(initialValue = emptyList())
    TelegramSetupContent(repository, projects.map { it.id to it.name },
        sessions.map { TelegramChatChoice(it.id, it.projectId, it.title) }, container.appContext, kiloTester)
}

internal data class TelegramChatChoice(val id: String, val projectId: String?, val title: String)

@Composable
internal fun TelegramSetupContent(
    repository: TelegramSetupRepository,
    projects: List<Pair<String, String>>,
    chats: List<TelegramChatChoice>,
    context: Context,
    kiloTester: TelegramKiloReplyTest? = null,
) {
    val setup by repository.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var tokenDialog by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf("") }
    var showToken by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var pairing by remember { mutableStateOf<TelegramPairing?>(null) }
    var candidate by remember { mutableStateOf<TelegramPairingCandidate?>(null) }
    var pairingJob by remember { mutableStateOf<Job?>(null) }
    var preview by remember { mutableStateOf(false) }
    var removeDialog by remember { mutableStateOf(false) }
    var kiloModels by remember { mutableStateOf(emptyList<String>()) }
    var kiloModel by remember { mutableStateOf(com.androidharness.app.llm.HarnessProvider.DEFAULT_MODEL) }
    var kiloLoading by remember { mutableStateOf(false) }
    var kiloJob by remember { mutableStateOf<Job?>(null) }
    var kiloReply by remember { mutableStateOf<TelegramModelReply?>(null) }

    fun reportError(e: Exception) {
        failed = true
        message = (e as? TelegramSetupException)?.problem?.hint ?: "Could not finish Telegram setup. Please retry."
    }
    fun action(success: String, block: suspend () -> Unit): Job {
        busy = true
        message = null
        failed = false
        return scope.launch {
            try { block(); message = success }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { reportError(e) }
            finally { busy = false }
        }
    }
    LaunchedEffect(kiloTester, setup.ownerId) {
        if (kiloTester != null && setup.ownerId != null) {
            kiloLoading = true
            try {
                kiloModels = kiloTester.models()
                if (kiloModel !in kiloModels) kiloModel = kiloModels.first()
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { reportError(e)
            } finally { kiloLoading = false }
        } else kiloModels = emptyList()
    }
    fun openTelegram(username: String) {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/$username")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: Exception) { failed = true; message = "Could not open Telegram. Open @$username in Telegram." }
    }
    fun cancelPairing() {
        repository.cancelPairing()
        pairingJob?.cancel()
        pairing = null
        candidate = null
        busy = false
    }
    DisposableEffect(repository) { onDispose { repository.close() } }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_STOP) {
        if (kiloJob?.isActive == true) {
            kiloJob?.cancel()
            busy = false
            failed = false
            message = "Kilo test stopped when the app left the screen."
        }
    }

    TelegramPanel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Outlined.Send, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Text("Telegram remote control", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                Text("Preview", Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
            }
        }
        Text("Set up your bot, pair your account and try a Kilo reply. Agent tasks and background listening are not enabled yet.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall,
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
    }

    TelegramPanel {
        TelegramStep("1", "Your bot", if (setup.botUsername == null) "Create a bot with BotFather, then paste its token." else "@${setup.botUsername} · Bot verified")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (setup.botUsername == null) OutlinedButton(onClick = { openTelegram("BotFather") }, enabled = !busy) { Text("Create bot") }
            Button(onClick = { token = ""; showToken = false; tokenDialog = true; message = null }, enabled = !busy) {
                Text(if (setup.botUsername == null) "Connect bot" else "Change token")
            }
        }
        if (setup.botUsername != null) TextButton(onClick = { removeDialog = true }, enabled = !busy) { Text("Disconnect bot") }
    }

    TelegramPanel {
        TelegramStep("2", "Pair your account", setup.ownerName?.let { "$it · Paired" } ?: "Only the account you confirm here will be paired.")
        if (setup.ownerId != null) Text("User ID ${setup.ownerId}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = setup.botUsername != null && !busy, onClick = {
                try {
                    val challenge = repository.beginPairing()
                    pairing = challenge
                    busy = true
                    message = null
                    failed = false
                    pairingJob = scope.launch {
                        try { candidate = repository.awaitPairing(challenge); pairing = null }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { pairing = null; reportError(e) }
                        finally { busy = false }
                    }
                } catch (e: Exception) { reportError(e) }
            }) { Text(if (setup.ownerId == null) "Pair account" else "Pair again") }
            if (setup.ownerId != null) TextButton(enabled = !busy, onClick = { action("Account unpaired.") { repository.unpair() } }) { Text("Unpair") }
        }
    }

    val projectExists = projects.any { it.first == setup.projectId }
    val eligibleChats = chats.filter { it.projectId == setup.projectId }
    val chatExists = setup.sessionId == null || eligibleChats.any { it.id == setup.sessionId }
    TelegramPanel {
        TelegramStep("3", "Destination", "Choose where future Telegram messages should go.")
        TelegramChoice("Project", projects.firstOrNull { it.first == setup.projectId }?.second ?: "Choose project", projects,
            enabled = !busy && projects.isNotEmpty(), onSelect = { id -> action("Destination saved.") { repository.selectDestination(id, null) } })
        TelegramChoice("Chat", eligibleChats.firstOrNull { it.id == setup.sessionId }?.title?.ifBlank { "Untitled chat" } ?: "New Telegram chat",
            listOf("" to "New Telegram chat") + eligibleChats.map { it.id to it.title.ifBlank { "Untitled chat" } },
            enabled = !busy && projectExists, onSelect = { id -> action("Destination saved.") {
                repository.selectDestination(requireNotNull(setup.projectId), id.ifEmpty { null })
            } })
        if (setup.projectId != null && (!projectExists || !chatExists)) Text("This destination is no longer available. Choose it again.",
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        Text("Your phone will run the selected model. Messages sent through Telegram pass through Telegram's service.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    TelegramPanel {
        TelegramStep("4", "Try the connection", "Send one setup message to your paired account.")
        Button(enabled = setup.ownerId != null && !busy, onClick = {
            action("Test message sent. Check your Telegram chat.") { repository.sendTestMessage() }
        }) { Text("Send test message") }
        OutlinedButton(onClick = { preview = true }, enabled = !busy) { Text("Preview command flow") }
    }

    if (kiloTester != null) TelegramPanel {
        TelegramStep("5", "Try Kilo replies", "Use a free Kilo model for a sample answer or one question from your paired Telegram account. Keep this screen open while listening. File changes and agent tools are disabled.")
        if (kiloLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        TelegramChoice("Free Kilo model", kiloModel, kiloModels.map { it to it },
            enabled = !busy && !kiloLoading && kiloModels.isNotEmpty(), onSelect = { kiloModel = it })
        TextButton(enabled = setup.ownerId != null && !busy && !kiloLoading, onClick = {
            kiloJob = action("Kilo models refreshed.") {
                kiloModels = kiloTester.models()
                if (kiloModel !in kiloModels) kiloModel = kiloModels.first()
            }
        }) { Text("Refresh Kilo models") }
        Button(enabled = setup.ownerId != null && !busy && !kiloLoading && kiloModel in kiloModels, onClick = {
            kiloReply = null
            kiloJob = action("Kilo reply sent. Check your Telegram chat.") {
                message = "Asking $kiloModel…"
                kiloReply = repository.sendKiloSample(kiloModel, kiloTester)
            }
        }) { Text("Send Kilo sample") }
        OutlinedButton(enabled = setup.ownerId != null && !busy && !kiloLoading && kiloModel in kiloModels, onClick = {
            kiloReply = null
            kiloJob = action("Kilo reply sent. Check your Telegram chat.") {
                message = "Listening for /ask from your paired account…"
                kiloReply = repository.receiveKiloQuestion(kiloModel, kiloTester)
            }
        }) { Text("Listen for one question") }
        Text("Send /ask followed by your question within 10 minutes. Only one question is answered per test.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (busy && kiloJob?.isActive == true) TextButton(onClick = {
            kiloJob?.cancel()
            busy = false
            failed = false
            message = "Kilo test stopped."
        }) { Text("Stop Kilo test") }
        kiloReply?.let { reply ->
            Text(reply.model, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Text(reply.text, style = MaterialTheme.typography.bodySmall)
        }
    }

    if (tokenDialog) AlertDialog(
        onDismissRequest = { if (!busy) { tokenDialog = false; token = ""; showToken = false } },
        title = { Text("Connect Telegram bot") },
        text = {
            SecureDialogEffect()
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Paste the token from BotFather. It is stored encrypted on this phone.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(token, { if (it.length <= 256) token = it }, singleLine = true, enabled = !busy,
                    label = { Text("Bot token") }, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { IconButton(onClick = { showToken = !showToken }, enabled = !busy) {
                        Icon(if (showToken) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            if (showToken) "Hide bot token" else "Show bot token")
                    } })
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (failed) message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(enabled = !busy && token.isNotBlank(), onClick = {
            val entered = token
            action("Bot verified. Pair your Telegram account next.") {
                repository.connect(entered)
                token = ""
                showToken = false
                tokenDialog = false
            }
        }) { Text("Verify bot") } },
        dismissButton = { TextButton(enabled = !busy, onClick = { tokenDialog = false; token = ""; showToken = false }) { Text("Cancel") } },
    )
    pairing?.let { challenge -> AlertDialog(
        onDismissRequest = ::cancelPairing,
        title = { Text("Pair your account") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Send this exact message to @${setup.botUsername}. The code expires in 2 minutes.")
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
                Text("/pair ${challenge.code}", Modifier.padding(12.dp), style = MaterialTheme.typography.titleMedium)
            }
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Waiting for your message…", style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { TextButton(onClick = { openTelegram(requireNotNull(setup.botUsername)) }) { Text("Open bot") } },
        dismissButton = { Row {
            TextButton(onClick = {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("Telegram pairing", "/pair ${challenge.code}"))
            }) { Text("Copy code") }
            TextButton(onClick = ::cancelPairing) { Text("Cancel") }
        } },
    ) }
    candidate?.let { found -> AlertDialog(
        onDismissRequest = ::cancelPairing,
        title = { Text("Pair this account?") },
        text = { Text("${found.account.name}\nUser ID ${found.account.userId}\n\nConfirm that this is your Telegram account.") },
        confirmButton = { TextButton(onClick = {
            try { repository.confirmPairing(found); candidate = null; message = "Account paired. You can send a test message."; failed = false }
            catch (e: Exception) { candidate = null; reportError(e) }
        }) { Text("Pair account") } },
        dismissButton = { TextButton(onClick = ::cancelPairing) { Text("Cancel") } },
    ) }
    if (removeDialog) AlertDialog(onDismissRequest = { removeDialog = false }, title = { Text("Disconnect bot?") },
        text = { Text("Remove the saved bot token and paired account from this phone.") },
        confirmButton = { TextButton(onClick = { removeDialog = false; action("Bot disconnected.") { repository.disconnect() } }) { Text("Disconnect") } },
        dismissButton = { TextButton(onClick = { removeDialog = false }) { Text("Cancel") } })
    if (preview) AlertDialog(onDismissRequest = { preview = false }, title = { Text("Command flow preview") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Sample conversation · nothing is sent or executed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            TelegramExample("You", "Summarize this project")
            TelegramExample("AndroidHarness", "Working in your selected project…")
            TelegramExample("Approval", "Allow this file edit?  Allow once · Deny")
            TelegramExample("You", "/status  ·  /stop  ·  /new")
            Text("Planned: replies, tool approvals, project switching and scheduled-task notifications.", style = MaterialTheme.typography.bodySmall)
        } }, confirmButton = { TextButton(onClick = { preview = false }) { Text("Done") } })
}

@Composable
private fun TelegramPanel(content: @Composable ColumnScope.() -> Unit) {
    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun TelegramStep(number: String, title: String, detail: String) {
    Text("$number. $title", style = MaterialTheme.typography.titleSmall)
    Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TelegramChoice(label: String, value: String, options: List<Pair<String, String>>, enabled: Boolean, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded, { if (enabled) expanded = !expanded }) {
        OutlinedTextField(value, {}, readOnly = true, enabled = enabled, label = { Text(label) },
            singleLine = true, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled).fillMaxWidth())
        ExposedDropdownMenu(expanded, { expanded = false }) {
            options.forEach { (id, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { expanded = false; onSelect(id) }) }
        }
    }
}

@Composable
private fun TelegramExample(speaker: String, text: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(speaker, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

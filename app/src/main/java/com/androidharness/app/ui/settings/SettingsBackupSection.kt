package com.androidharness.app.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.androidharness.app.AppContainer
import com.androidharness.app.data.SettingsBackup
import com.androidharness.app.data.SettingsBackupFile
import com.androidharness.app.data.settingsBackupExportError
import com.androidharness.app.ui.common.SecureDialogEffect
import com.androidharness.app.ui.common.SecureScreenEffect
import com.androidharness.app.ui.common.ThinLinearProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun SettingsBackupSection(container: AppContainer) {
    val backup = remember(container) { SettingsBackup(container) }
    val scope = rememberCoroutineScope()
    var destination by remember { mutableStateOf<Uri?>(null) }
    var source by remember { mutableStateOf<Uri?>(null) }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var includeKeys by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<SettingsBackupFile?>(null) }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) {
        destination = it
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { source = it }
    fun clear() {
        password = ""
        confirmation = ""
        source = null
        destination = null
        preview = null
        includeKeys = false
    }
    SecureScreenEffect(container, source != null || destination != null || preview != null || busy)
    SettingsPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Settings backup", style = MaterialTheme.typography.titleSmall)
            Text("Password-encrypted API providers and preferences, with optional API keys and MCP configuration. Local models are excluded. Chats have a separate backup.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { message = null; exportPicker.launch("androidharness-settings.hbackup") }, enabled = !busy) { Text("Export") }
                OutlinedButton(onClick = { message = null; importPicker.launch(arrayOf("application/octet-stream", "*/*")) }, enabled = !busy) { Text("Import") }
            }
            if (busy) ThinLinearProgress(Modifier.fillMaxWidth())
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    if ((destination != null || source != null) && preview == null) {
        val exporting = destination != null
        AlertDialog(
            onDismissRequest = { if (!busy) clear() },
            title = { Text(if (exporting) "Encrypt settings" else "Unlock backup") },
            text = {
                SecureDialogEffect()
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (exporting) "Use at least 12 characters. A lost password cannot be recovered. Nothing is uploaded by the app; your chosen file provider may sync the encrypted file." else "Enter the backup password. Settings are not changed until you review and confirm.")
                    OutlinedTextField(password, { if (it.length <= 1024) password = it }, label = { Text("Password") },
                        singleLine = true, enabled = !busy, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                    if (exporting) {
                        OutlinedTextField(confirmation, { if (it.length <= 1024) confirmation = it }, label = { Text("Confirm password") },
                            singleLine = true, enabled = !busy, visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                        Row {
                            Checkbox(includeKeys, { includeKeys = it }, enabled = !busy)
                            Text("Include credentials and MCP servers", modifier = Modifier.padding(top = 12.dp))
                        }
                        Text("MCP commands, URLs and headers can contain secrets, so MCP servers are included only with credentials. OAuth sessions, permissions, app lock and workspace access are never restored.", style = MaterialTheme.typography.bodySmall)
                    }
                    if (busy) ThinLinearProgress(Modifier.fillMaxWidth())
                    if (failed) message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = !busy && password.length >= 12 && (!exporting || password == confirmation), onClick = {
                    val chars = password.toCharArray()
                    password = ""
                    confirmation = ""
                    val target = destination
                    val input = source
                    busy = true
                    failed = false
                    scope.launch {
                        try {
                            if (target != null) {
                                backup.exportTo(target, chars, includeKeys)
                                clear()
                                message = "Encrypted settings exported. Keep the password separately."
                            } else if (input != null) {
                                preview = backup.read(input, chars)
                                source = null
                            }
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) {
                            failed = true
                            message = if (exporting) settingsBackupExportError(e) else "Could not unlock backup. Check the password and file."
                        } finally { chars.fill('\u0000'); busy = false }
                    }
                }) { Text(if (exporting) "Encrypt" else "Unlock") }
            },
            dismissButton = { TextButton(onClick = { clear() }, enabled = !busy) { Text("Cancel") } },
        )
    }
    preview?.let { file ->
        AlertDialog(
            onDismissRequest = { if (!busy) clear() },
            title = { Text("Restore settings?") },
            text = {
                SecureDialogEffect()
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("${file.providers.count { it.config.id != com.androidharness.app.llm.HarnessProvider.ID }} providers · ${file.servers.size} MCP servers")
                    Text("Providers are added as new connections, not overwritten. Preferences and selected models will change. Existing app lock, screenshot policy and permissions stay unchanged. MCP servers are added disabled; review them before enabling.")
                    Text("Only restore a backup you trust. Imported provider endpoints can receive future messages. Restoring does not contact them.")
                    file.providers.filterNot { it.config.id == com.androidharness.app.llm.HarnessProvider.ID }.forEach { entry ->
                        Text("${entry.config.name}\n${entry.config.baseUrl}", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Missing search and voice keys are restored when included. Existing keys are kept. GitHub tokens and OAuth sessions are excluded.", style = MaterialTheme.typography.bodySmall)
                    if (busy) ThinLinearProgress(Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    try {
                        backup.restore(file)
                        failed = false
                        message = "Settings restored. Review new connections before use."
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) {
                        failed = true
                        message = "Restore did not finish. Stop active tasks and check connections before retrying; some entries may have been added."
                    } finally { clear(); busy = false }
                }
            }) { Text("Restore") } },
            dismissButton = { TextButton(onClick = { clear() }, enabled = !busy) { Text("Cancel") } },
        )
    }
}

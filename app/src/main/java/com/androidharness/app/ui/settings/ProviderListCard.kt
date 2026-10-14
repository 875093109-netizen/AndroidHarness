package com.androidharness.app.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.llm.ProviderConfig
import com.androidharness.app.ui.common.ProviderMark

/** Actions have their own row so names stay readable in narrow windows and at large text sizes. */
@Composable
internal fun ProviderListCard(
    provider: ProviderConfig,
    active: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onSelect: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onMove: (Int) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onBrowseModels: (() -> Unit)? = null,
    catalogSize: Int? = null,
) {
    val scheme = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    val editable = provider.id != com.androidharness.app.llm.HarnessProvider.ID &&
        !com.androidharness.app.chatgpt.ChatGptProtocol.isProvider(provider.id) &&
        !com.androidharness.app.local.LocalModelCatalog.isLocal(provider.id)
    Surface(
        color = if (active) scheme.secondaryContainer else scheme.surfaceContainerLow,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, if (active) scheme.primary.copy(alpha = .3f) else scheme.outlineVariant.copy(alpha = .35f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).heightIn(min = 48.dp)
                    .clickable(enabled = provider.enabled, onClick = onSelect)
                    .semantics { contentDescription = "Select ${provider.name}" },
                    verticalAlignment = Alignment.CenterVertically) {
                    ProviderMark(size = 36.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(provider.name, style = MaterialTheme.typography.titleSmallEmphasized,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(if (active) "Active" else null, if (!provider.enabled) "Disabled" else null,
                            provider.model).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(when {
                            provider.id == com.androidharness.app.llm.HarnessProvider.ID -> "Built-in · Free"
                            com.androidharness.app.chatgpt.ChatGptProtocol.isProvider(provider.id) -> "ChatGPT plan"
                            com.androidharness.app.local.LocalModelCatalog.isLocal(provider.id) -> "On-device"
                            catalogSize != null && catalogSize > 0 -> "$catalogSize ${if (catalogSize == 1) "model" else "models"} available"
                            else -> provider.baseUrl
                        }, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Actions for ${provider.name}")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Move up") }, enabled = canMoveUp,
                            onClick = { menu = false; onMove(-1) })
                        DropdownMenuItem(text = { Text("Move down") }, enabled = canMoveDown,
                            onClick = { menu = false; onMove(1) })
                        if (editable) {
                            DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; onEdit() })
                            DropdownMenuItem(text = { Text("Delete", color = scheme.error) },
                                onClick = { menu = false; onDelete() })
                        }
                    }
                }
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.heightIn(min = 48.dp)
                    .toggleable(provider.enabled, role = Role.Switch, onValueChange = onEnabledChange)
                    .semantics { contentDescription = "Enable ${provider.name}" },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Switch(checked = provider.enabled, onCheckedChange = null)
                    Text(if (provider.enabled) "Enabled" else "Disabled", style = MaterialTheme.typography.labelMedium)
                }
                if (!provider.enabled) TextButton(onClick = onSelect) { Text("Enable & use") }
                onBrowseModels?.let { browse -> TextButton(onClick = browse) { Text("Browse models") } }
            }
        }
    }
}

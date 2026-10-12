package com.androidharness.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.androidharness.app.data.WorkspaceLayoutMode

@Composable
internal fun WorkspaceLayoutSection(selected: WorkspaceLayoutMode, onSelect: (WorkspaceLayoutMode) -> Unit) {
    SettingsAnchor("Workspace layout") {
        SettingsPanel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp).selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Workspace layout", style = MaterialTheme.typography.titleSmall)
                Text("Narrow windows keep one full-width panel.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                WorkspaceLayoutMode.entries.forEach { mode ->
                    Row(Modifier.fillMaxWidth().selectable(selected == mode, role = Role.RadioButton,
                        onClick = { onSelect(mode) }).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected == mode, onClick = null)
                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                            Text(mode.label, style = MaterialTheme.typography.bodyLarge)
                            Text(mode.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

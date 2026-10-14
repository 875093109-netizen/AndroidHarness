package com.androidharness.app.ui.settings

import androidx.compose.animation.Crossfade
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.androidharness.app.llm.ProviderModelSearch
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.androidharness.app.llm.ModelsDev
import com.androidharness.app.llm.ProviderConfig
import com.androidharness.app.llm.ProviderType
import com.androidharness.app.ui.theme.fastEffectsSpec
import kotlinx.coroutines.launch

/**
 * Provider management without leaving the conversation: a fully-expanded
 * bottom sheet with two pages, a list (tap = activate + close; edit/delete
 * icons mirror the Providers screen) and the shared add/edit form
 * ([ProviderSheetContent]) reached with a forward/back slide. The full-screen
 * Providers destination still exists for bulk management from the drawer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderManagerSheet(
    providers: List<ProviderConfig>,
    activeProviderId: String?,
    apiKey: (providerId: String) -> String?,
    onDismiss: () -> Unit,
    onSetActive: (String) -> Unit,
    onDelete: (String) -> Unit,
    onEnabledChange: (String, Boolean) -> Unit,
    onMove: (String, Int, List<String>) -> Unit,
    onSave: (
        existing: ProviderConfig?,
        name: String,
        type: ProviderType,
        baseUrl: String,
        model: String,
        apiKey: String,
    ) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<ProviderConfig?>(null) }
    var showForm by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var showDisabled by rememberSaveable { mutableStateOf(false) }
    val visible = providers.filter { (it.enabled || showDisabled || query.isNotBlank()) &&
        ProviderModelSearch.providerMatches(it, emptyList(), query) }
    val visibleIds = visible.map { it.id }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Crossfade(
            targetState = showForm,
            animationSpec = fastEffectsSpec(),
            label = "provider manager page",
        ) { inForm ->
            if (!inForm) {
                LazyColumn(
                    Modifier.fillMaxWidth().height((LocalConfiguration.current.screenHeightDp * .9f).dp)
                        .padding(horizontal = 16.dp).navigationBarsPadding(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Providers", style = MaterialTheme.typography.titleMediumEmphasized, modifier = Modifier.weight(1f))
                            IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close") }
                        }
                        Text("Disabled providers stay searchable. Existing chats keep their model.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FilledTonalButton(onClick = { editing = null; showForm = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("Add provider")
                        }
                        OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Search providers") },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        FilterChip(selected = showDisabled, onClick = { showDisabled = !showDisabled }, label = { Text("Show disabled") })
                    }
                    itemsIndexed(visible, key = { _, p -> p.id }) { index, provider ->
                        ProviderListCard(provider, provider.id == activeProviderId, index > 0, index < visible.lastIndex,
                            onSelect = {
                                if (!provider.enabled) onEnabledChange(provider.id, true)
                                onSetActive(provider.id)
                                onDismiss()
                            },
                            onEnabledChange = { onEnabledChange(provider.id, it) },
                            onMove = { onMove(provider.id, it, visibleIds) },
                            onEdit = { editing = provider; showForm = true },
                            onDelete = { onDelete(provider.id) })
                    }
                    if (visible.isEmpty()) item {
                        Text("No matching providers. Try searching or show disabled providers.",
                            modifier = Modifier.padding(vertical = 20.dp))
                    }
                    item {
                        val context = LocalContext.current
                        var catalogBusy by remember { mutableStateOf(false) }
                        var catalogStatus by remember { mutableStateOf<String?>(null) }
                        Column {
                            TextButton(onClick = {
                                scope.launch {
                                    catalogBusy = true
                                    try {
                                        catalogStatus = ModelsDev.refresh(context, force = true)
                                            ?: "Catalog updated"
                                    } finally { catalogBusy = false }
                                }
                            }, enabled = !catalogBusy) { Text(if (catalogBusy) "Updating catalog…" else "Update model catalog") }
                            catalogStatus?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                }
            } else {
                // The form owns its step headers + back navigation; closing at
                // step one returns to the list page.
                ProviderSheetContent(
                    existing = editing,
                    existingKey = editing?.let { apiKey(it.id) },
                    onDismiss = { showForm = false },
                    onSave = { name, type, baseUrl, model, key ->
                        onSave(editing, name, type, baseUrl, model, key)
                        showForm = false
                        editing = null
                    },
                )
            }
        }
    }
}

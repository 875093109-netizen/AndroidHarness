package com.androidharness.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.androidharness.app.llm.ProviderModelSearch
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.androidharness.app.AppContainer
import com.androidharness.app.data.AppSettings
import com.androidharness.app.llm.ProviderConfig
import com.androidharness.app.llm.effectiveApiKey
import com.androidharness.app.ui.chat.components.ModelPickerSheet
import com.androidharness.app.ui.common.AppHeader
import com.androidharness.app.ui.common.ProviderMark
import kotlinx.coroutines.launch

@Composable
fun ProvidersScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    // The provider list is where API keys are entered and revealed.
    val providers by container.providers.providers.collectAsStateWithLifecycle(initialValue = emptyList())
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val catalogs by container.providers.catalogs.collectAsStateWithLifecycle(initialValue = emptyMap())
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<ProviderConfig?>(null) }
    var showDialog by remember { mutableStateOf(false) }
    var showModelsFor by remember { mutableStateOf<String?>(null) }
    val scheme = MaterialTheme.colorScheme
    var query by rememberSaveable { mutableStateOf("") }
    var showDisabled by rememberSaveable { mutableStateOf(false) }
    var showAllModels by remember { mutableStateOf(false) }
    val visibleProviders = providers.filter { (it.enabled || showDisabled || query.isNotBlank()) &&
        ProviderModelSearch.providerMatches(it, catalogs[it.id].orEmpty(), query) }
    val visibleIds = visibleProviders.map { it.id }

    Scaffold(
        containerColor = scheme.surface,
        topBar = {
            AppHeader(
                title = "Providers",
                subtitle = "Connect models and choose what powers your chats",
                onBack = onBack,
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.fillMaxHeight().widthIn(max = 960.dp).fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    FilledTonalButton(
                        onClick = {
                            editing = null
                            showDialog = true
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp, bottom = 4.dp),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Add provider")
                    }
                }
                item {
                    OutlinedTextField(value = query, onValueChange = { query = it },
                        label = { Text("Search providers or models") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = showDisabled, onClick = { showDisabled = !showDisabled }, label = { Text("Show disabled") })
                        TextButton(onClick = { showAllModels = true }) { Text("Search all models") }
                    }
                    Text("Disabled providers stay searchable. Existing chats keep their model.",
                        style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
                items(visibleProviders, key = { it.id }) { provider ->
                    ProviderListCard(
                        provider = provider,
                        active = provider.id == settings.activeProviderId,
                        canMoveUp = visibleIds.indexOf(provider.id) > 0,
                        canMoveDown = visibleIds.indexOf(provider.id) < visibleIds.lastIndex,
                        onEnabledChange = { enabled -> scope.launch { container.providers.setEnabled(provider.id, enabled) } },
                        onMove = { direction -> scope.launch { container.providers.move(provider.id, direction, visibleIds) } },
                        onSelect = {
                            scope.launch {
                                container.providers.setEnabled(provider.id, true)
                                val selectedModel = settings.activeModel.takeIf { model ->
                                    catalogs[provider.id].orEmpty().any { it.id == model }
                                }
                                container.settings.setActiveSelection(provider.id, selectedModel)
                                // Adapt the stored thinking tier to the new model.
                                com.androidharness.app.agent.ThinkingSpecs.clampStoredLevel(
                                    container.settings,
                                    selectedModel ?: provider.model,
                                    com.androidharness.app.llm.ModelsDev.providerKeyFor(provider.baseUrl),
                                )
                            }
                        },
                        onEdit = {
                            editing = provider
                            showDialog = true
                        },
                        onDelete = {
                            scope.launch { container.providers.delete(provider.id) }
                        },
                        onBrowseModels = { showModelsFor = provider.id },
                        catalogSize = catalogs[provider.id]?.size,
                    )
                }
                if (visibleProviders.isEmpty()) {
                    item {
                        Surface(
                            color = scheme.surfaceContainerLow,
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
                            ) {
                                ProviderMark(size = 48.dp)
                                Text(
                                    if (providers.isEmpty()) "No providers yet" else "No matching providers",
                                    style = MaterialTheme.typography.titleSmallEmphasized,
                                    modifier = Modifier.padding(top = 12.dp),
                                )
                                Text(
                                    if (providers.isEmpty()) "Add a provider, then choose its model." else "Try a different search or show disabled providers.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDialog) {
        ProviderSheet(
            existing = editing,
            existingKey = editing?.let { container.providers.apiKey(it.id) },
            onDismiss = { showDialog = false },
            onSave = { name, type, baseUrl, model, apiKey ->
                scope.launch {
                    val existingConfig = editing
                    if (existingConfig == null) {
                        val created = container.providers.add(name, type, baseUrl, model, apiKey)
                        if (settings.activeProviderId == null) {
                            container.settings.setActiveProvider(created.id)
                        }
                    } else {
                        container.providers.update(
                            existingConfig.copy(
                                name = name,
                                type = type,
                                baseUrl = baseUrl,
                                model = model,
                            ),
                            apiKey,
                        )
                    }
                    showDialog = false
                }
            },
        )
    }

    if (showModelsFor != null || showAllModels) {
        ModelPickerSheet(
            providers = providers,
            activeProviderId = settings.activeProviderId,
            activeModel = settings.activeModel,
            catalogs = catalogs,
            browseProviderId = showModelsFor,
            searchAllProvidersInitially = showAllModels,
            onEnableProvider = { id -> scope.launch { container.providers.setEnabled(id, true) } },
            onDismiss = { showModelsFor = null; showAllModels = false },
            onSelect = { providerId, model ->
                scope.launch {
                    container.settings.setActiveSelection(providerId, model)
                    val provider = providers.firstOrNull { it.id == providerId }
                    if (provider != null) {
                        com.androidharness.app.agent.ThinkingSpecs.clampStoredLevel(
                            container.settings,
                            model?.takeIf { it.isNotBlank() } ?: provider.model,
                            com.androidharness.app.llm.ModelsDev.providerKeyFor(provider.baseUrl),
                        )
                    }
                }
            },
            onRefreshCatalog = { providerId ->
                val provider = providers.firstOrNull { it.id == providerId }
                when {
                    provider == null -> "Unknown provider"
                    else -> when (
                        val result = com.androidharness.app.llm.ModelCatalog.listModels(
                            provider,
                            provider.effectiveApiKey(container.providers.apiKey(providerId)),
                        )
                    ) {
                        is com.androidharness.app.llm.ModelCatalog.Result.Models -> {
                            container.providers.saveCatalog(providerId, result.models)
                            null
                        }
                        is com.androidharness.app.llm.ModelCatalog.Result.Failed -> result.message
                    }
                }
            },
            onAddCustomModel = { providerId, model, reasoning ->
                scope.launch { container.providers.addCustomModel(providerId, model, reasoning) }
            },
            onDeleteCustomModel = { providerId, model ->
                scope.launch { container.providers.removeCustomModel(providerId, model) }
            },
            onManageProviders = { /* already here */ },
        )
    }
}

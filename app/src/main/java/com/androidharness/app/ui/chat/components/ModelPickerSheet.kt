package com.androidharness.app.ui.chat.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.data.ModelShortcuts
import com.androidharness.app.data.ModelShortcut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.agent.ThinkingLevel
import com.androidharness.app.llm.ProviderConfig
import com.androidharness.app.llm.ModelEntry
import com.androidharness.app.llm.ModelsDev
import com.androidharness.app.llm.reasoningCapable
import com.androidharness.app.ui.common.ProviderMark
import com.androidharness.app.ui.theme.HarnessMono
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import com.androidharness.app.llm.ProviderModelSearch
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.imePadding

/** Current-provider browsing plus an explicit search across connected provider catalogs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(
    providers: List<ProviderConfig>,
    activeProviderId: String?,
    activeModel: String?,
    catalogs: Map<String, List<ModelEntry>>,
    onDismiss: () -> Unit,
    onSelect: (providerId: String, model: String?) -> Unit,
    onRefreshCatalog: suspend (providerId: String) -> String?,
    onManageProviders: () -> Unit,
    /** When set (Providers screen "browse"), list this provider instead of the active one. */
    browseProviderId: String? = null,
    onAddCustomModel: (providerId: String, model: String, reasoning: Boolean?) -> Unit = { _, _, _ -> },
    onDeleteCustomModel: (providerId: String, model: String) -> Unit = { _, _ -> },
    /** Scope belongs to the conversation picker, not provider browsing or automation editors. */
    sameModelAcrossChats: Boolean? = null,
    onModelScopeChange: (Boolean) -> Unit = {},
    modelScopeEnabled: Boolean = true,
    modelShortcuts: ModelShortcuts? = null,
    searchAllProvidersInitially: Boolean = false,
    onEnableProvider: (String) -> Unit = {},
    fallbackSummary: String? = null,
    fallbackEditEnabled: Boolean = true,
    onEditFallbacks: () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var allProviders by rememberSaveable { mutableStateOf(searchAllProvidersInitially) }
    val context = LocalContext.current
    val shortcuts = remember(context, modelShortcuts) { modelShortcuts ?: ModelShortcuts.get(context) }
    val saved by shortcuts.state.collectAsStateWithLifecycle()
    var collection by remember { mutableStateOf("All") }
    fun select(providerId: String, model: String?) {
        if (!modelScopeEnabled) return
        val id = model ?: providers.firstOrNull { it.id == providerId }?.model
        if (providers.firstOrNull { it.id == providerId }?.enabled == false) onEnableProvider(providerId)
        onSelect(providerId, model)
        if (!id.isNullOrBlank()) shortcuts.selected(ModelShortcut(providerId, id))
    }
    var thinkOnly by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }
    var refreshError by remember { mutableStateOf<String?>(null) }
    var showAddCustomDialog by remember { mutableStateOf(false) }

    val listedId = browseProviderId ?: activeProviderId
    val listedProvider = providers.firstOrNull { it.id == listedId }
    val chatGptPlan = com.androidharness.app.chatgpt.ChatGptProtocol.isProvider(listedId)
    val activeProvider = providers.firstOrNull { it.id == activeProviderId }
    val effective = activeModel?.takeIf { it.isNotBlank() } ?: activeProvider?.model
    val listedModel = if (browseProviderId == null) effective else listedProvider?.model
    val devKey = ModelsDev.providerKeyFor(listedProvider?.baseUrl)
    val listedCatalog = remember(listedProvider?.id, catalogs) {
        listedProvider?.let { catalogs[it.id].orEmpty() }.orEmpty()
    }
    val normalizedQuery = remember(query) { query.trim().lowercase() }
    val availableRows = remember(listedProvider, listedCatalog, chatGptPlan) {
        val provider = listedProvider ?: return@remember emptyList()
        if (chatGptPlan) listedCatalog.sortedWith(newestChatGptFirst) else buildList {
            add(listedCatalog.firstOrNull { it.id == provider.model }
                ?: ModelEntry(provider.model, reasoning = null, contextTokens = null))
            addAll(listedCatalog.filter { it.id != provider.model })
        }.distinctBy { it.id }.filter { it.id.isNotBlank() }
    }
    val availableIds = availableRows.map { it.id }.toSet()
    val favouriteIds = saved.favourites.filter { it.providerId == listedId }.map { it.modelId }
    val recentIds = saved.recent.filter { it.providerId == listedId }.map { it.modelId }.take(10)
    val visibleRows = remember(availableRows, favouriteIds, recentIds, collection, normalizedQuery, thinkOnly) {
        val rows = when (collection) {
            "Favourites" -> favouriteIds.map { id -> availableRows.firstOrNull { it.id == id }
                ?: ModelEntry(id, reasoning = null, contextTokens = null) }
            "Recent" -> recentIds.map { id -> availableRows.firstOrNull { it.id == id }
                ?: ModelEntry(id, reasoning = null, contextTokens = null) }
            else -> availableRows.sortedBy { if (it.id in favouriteIds) 0 else 1 }
        }
        rows.filter { normalizedQuery.isBlank() || it.id.lowercase().contains(normalizedQuery)
            || it.displayName?.lowercase()?.contains(normalizedQuery) == true }
            .filter { !thinkOnly || (it.reasoning ?: reasoningCapable(it.id)) }
    }

    val unifiedRows = remember(allProviders, providers, catalogs, query, collection, saved, thinkOnly) {
        if (!allProviders) emptyList() else ProviderModelSearch.results(providers, catalogs, query, collection, saved.favourites, saved.recent, thinkOnly)
    }
    suspend fun refreshProviders(ids: List<String>) {
        if (isRefreshing || ids.isEmpty()) return
        isRefreshing = true
        refreshError = null
        try {
            val limit = Semaphore(3)
            val failures = supervisorScope {
                ids.map { id -> async {
                    limit.withPermit {
                        val message = try { onRefreshCatalog(id) }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { "Could not refresh" }
                        message?.let { "$it · ${providers.firstOrNull { it.id == id }?.name ?: "Provider"}" }
                    }
                } }.awaitAll().filterNotNull()
            }
            refreshError = failures.firstOrNull()?.let {
                if (failures.size > 1) "${failures.size} providers could not refresh. $it" else it
            }
        } finally { isRefreshing = false }
    }
    LaunchedEffect(listedProvider?.id, allProviders) {
        if (allProviders) {
            refreshProviders(providers.filter { it.enabled && catalogs[it.id].isNullOrEmpty() }.map { it.id })
        } else if (listedProvider != null &&
            (chatGptPlan || listedProvider.id == com.androidharness.app.llm.HarnessProvider.ID || listedCatalog.isEmpty())) {
            refreshProviders(listOf(listedProvider.id))
        }
    }

    // Opens fully expanded: half-expanded sheets trap bottom rows behind the
    // drag-to-dismiss gesture, which read as "touch not responding".
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LazyColumn(
            Modifier
                .fillMaxWidth()
                // Keep the sheet's expanded anchor stable as searches change its content height.
                .height((LocalConfiguration.current.screenHeightDp * .9f).dp)
                .padding(horizontal = 16.dp)
                .navigationBarsPadding().imePadding(),
        ) {
            item(key = "picker-controls") {
                Column(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Choose model", style = MaterialTheme.typography.titleMediumEmphasized)
                            Text(
                                if (allProviders) "Find a model across your providers" else if (chatGptPlan) "Choose a model available with your ChatGPT plan" else "Search, filter, or enter any model ID",
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Filled.Close, contentDescription = "Close")
                        }
                    }

                    fallbackSummary?.let { summary ->
                        TextButton(onClick = onEditFallbacks, enabled = modelScopeEnabled && fallbackEditEnabled, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) {
                                Text("Fallbacks", style = MaterialTheme.typography.labelLarge)
                                Text(summary, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                            }
                        }
                    }
                    sameModelAcrossChats?.let { shared ->
                        Surface(color = scheme.surfaceContainerHigh, shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
                                .toggleable(value = shared, enabled = modelScopeEnabled, role = Role.Switch,
                                    onValueChange = onModelScopeChange)
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text("Use the same model in every chat", style = MaterialTheme.typography.bodyMedium)
                                    Text(if (shared) "Changes apply across all chats." else "Each chat remembers its own model.",
                                        style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                                }
                                Switch(checked = shared, onCheckedChange = null, enabled = modelScopeEnabled)
                            }
                        }
                    }

                    // Current selection + prominent jump to provider management.
                    if (activeProvider != null && browseProviderId == null) {
                        Surface(
                            color = scheme.surfaceContainerHigh,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 12.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(12.dp),
                            ) {
                                ProviderMark(size = 42.dp)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        activeProvider.name,
                                        style = MaterialTheme.typography.titleSmallEmphasized,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        effective.orEmpty(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = scheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                OutlinedButton(onClick = { onDismiss(); onManageProviders() }) {
                                    Text("Switch")
                                }
                            }
                        }
                    }

                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !allProviders, onClick = { allProviders = false }, label = { Text("Current provider") })
                        FilterChip(selected = allProviders, onClick = { allProviders = true }, label = { Text("All providers") })
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("All", "Favourites", "Recent").forEach { label ->
                            FilterChip(selected = collection == label, onClick = { collection = label },
                                label = { Text(label) })
                        }
                    }
                    if (!allProviders && collection == "Recent" && recentIds.isNotEmpty()) TextButton(
                        onClick = { listedId?.let(shortcuts::clearRecent) }) { Text("Clear recent") }

                    // Thinking tiers live in the header badge, not here: the picker is
                    // about WHICH model runs; the global ladder adapts to it via
                    // ThinkingSpecs.clampStoredLevel on every switch.

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text(if (allProviders) "Search models or providers" else "Search models") },
                            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        FilterChip(
                            selected = thinkOnly,
                            onClick = { thinkOnly = !thinkOnly },
                            label = { Text("Thinking only", style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        if (!allProviders && !chatGptPlan && listedProvider?.let { !com.androidharness.app.local.LocalModelCatalog.isLocal(it.id) } == true) AssistChip(
                            onClick = { showAddCustomDialog = true },
                            label = { Text("Custom model", style = MaterialTheme.typography.labelSmall) },
                            leadingIcon = { Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        if (allProviders || listedProvider != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (isRefreshing) "reloading…" else "${if (allProviders) unifiedRows.size else visibleRows.size} ${if ((if (allProviders) unifiedRows.size else visibleRows.size) == 1) "model" else "models"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                            IconButton(
                                onClick = {
                                    if (!isRefreshing) {
                                        scope.launch {
                                            refreshProviders(if (allProviders) providers.filter { it.enabled }.map { it.id }
                                                else listOfNotNull(listedProvider?.id))
                                        }
                                    }
                                },
                            ) {
                                if (isRefreshing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    Icon(
                                        Icons.Outlined.Refresh,
                                        contentDescription = "Refresh models",
                                        tint = scheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                            }
                        }
                    }
                    if (refreshError != null) {
                        Text(
                            refreshError!!.take(80),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.error,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }

                    if (!allProviders && chatGptPlan) {
                        Text("Using ChatGPT plan", style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 8.dp))
                        com.androidharness.app.ui.common.ChatGptUsageLimits(compact = true)
                    }
                }
            }
            // Controls and models share one scroll area so landscape and large text remain reachable.
            val provider = listedProvider
            if (allProviders) {
                item(key = "all-provider-search-note") {
                    Text("Search also finds disabled providers.",
                        style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp))
                }
                if (unifiedRows.isEmpty()) item(key = "empty-unified-models") {
                    Text(if (isRefreshing) "Loading provider catalogs…" else "No models match. Try another search or refresh catalogs.",
                        modifier = Modifier.padding(vertical = 20.dp), color = scheme.onSurfaceVariant)
                }
                items(unifiedRows.size, key = { index ->
                    val result = unifiedRows[index]
                    "all:${result.provider.id.length}:${result.provider.id}:${result.model.id}"
                }) { index ->
                    val result = unifiedRows[index]
                    val entry = result.model
                    val owner = result.provider
                    ModelRow(id = entry.displayName ?: entry.id,
                        providerLabel = owner.name,
                        providerDisabled = !owner.enabled,
                        thinking = entry.reasoning ?: reasoningCapable(entry.id), known = entry.reasoning != null,
                        selected = owner.id == activeProviderId && entry.id == effective,
                        isCustom = entry.custom,
                        ctx = ctxLabel(entry.contextTokens ?: ModelsDev.entry(ModelsDev.providerKeyFor(owner.baseUrl), entry.id)?.contextTokens),
                        note = if (!result.available) "Unavailable · refresh or choose another model" else entry.note,
                        enabled = modelScopeEnabled && result.available,
                        favourite = result.shortcut in saved.favourites,
                        onFavourite = { shortcuts.toggle(result.shortcut) },
                        onClick = { select(owner.id, entry.id); onDismiss() },
                        onDelete = if (entry.custom) ({ onDeleteCustomModel(owner.id, entry.id) }) else null)
                }
            } else if (provider == null) {
                item {
                    Text(
                        "Add a provider first. The model list follows from it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            } else {
                if (isRefreshing && listedCatalog.isEmpty()) {
                    item(key = "reloading") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                "Reloading models…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                if (collection == "All" && !chatGptPlan && !com.androidharness.app.local.LocalModelCatalog.isLocal(provider.id) && query.isNotBlank() && availableRows.none { it.id.equals(query.trim(), ignoreCase = true) }) {
                    item(key = "inline-custom-${query.trim()}") {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = scheme.primaryContainer.copy(alpha = 0.6f),
                            border = BorderStroke(1.dp, scheme.primary.copy(alpha = 0.3f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable(enabled = modelScopeEnabled) {
                                    val customName = query.trim()
                                    onAddCustomModel(provider.id, customName, null)
                                    select(provider.id, customName)
                                    onDismiss()
                                },
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            ) {
                                Icon(
                                    Icons.Outlined.Add,
                                    contentDescription = null,
                                    tint = scheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "Use custom model",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = scheme.primary,
                                    )
                                    Text(
                                        query.trim(),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontFamily = HarnessMono,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }

                if (visibleRows.isEmpty() && !isRefreshing) item(key = "empty-models") {
                    Text(when {
                        query.isNotBlank() || thinkOnly -> "No models match these filters."
                        collection == "Favourites" -> "Tap a star beside a model to keep it here."
                        collection == "Recent" -> "Models you choose will appear here."
                        else -> "No models available. Try refreshing."
                    }, modifier = Modifier.padding(vertical = 20.dp),
                        style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                }
                items(visibleRows.size, key = { "${provider.id}-${visibleRows[it].id}" }) { index ->
                    val entry = visibleRows[index]
                    val isSelected = provider.id == (browseProviderId ?: activeProviderId) &&
                        entry.id == (if (browseProviderId == null) effective else listedModel)
                    ModelRow(
                        id = entry.displayName ?: entry.id,
                        thinking = entry.reasoning ?: reasoningCapable(entry.id),
                        known = entry.reasoning != null,
                        selected = isSelected,
                        isCustom = entry.custom,
                        ctx = ctxLabel(
                            entry.contextTokens
                                ?: ModelsDev.entry(devKey, entry.id)?.contextTokens,
                        ),
                        providerLabel = if (!provider.enabled) provider.name else null,
                        providerDisabled = !provider.enabled,
                        note = if (entry.id !in availableIds) "Unavailable · refresh or choose another model" else entry.note,
                        enabled = modelScopeEnabled && entry.id in availableIds,
                        favourite = entry.id in favouriteIds,
                        onFavourite = { shortcuts.toggle(ModelShortcut(provider.id, entry.id)) },
                        onClick = {
                            select(provider.id, entry.id)
                            onDismiss()
                        },
                        onDelete = if (entry.custom) {
                            { onDeleteCustomModel(provider.id, entry.id) }
                        } else null,
                    )
                }
            }
            item {
                FlowRow(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (!allProviders && !chatGptPlan) TextButton(onClick = { showAddCustomDialog = true }, enabled = listedProvider?.let { !com.androidharness.app.local.LocalModelCatalog.isLocal(it.id) } == true) {
                        Text("+ Custom model…")
                    }
                    TextButton(onClick = { onDismiss(); onManageProviders() }) {
                        Text("Manage providers…")
                    }
                }
            }
        }
    }

    if (showAddCustomDialog && listedProvider != null) {
        AddCustomModelDialog(
            providerName = listedProvider.name,
            onDismiss = { showAddCustomDialog = false },
            onAdd = { modelId, reasoning ->
                onAddCustomModel(listedProvider.id, modelId, reasoning)
                select(listedProvider.id, modelId)
                onDismiss()
            },
        )
    }
}

@Composable
private fun ModelRow(
    id: String,
    thinking: Boolean,
    providerLabel: String? = null,
    providerDisabled: Boolean = false,
    known: Boolean,
    selected: Boolean,
    ctx: String?,
    isCustom: Boolean = false,
    note: String? = null,
    onClick: () -> Unit,
    onDelete: (() -> Unit)? = null,
    enabled: Boolean = true,
    favourite: Boolean = false,
    onFavourite: () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        color = if (selected) scheme.secondaryContainer else scheme.surface,
        shape = RoundedCornerShape(14.dp),
        border = if (selected) BorderStroke(1.dp, scheme.primary.copy(alpha = 0.28f)) else null,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Column(Modifier.weight(1f).clickable(enabled = enabled, onClick = onClick).heightIn(min = 48.dp),
                verticalArrangement = Arrangement.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        id,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = HarnessMono,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (isCustom) {
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = scheme.tertiaryContainer,
                        ) {
                            Text(
                                "custom",
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onTertiaryContainer,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                providerLabel?.let { Text(it, style = MaterialTheme.typography.labelMedium,
                    color = scheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                if (providerDisabled) Text("Disabled · Enable & use", style = MaterialTheme.typography.labelMedium,
                    color = scheme.primary)
                val sub = listOfNotNull(
                    if (thinking) "thinking" else null,
                    ctx,
                    note,
                ).joinToString(" · ")
                if (sub.isNotEmpty()) {
                    Text(
                        sub,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (thinking) scheme.primary else scheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onFavourite) {
                Icon(if (favourite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    contentDescription = if (favourite) "Remove $id from favourites" else "Favourite $id",
                    tint = if (favourite) scheme.primary else scheme.onSurfaceVariant)
            }
            if (onDelete != null) {
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "Remove custom model",
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Spacer(Modifier.width(4.dp))
            }
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "Selected",
                    tint = scheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun AddCustomModelDialog(
    providerName: String,
    onDismiss: () -> Unit,
    onAdd: (modelId: String, reasoning: Boolean?) -> Unit,
) {
    var modelId by remember { mutableStateOf("") }
    var thinking by remember { mutableStateOf(false) }
    val clean = modelId.trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add custom model") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Add any model ID supported by $providerName.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = modelId,
                    onValueChange = { modelId = it },
                    label = { Text("Model ID") },
                    placeholder = { Text("e.g. meta-llama/llama-3.3-70b") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { thinking = !thinking }
                        .padding(vertical = 4.dp),
                ) {
                    Checkbox(
                        checked = thinking,
                        onCheckedChange = { thinking = it },
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("Reasoning model", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Enables thinking level ladder for this model",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = clean.isNotBlank(),
                onClick = {
                    onAdd(clean, if (thinking) true else null)
                    onDismiss()
                },
            ) {
                Text("Add & Select")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

private val newestChatGptFirst = Comparator<ModelEntry> { left, right ->
    fun version(entry: ModelEntry) = entry.id.lowercase().removePrefix("gpt-")
        .substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    val leftVersion = version(left)
    val rightVersion = version(right)
    for (index in 0 until maxOf(leftVersion.size, rightVersion.size)) {
        val order = rightVersion.getOrElse(index) { 0 }.compareTo(leftVersion.getOrElse(index) { 0 })
        if (order != 0) return@Comparator order
    }
    0
}

/** Compact context-window label for picker rows ("200K ctx", "1M ctx", "1B ctx"). */
private fun ctxLabel(tokens: Long?): String? = when {
    tokens == null || tokens <= 0 -> null
    tokens >= 1_000_000_000 -> "${tokens / 1_000_000_000}B ctx"
    tokens >= 1_000_000 -> "${tokens / 1_000_000}M ctx"
    tokens >= 1_000 -> "${tokens / 1_000}K ctx"
    else -> "$tokens ctx"
}

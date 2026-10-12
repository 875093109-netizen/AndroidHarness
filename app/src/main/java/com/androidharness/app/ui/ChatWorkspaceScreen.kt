package com.androidharness.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.data.useWorkspacePanes
import com.androidharness.app.data.WorkspaceLayoutMode
import com.androidharness.app.ui.chat.ChatScreen
import com.androidharness.app.ui.chat.ChatViewModel
import com.androidharness.app.ui.files.*
import com.androidharness.app.ui.terminal.TerminalScreen

@Composable
fun ChatWorkspaceScreen(
    viewModel: ChatViewModel,
    onOpenDrawer: () -> Unit,
    onOpenFile: (String, Int?) -> Unit,
    onNewChat: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenFiles: () -> Unit = {},
    onOpenSubagent: (String) -> Unit,
    onOpenSettings: () -> Unit = {},
    onNavigateToSession: (String) -> Unit = {},
    searchMessageId: String? = null,
) {
    val container = viewModel.container
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = com.androidharness.app.data.AppSettings())
    val chatState by viewModel.state.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    val localGuard = remember { EditorNavigationGuard() }
    val guard = LocalDesktopNavigationGuard.current ?: localGuard
    val project by viewModel.activeWorkspace.collectAsStateWithLifecycle(initialValue = null)
    var tool by rememberSaveable { mutableStateOf("Files") }
    var file by rememberSaveable { mutableStateOf<String?>(null) }
    var line by rememberSaveable { mutableIntStateOf(0) }
    var panelOpen by rememberSaveable { mutableStateOf(false) }
    var compactOpen by rememberSaveable { mutableStateOf(false) }
    var toolsUsed by rememberSaveable { mutableStateOf(false) }
    var fraction by rememberSaveable { mutableFloatStateOf(0.62f) }
    val savedTools = rememberSaveableStateHolder()
    val inheritedDesktop = LocalDesktopLayout.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = useWorkspacePanes(settings.workspaceLayout, maxWidth.value, LocalDensity.current.fontScale)
        val desktop = settings.workspaceLayout != WorkspaceLayoutMode.PHONE && (wide || inheritedDesktop)
        val embedded = settings.workspaceLayout != WorkspaceLayoutMode.PHONE && (desktop || toolsUsed)
        val visible = settings.workspaceLayout != WorkspaceLayoutMode.PHONE && if (wide) panelOpen else compactOpen
        val showChrome = settings.workspaceLayout != WorkspaceLayoutMode.PHONE && (desktop || toolsUsed)
        LaunchedEffect(desktop) { if (desktop) toolsUsed = true }
        fun showTool(name: String) = guard.navigate {
            focus.clearFocus(force = true)
            file = null
            tool = name
            toolsUsed = true
            panelOpen = true
            compactOpen = !wide
        }
        fun openTerminal() = showTool("Terminal")
        fun closePanel() {
            focus.clearFocus(force = true)
            panelOpen = false
            compactOpen = false
        }
        BackHandler(enabled = visible || file != null) {
            guard.navigate {
                when {
                    file != null -> file = null
                    else -> closePanel()
                }
            }
        }
        CompositionLocalProvider(LocalDesktopLayout provides desktop,
            LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.fillMaxSize().then(if (showChrome) Modifier.statusBarsPadding() else Modifier)) {
            if (showChrome) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer,
                    contentColor = MaterialTheme.colorScheme.onSurface) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { guard.navigate { file = null; onOpenDrawer() } }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Outlined.Menu, "Toggle sidebar", Modifier.size(18.dp))
                    }
                    Icon(Icons.Outlined.Folder, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(project?.name ?: "Workspace", Modifier.weight(1f).padding(start = 8.dp),
                        style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    WorkspaceToolButton("Files", Icons.Outlined.Folder, visible && tool == "Files", desktop) { showTool("Files") }
                    WorkspaceToolButton("Changes", Icons.Outlined.Difference, visible && tool == "Changes", desktop,
                        enabled = chatState.sessionId != null) { showTool("Changes") }
                    WorkspaceToolButton("Terminal", Icons.Outlined.Terminal, visible && tool == "Terminal", desktop) { openTerminal() }
                }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
            Box(Modifier.weight(1f)) {
                WorkspaceSplitLayout(wide, visible, fraction, { fraction = it }, chat = {
                    ChatScreen(viewModel, onOpenDrawer = { guard.navigate { file = null; onOpenDrawer() } },
                        onOpenFile = { path, initialLine ->
                            if (embedded) guard.navigate {
                                focus.clearFocus(force = true)
                                file = path; line = initialLine ?: 0; tool = "Files"
                                toolsUsed = true; panelOpen = true; compactOpen = !wide
                            } else onOpenFile(path, initialLine)
                        },
                        onNewChat = { guard.navigate(onNewChat) },
                        onOpenTerminal = { if (embedded) openTerminal() else onOpenTerminal() },
                        onOpenFiles = { if (embedded) showTool("Files") else onOpenFiles() },
                        onOpenSubagent = { guard.navigate { onOpenSubagent(it) } },
                        onOpenSettings = { guard.navigate(onOpenSettings) },
                        onNavigateToSession = { guard.navigate { onNavigateToSession(it) } },
                        searchMessageId = searchMessageId)
                }, panel = {
                    if (desktop || toolsUsed) {
                        Box(Modifier.fillMaxSize()) {
                                if (file != null) {
                                    CodeEditorScreen(container, file!!, initialLine = line.takeIf { it > 0 },
                                        sessionId = chatState.sessionId, onBack = { file = null }, navigationGuard = guard)
                                } else savedTools.SaveableStateProvider(tool) {
                                    when (tool) {
                                        "Terminal" -> TerminalScreen(container, onBack = { closePanel() }, sessionId = chatState.sessionId)
                                        "Changes" -> chatState.sessionId?.let { sid -> ChangesScreen(container, sid,
                                            onBack = { closePanel() }, onAskAgent = { container.pendingAgentPrompt.value = it; closePanel() }) }
                                        else -> FilesScreen(container, sessionId = chatState.sessionId, onBack = { closePanel() },
                                            onOpenFile = { file = it; line = 0 },
                                            onOpenChanges = chatState.sessionId?.let { { showTool("Changes") } })
                                    }
                                }
                        }
                    }
                })
            }

        }
        }
    }
}

@Composable
private fun WorkspaceToolButton(
    label: String, icon: ImageVector, selected: Boolean, showLabel: Boolean,
    enabled: Boolean = true, onClick: () -> Unit,
) {
    if (showLabel) FilterChip(
        selected = selected, onClick = onClick, enabled = enabled,
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        leadingIcon = { Icon(icon, null, Modifier.size(16.dp)) },
        modifier = Modifier.padding(start = 6.dp),
    ) else IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(40.dp)) {
        Icon(icon, label, Modifier.size(18.dp),
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

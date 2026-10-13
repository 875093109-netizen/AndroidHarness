package com.androidharness.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.androidharness.app.R
import com.androidharness.app.agent.PermissionMode
import com.androidharness.app.data.useWorkspacePanes
import com.androidharness.app.data.WorkspaceLayoutMode
import com.androidharness.app.ui.chat.ChatScreen
import com.androidharness.app.ui.chat.ChatViewModel
import com.androidharness.app.ui.files.*
import com.androidharness.app.ui.github.GitHubWorkspaceDialog
import com.androidharness.app.ui.terminal.TerminalScreen
import kotlinx.coroutines.launch

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
    val project by viewModel.activeWorkspace.collectAsStateWithLifecycle(initialValue = null)
    val focus = LocalFocusManager.current
    val inputFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val localGuard = remember { EditorNavigationGuard() }
    val guard = LocalDesktopNavigationGuard.current ?: localGuard
    var tool by rememberSaveable { mutableStateOf("Files") }
    var file by rememberSaveable { mutableStateOf<String?>(null) }
    var line by rememberSaveable { mutableIntStateOf(0) }
    var panelOpen by rememberSaveable { mutableStateOf(false) }
    var compactOpen by rememberSaveable { mutableStateOf(false) }
    var toolsUsed by rememberSaveable { mutableStateOf(false) }
    var fraction by rememberSaveable { mutableFloatStateOf(0.62f) }
    var githubProject by remember { mutableStateOf<Pair<String, String>?>(null) }
    val savedTools = rememberSaveableStateHolder()
    val keyboardRouter = LocalDesktopKeyboardRouter.current
    val keyboardOwner = remember { Any() }
    val inheritedDesktop = LocalDesktopLayout.current
    val desktopInput = settings.workspaceLayout == WorkspaceLayoutMode.DESKTOP

    githubProject?.let { (id, name) ->
        GitHubWorkspaceDialog(container, id, name, onOpenSettings = {
            container.pendingSettingsScroll.value = "github"
            guard.navigate(onOpenSettings)
        }, onDismiss = { githubProject = null })
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val windowWidth = maxWidth.value
        val fontScale = LocalDensity.current.fontScale
        val wide = useWorkspacePanes(settings.workspaceLayout, maxWidth.value, fontScale)
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
        fun closePanel() {
            focus.clearFocus(force = true)
            panelOpen = false
            compactOpen = false
        }
        fun openGitHub() { project?.let { githubProject = it.id to it.name } }
        fun focusInput() {
            if (!wide) closePanel()
            scope.launch { withFrameNanos { }; inputFocus.requestFocus() }
        }
        BackHandler(enabled = visible || file != null) {
            guard.navigate { if (file != null) file = null else closePanel() }
        }
        fun handleKey(event: androidx.compose.ui.input.key.KeyEvent): Boolean {
                    return if (!desktopInput) false else {
                        val action: (() -> Unit)? = when {
                            event.isCtrlPressed && !event.isAltPressed && event.key == Key.L -> ::focusInput
                            event.isCtrlPressed && event.isAltPressed && event.key == Key.F -> { { showTool("Files") } }
                            event.isCtrlPressed && event.isAltPressed && event.key == Key.T -> { { showTool("Terminal") } }
                            event.isCtrlPressed && event.isAltPressed && event.key == Key.G -> ::openGitHub
                            event.key == Key.Escape && visible && githubProject == null -> { { guard.navigate(::closePanel) } }
                            else -> null
                        }
                        if (action == null) false else { if (event.type == KeyEventType.KeyUp) action(); true }
                    }
        }
        SideEffect { keyboardRouter?.register(keyboardOwner, ::handleKey) }
        DisposableEffect(keyboardRouter) { onDispose { keyboardRouter?.unregister(keyboardOwner) } }
        val controls: @Composable () -> Unit = {
            WorkspaceToolButton("Files", Icons.Outlined.Folder, visible && tool == "Files", desktop) { showTool("Files") }
            if (desktop) {
                FilterChip(selected = githubProject != null, onClick = ::openGitHub, enabled = project != null,
                    label = { Text("GitHub", style = MaterialTheme.typography.labelMedium) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_github), null, Modifier.size(16.dp)) },
                    modifier = Modifier.padding(start = 6.dp).pointerHoverIcon(PointerIcon.Hand))
            }
            WorkspaceToolButton("Changes", Icons.Outlined.Difference, visible && tool == "Changes", desktop,
                enabled = chatState.sessionId != null) { showTool("Changes") }
            WorkspaceToolButton("Terminal", Icons.Outlined.Terminal, visible && tool == "Terminal", desktop) { showTool("Terminal") }
            if (desktop) WorkspaceAccessMode(chatState.permissionMode, viewModel::setPermissionMode)
        }
        CompositionLocalProvider(LocalDesktopLayout provides desktop, LocalDesktopInput provides desktopInput,
            LocalComposerFocus provides inputFocus, LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            Column(Modifier.fillMaxSize().then(if (showChrome) Modifier.statusBarsPadding() else Modifier)
                .onPreviewKeyEvent { event -> if (keyboardRouter == null) handleKey(event) else false }) {
                if (showChrome) {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainer, contentColor = MaterialTheme.colorScheme.onSurface) {
                        Column {
                            val inline = windowWidth >= 1000f * fontScale
                            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = onOpenDrawer,
                                    modifier = Modifier.size(40.dp).pointerHoverIcon(PointerIcon.Hand)) {
                                    Icon(Icons.Outlined.Menu, "Toggle sidebar", Modifier.size(18.dp))
                                }
                                Icon(Icons.Outlined.Folder, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(project?.name ?: "Workspace", Modifier.weight(1f).padding(start = 8.dp),
                                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (inline) controls()
                            }
                            if (!inline) FlowRow(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                                horizontalArrangement = Arrangement.End) { controls() }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
                Box(Modifier.weight(1f)) {
                    WorkspaceSplitLayout(wide, visible, fraction, { fraction = it }, chat = {
                        ChatScreen(viewModel, onOpenDrawer = onOpenDrawer,
                            onOpenFile = { path, initialLine ->
                                if (embedded) guard.navigate {
                                    focus.clearFocus(force = true)
                                    file = path; line = initialLine ?: 0; tool = "Files"
                                    toolsUsed = true; panelOpen = true; compactOpen = !wide
                                } else onOpenFile(path, initialLine)
                            }, onNewChat = { guard.navigate(onNewChat) },
                            onOpenTerminal = { if (embedded) showTool("Terminal") else onOpenTerminal() },
                            onOpenFiles = { if (embedded) showTool("Files") else onOpenFiles() },
                            onOpenSubagent = { guard.navigate { onOpenSubagent(it) } },
                            onOpenSettings = { guard.navigate(onOpenSettings) },
                            onNavigateToSession = { guard.navigate { onNavigateToSession(it) } }, searchMessageId = searchMessageId)
                    }, panel = {
                        if (desktop || toolsUsed) Box(Modifier.fillMaxSize()) {
                            if (file != null) CodeEditorScreen(container, file!!, initialLine = line.takeIf { it > 0 },
                                sessionId = chatState.sessionId, onBack = { file = null }, navigationGuard = guard,
                                onAskAgent = { sourceId, prompt ->
                                    scope.launch {
                                        try {
                                            val sid = chatState.sessionId ?: sourceId?.let { container.sessions.createProjectSession(it) }
                                                ?: error("Workspace unavailable")
                                            container.pendingCodeDraft.value = com.androidharness.app.ui.files.CodeChatDraft(sid, prompt)
                                            if (chatState.sessionId == null) onNavigateToSession(sid)
                                            else { file = null; if (!wide) compactOpen = false }
                                        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                        catch (_: Exception) {
                                            android.widget.Toast.makeText(container.appContext,
                                                "Could not open chat. Choose an available workspace and retry.", android.widget.Toast.LENGTH_LONG).show()
                                        }
                                    }
                                })
                            else savedTools.SaveableStateProvider(tool) {
                                when (tool) {
                                    "Terminal" -> TerminalScreen(container, onBack = ::closePanel, sessionId = chatState.sessionId)
                                    "Changes" -> chatState.sessionId?.let { sid -> ChangesScreen(container, sid,
                                        onBack = ::closePanel, onAskAgent = { container.pendingAgentPrompt.value = it; closePanel() }) }
                                    else -> FilesScreen(container, sessionId = chatState.sessionId, onBack = ::closePanel,
                                        onOpenFile = { file = it; line = 0 },
                                        onOpenChanges = chatState.sessionId?.let { { showTool("Changes") } })
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
private fun WorkspaceAccessMode(permission: PermissionMode, onSelect: (PermissionMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilterChip(selected = expanded, onClick = { expanded = true },
            label = { Text(permission.label, style = MaterialTheme.typography.labelMedium) },
            leadingIcon = { Icon(Icons.Outlined.Shield, null, Modifier.size(16.dp)) },
            trailingIcon = { Icon(Icons.Outlined.KeyboardArrowDown, null, Modifier.size(16.dp)) },
            modifier = Modifier.padding(start = 6.dp).pointerHoverIcon(PointerIcon.Hand)
                .semantics { contentDescription = "Access mode: ${permission.label}" })
        DropdownMenu(expanded, { expanded = false }) {
            PermissionMode.entries.forEach { option ->
                DropdownMenuItem(text = { Text(option.label) }, onClick = { expanded = false; onSelect(option) },
                    trailingIcon = { if (permission == option) Icon(Icons.Outlined.Check, "Selected", Modifier.size(16.dp)) })
            }
        }
    }
}

@Composable
private fun WorkspaceToolButton(label: String, icon: ImageVector, selected: Boolean, showLabel: Boolean,
    enabled: Boolean = true, onClick: () -> Unit) {
    if (showLabel) FilterChip(selected, onClick, enabled = enabled,
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        leadingIcon = { Icon(icon, null, Modifier.size(16.dp)) },
        modifier = Modifier.padding(start = 6.dp).pointerHoverIcon(PointerIcon.Hand))
    else IconButton(onClick, enabled = enabled, modifier = Modifier.size(40.dp).pointerHoverIcon(PointerIcon.Hand)) {
        Icon(icon, label, Modifier.size(18.dp),
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

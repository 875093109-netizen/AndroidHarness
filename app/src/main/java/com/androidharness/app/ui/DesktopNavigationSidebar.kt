package com.androidharness.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidharness.app.data.db.ProjectEntity
import com.androidharness.app.data.db.SessionEntity
import com.androidharness.app.ui.common.HarnessMark

@Composable
internal fun DesktopNavigationSidebar(
    projects: List<ProjectEntity>, sessions: List<SessionEntity>, selectedSession: String?,
    selectedProject: String?, running: Set<String>, currentRoute: String?,
    onNewChat: () -> Unit, onChat: (String) -> Unit, onProjectChat: (ProjectEntity) -> Unit,
    onProjects: () -> Unit, onAutomation: () -> Unit, onSettings: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var query by rememberSaveable { mutableStateOf("") }
    var collapsed by rememberSaveable { mutableStateOf(emptyList<String>()) }
    Surface(color = scheme.surfaceContainerLow, contentColor = scheme.onSurface, modifier = Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(scheme.surfaceContainerLow)
        .semantics { contentDescription = "Project and chat sidebar" }) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HarnessMark(size = 24.dp)
            Text("AndroidHarness", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
        DesktopSidebarAction("New chat", Icons.Outlined.Add, onClick = onNewChat)
        OutlinedTextField(query, { query = it }, singleLine = true,
            placeholder = { Text("Search chats", style = MaterialTheme.typography.bodySmall) },
            leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(16.dp)) },
            textStyle = MaterialTheme.typography.bodySmall,
            shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 12.dp)) {
            item { Text("PROJECTS", Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant) }
            projects.forEach { project ->
                val chats = sessions.filter { it.projectId == project.id && (query.isBlank() || it.title.contains(query, true)) }
                if (query.isNotBlank() && chats.isEmpty() && !project.name.contains(query, true)) return@forEach
                item(key = "project:${project.id}") {
                    Row(Modifier.fillMaxWidth().clickable {
                        collapsed = if (project.id in collapsed) collapsed - project.id else collapsed + project.id
                    }.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (project.id in collapsed) Icons.Outlined.ChevronRight else Icons.Outlined.KeyboardArrowDown,
                            null, Modifier.size(16.dp), tint = scheme.onSurfaceVariant)
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Outlined.Folder, null, Modifier.size(16.dp), tint = scheme.onSurfaceVariant)
                        Text(project.name, Modifier.weight(1f).padding(start = 8.dp), maxLines = 1,
                            overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (project.id == selectedProject) FontWeight.SemiBold else FontWeight.Normal)
                        IconButton(onClick = { onProjectChat(project) }, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Outlined.Add, "New chat in ${project.name}", Modifier.size(16.dp))
                        }
                    }
                }
                if (project.id !in collapsed || query.isNotBlank()) {
                    items(chats, key = { it.id }) { chat ->
                        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, bottom = 2.dp)
                            .background(if (chat.id == selectedSession) scheme.surfaceContainerHighest else scheme.surfaceContainerLow,
                                RoundedCornerShape(6.dp)).clickable { onChat(chat.id) }
                            .heightIn(min = 36.dp).padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(chat.title, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (chat.id in running) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                        }
                    }
                    if (chats.isEmpty() && query.isBlank()) item {
                        Text("No chats yet", Modifier.padding(start = 50.dp, top = 4.dp, bottom = 12.dp),
                            style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                    }
                }
            }
            val unassigned = sessions.filter { it.projectId == null && (query.isBlank() || it.title.contains(query, true)) }
            if (unassigned.isNotEmpty()) {
                item { Text("RECENT CHATS", Modifier.padding(16.dp), style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant) }
                items(unassigned, key = { it.id }) { chat ->
                    DesktopSidebarAction(chat.title, Icons.Outlined.ChatBubbleOutline, chat.id == selectedSession) { onChat(chat.id) }
                }
            }
        }
        HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f))
        DesktopSidebarAction("Projects", Icons.Outlined.Folder, currentRoute?.startsWith("project") == true, onProjects)
        DesktopSidebarAction("Scheduled tasks", Icons.Outlined.Schedule, currentRoute?.startsWith("automation") == true, onAutomation)
        DesktopSidebarAction("Settings", Icons.Outlined.Settings, currentRoute == "settings", onSettings)
        Spacer(Modifier.height(8.dp))
    }
    }
}

@Composable
private fun DesktopSidebarAction(label: String, icon: ImageVector, selected: Boolean = false, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)
        .background(if (selected) scheme.surfaceContainerHighest else scheme.surfaceContainerLow, RoundedCornerShape(6.dp))
        .clickable(onClick = onClick).heightIn(min = 38.dp).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, Modifier.size(18.dp), tint = scheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

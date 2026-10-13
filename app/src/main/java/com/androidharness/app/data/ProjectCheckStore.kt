package com.androidharness.app.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class ProjectCheckStatus { RUNNING, PASSED, FAILED, INTERRUPTED }

@Serializable
data class ProjectCheckResult(val name: String, val status: ProjectCheckStatus, val updatedAt: Long)

/** Records named Build & Test checks even when their screen has been closed. */
class ProjectCheckStore(context: Context, private val terminal: TerminalManager) {
    private val prefs = context.getSharedPreferences("project_checks", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private val _results = MutableStateFlow(prefs.all.mapNotNull { (id, saved) ->
        runCatching { json.decodeFromString<ProjectCheckResult>(saved as String) }.getOrNull()?.let {
            id to if (it.status == ProjectCheckStatus.RUNNING) it.copy(status = ProjectCheckStatus.INTERRUPTED) else it
        }
    }.toMap())
    val results: StateFlow<Map<String, ProjectCheckResult>> = _results
    private data class Run(val projectId: String, val name: String, val command: String)
    private val pending = MutableStateFlow<Run?>(null)

    init {
        _results.value.forEach { (id, result) -> prefs.edit().putString(id, json.encodeToString(result)).apply() }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            combine(terminal.state, pending) { _, run -> run }.collect { run ->
                if (run == null) return@collect
                val state = terminal.state.value
                val status = when {
                    state.lastCommand != run.command -> ProjectCheckStatus.INTERRUPTED
                    state.busy -> return@collect
                    state.lastExitCode == 0 -> ProjectCheckStatus.PASSED
                    state.lastExitCode == -1 -> ProjectCheckStatus.INTERRUPTED
                    state.lastExitCode != null -> ProjectCheckStatus.FAILED
                    else -> ProjectCheckStatus.INTERRUPTED
                }
                synchronized(lock) {
                    if (pending.value === run) {
                        save(run.projectId, ProjectCheckResult(run.name, status, System.currentTimeMillis()))
                        pending.value = null
                    }
                }
            }
        }
    }

    /** Call immediately after send, so a rejected command cannot replace another project's result. */
    fun track(projectId: String, name: String, command: String) = synchronized(lock) {
        val expected = command.trimEnd()
        if (terminal.state.value.lastCommand != expected) return@synchronized
        pending.value?.let { previous ->
            save(previous.projectId, ProjectCheckResult(previous.name, ProjectCheckStatus.INTERRUPTED, System.currentTimeMillis()))
        }
        save(projectId, ProjectCheckResult(name.take(128), ProjectCheckStatus.RUNNING, System.currentTimeMillis()))
        pending.value = Run(projectId, name.take(128), expected)
    }

    private fun save(id: String, result: ProjectCheckResult) {
        _results.value = _results.value + (id to result)
        prefs.edit().putString(id, json.encodeToString(result)).apply()
    }
}

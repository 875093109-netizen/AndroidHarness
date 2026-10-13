package com.androidharness.app.ui.projects

import com.androidharness.app.AppContainer
import com.androidharness.app.github.gitCommand
import com.androidharness.app.github.GitCommandResult
import com.androidharness.app.github.parseGitChanges
import com.androidharness.app.workspace.WorkspaceFs
import kotlinx.coroutines.CancellationException

internal data class ProjectGitOverview(val branch: String? = null, val changedFiles: Int? = null,
    val note: String? = null)

/** Repository status does not require a GitHub account, origin, or a publishable branch. */
internal suspend fun loadProjectGitOverview(container: AppContainer, fs: WorkspaceFs): ProjectGitOverview {
    val remote = fs is com.androidharness.app.workspace.SshFs
    if (!remote && fs.shellRoot == null) return ProjectGitOverview(note = "Git needs a workspace with shell access")
    if (!remote && !container.linuxEnv.gitToolsReady) return ProjectGitOverview(note = "Install Git tools in Terminal & device")
    return readProjectGitOverview { args ->
        val result = container.shellRouter.runWorkspace(gitCommand(args), fs, timeoutMs = 10_000, maxOutput = 256_000)
        check(!result.timedOut && result.rawOutput.length < 256_000) { "Repository check incomplete" }
        GitCommandResult(result.exitCode, result.rawOutput, result.rawStderr)
    }
}

internal suspend fun readProjectGitOverview(execute: suspend (List<String>) -> GitCommandResult): ProjectGitOverview =
    try {
        suspend fun run(vararg args: String) = execute(args.toList())
        val repository = run("rev-parse", "--is-inside-work-tree")
        if (repository.code != 0 || repository.out.trim() != "true") {
            ProjectGitOverview(note = if (repository.code == 0 || "not a git repository" in repository.err.lowercase())
                "No Git repository" else "Repository status unavailable · Retry")
        } else {
            val branch = run("symbolic-ref", "--quiet", "--short", "HEAD")
            val label = if (branch.code == 0) branch.out.trim() else if (branch.code == 1) "Detached HEAD" else null
            val changes = run("status", "--porcelain=v1", "-z", "--untracked-files=all", "--", ".")
            if (changes.code != 0) {
                ProjectGitOverview(branch = label, note = "Changes unavailable · Retry")
            } else ProjectGitOverview(branch = label, changedFiles = parseGitChanges(changes.out).size,
                note = if (label == null) "Branch unavailable · Retry" else null)
        }
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { ProjectGitOverview(note = "Repository status unavailable · Retry") }

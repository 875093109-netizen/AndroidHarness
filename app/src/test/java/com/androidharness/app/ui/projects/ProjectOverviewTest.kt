package com.androidharness.app.ui.projects

import com.androidharness.app.github.GitCommandResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ProjectOverviewTest {
    private fun git(root: File, args: List<String>): GitCommandResult {
        val process = ProcessBuilder(listOf("git") + args).directory(root).start()
        val out = process.inputStream.bufferedReader().readText()
        val err = process.errorStream.bufferedReader().readText()
        return GitCommandResult(process.waitFor(), out, err)
    }

    @Test fun localRepositoryWithoutOriginShowsItsBranchAndExcludesAppHistory(): Unit = runBlocking {
        val root = Files.createTempDirectory("project-overview").toFile()
        try {
            assertEquals(0, git(root, listOf("init", "-b", "project-main")).code)
            File(root, "first file.txt").writeText("first")
            File(root, "second.txt").writeText("second")
            File(root, ".harness/cache").apply { parentFile.mkdirs(); writeText("private") }
            val dirty = readProjectGitOverview { git(root, it) }
            assertEquals("project-main", dirty.branch)
            assertEquals(2, dirty.changedFiles)
            assertNull(dirty.note)
            git(root, listOf("config", "user.email", "fixture@example.test"))
            git(root, listOf("config", "user.name", "Fixture"))
            git(root, listOf("add", "first file.txt", "second.txt"))
            assertEquals(0, git(root, listOf("commit", "-m", "Fixture")).code)
            val clean = readProjectGitOverview { git(root, it) }
            assertEquals(0, clean.changedFiles)
            assertEquals(0, git(root, listOf("checkout", "--detach")).code)
            assertEquals("Detached HEAD", readProjectGitOverview { git(root, it) }.branch)
        } finally { root.deleteRecursively() }
    }

    @Test fun unavailableStatusIsNeverReportedAsACleanTree(): Unit = runBlocking {
        val root = Files.createTempDirectory("project-no-git").toFile()
        try {
            val missing = readProjectGitOverview { git(root, it) }
            assertNull(missing.branch); assertNull(missing.changedFiles); assertEquals("No Git repository", missing.note)
            val failed = readProjectGitOverview { error("Git timed out") }
            assertNull(failed.changedFiles); assertNotNull(failed.note)
            val denied = readProjectGitOverview { GitCommandResult(126, "", "Permission denied") }
            assertNull(denied.changedFiles); assertEquals("Repository status unavailable · Retry", denied.note)
        } finally { root.deleteRecursively() }
    }
}

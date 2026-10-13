package com.androidharness.app.ui.projects

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.data.ProjectCheckStatus
import com.androidharness.app.data.ProjectCheckStore
import com.androidharness.app.workspace.FileFs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ProjectCheckDeviceTest {
    @Test fun namedChecksCompleteWithoutAScreenAndRemainAssignedToTheirProjects(): Unit = runBlocking {
        val c = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HarnessApp).container
        val root = File(c.appContext.cacheDir, "checks-${System.nanoTime()}").apply { mkdirs() }
        val alpha = "alpha-${System.nanoTime()}"
        val beta = "beta-${System.nanoTime()}"
        try {
            c.terminal.stopTerminal()
            val fs = FileFs(root)
            c.terminal.send("true", fs)
            c.projectChecks.track(alpha, "Build", "true")
            withTimeout(10000) { c.projectChecks.results.first { it[alpha]?.status == ProjectCheckStatus.PASSED } }
            c.terminal.send("false", fs)
            c.projectChecks.track(beta, "Tests", "false")
            withTimeout(10000) { c.projectChecks.results.first { it[beta]?.status == ProjectCheckStatus.FAILED } }
            assertEquals(ProjectCheckStatus.PASSED, c.projectChecks.results.value[alpha]?.status)
            val reopened = ProjectCheckStore(c.appContext, c.terminal)
            assertEquals(ProjectCheckStatus.PASSED, reopened.results.value[alpha]?.status)
            assertEquals(ProjectCheckStatus.FAILED, reopened.results.value[beta]?.status)
            c.terminal.send("sleep 30", fs)
            c.projectChecks.track(alpha, "Long check", "sleep 30")
            c.terminal.stopTerminal()
            withTimeout(10000) { c.projectChecks.results.first { it[alpha]?.status == ProjectCheckStatus.INTERRUPTED } }
        } finally {
            c.terminal.stopTerminal(); root.deleteRecursively()
            c.appContext.getSharedPreferences("project_checks", 0).edit().remove(alpha).remove(beta).apply()
        }
    }
}

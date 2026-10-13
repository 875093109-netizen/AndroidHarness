package com.androidharness.app.ui.settings

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.data.env.HarnessUserService
import com.androidharness.app.data.env.ShizukuManager
import com.androidharness.app.data.env.UserServiceState
import com.androidharness.app.ui.chat.ChatListTestActivity
import com.androidharness.app.ui.theme.HarnessTheme
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShizukuCompatibilityUiDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun liveStatusRecheckAndCopiedDiagnosticsWorkForBothBackends(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("shizukuReady") == "true")
        val context = instrumentation.targetContext
        for (compat in listOf(false, true)) {
            val manager = ShizukuManager(context,
                userServiceClassName = if (compat) "${context.packageName}.MissingUiTestService" else HarnessUserService::class.java.name,
                userServiceTimeoutMs = 2_000, userServiceTag = "ui-${UUID.randomUUID()}")
            try {
                withTimeout(30_000) { manager.serviceState.first { it.isReady } }
                ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                    scenario.onActivity { host -> host.setContent { HarnessTheme {
                        val state by manager.state.collectAsState()
                        val runner by manager.serviceState.collectAsState()
                        val failure by manager.connectionFailure.collectAsState()
                        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
                            ShizukuCard(state, runner, failure, manager::connectionReport, manager::startupReport,
                                manager::refresh, manager::requestPermission)
                        }
                    } } }
                    awaitText(if (compat) "Compatibility runner ready ✓" else "Privileged runner ready ✓")
                    tap("Re-check status")
                    assertTrue(manager.isServiceReady())
                    assertEquals(if (compat) UserServiceState.COMPAT_READY else UserServiceState.BOUND_READY,
                        manager.serviceState.value)
                    if (compat) {
                        awaitText("Compatibility mode restored the connection.")
                        tap("Copy error details")
                        awaitText("Copied")
                        scenario.onActivity {
                            val report = (it.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                .primaryClip!!.getItemAt(0).text.toString()
                            assertTrue(report.contains("Shell runner: COMPAT_READY"))
                            assertTrue(report.contains("Error: TIMEOUT"))
                        }
                    }
                    val screenshot = instrumentation.uiAutomation.takeScreenshot()
                    File(context.getExternalFilesDir(null), "shizuku-${if (compat) "compat" else "normal"}-ui.png")
                        .outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    screenshot.recycle()
                }
            } finally { manager.close() }
        }
    }

    private fun awaitText(text: String): AccessibilityNodeInfo {
        val until = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < until) {
            find(instrumentation.uiAutomation.rootInActiveWindow, text)?.let { return it }
            SystemClock.sleep(100)
        }
        error("Text not visible: $text")
    }

    private fun tap(text: String) {
        var node = awaitText(text)
        while (!node.isClickable) node = requireNotNull(node.parent) { "No button for $text" }
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun find(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.text?.toString() == text) return node
        for (i in 0 until node.childCount) find(node.getChild(i), text)?.let { return it }
        return null
    }
}

package com.androidharness.app.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.ui.chat.components.PlanApprovalCard
import com.androidharness.app.ui.setup.SetupScreen
import com.androidharness.app.ui.theme.HarnessTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class SetupAndPlanDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as HarnessApp).container

    @Test fun setupRequiresStorageBeforeItCanFinish(): Unit = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 30)
        val saved = c.settings.settings.first()
        val finished = AtomicBoolean(false)
        try {
            // The runner changes this grant before instrumentation, because revocation kills the app.
            assertFalse(com.androidharness.app.data.env.StorageAccess.isGranted(c.appContext))
            c.settings.setOnboardingDone(false)
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { host -> host.setContent { HarnessTheme { SetupScreen(c) { finished.set(true) } } } }
                val start = await { it.text?.toString() == "Start harness" }
                await { it.text?.toString() == "Allow storage access to begin" }
                val bounds = Rect(); start.getBoundsInScreen(bounds)
                shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
                SystemClock.sleep(250)
                assertFalse(finished.get()); assertFalse(c.settings.settings.first().onboardingDone)
                screenshot("setup-storage-required")
            }
        } finally {
            c.settings.setOnboardingDone(saved.onboardingDone)
        }
    }

    @Test fun setupCanFinishWithNotificationPermissionDenied() = runBlocking {
        val saved = c.settings.settings.first()
        val finished = AtomicBoolean(false)
        try {
            // The runner revokes this before instrumentation; revoking here kills its own process.
            assertEquals(PackageManager.PERMISSION_DENIED, c.appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { host -> host.setContent { HarnessTheme { SetupScreen(c) { finished.set(true) } } } }
                val start = await { it.text?.toString() == "Start harness" }
                assertTrue(start.isEnabled)
                screenshot("setup-notifications-optional")
                tap("Start harness")
                awaitCondition { finished.get() }
                assertEquals(PackageManager.PERMISSION_DENIED, c.appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
                assertTrue(c.settings.settings.first().onboardingDone)
            }
        } finally {
            c.settings.setOnboardingDone(saved.onboardingDone)
        }
    }

    @Test fun longPlanCanBeExpandedThroughItsFinalStepBeforeApproval() {
        val plan = "# Review this plan\n\n" + (1..35).joinToString("\n\n") {
            "$it. Verify the project and preserve existing work. " + "Detailed implementation steps. ".repeat(5)
        } + "\n\n**FULL_PLAN_END: verify the final result.**"
        val approved = AtomicBoolean(false)
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { host -> host.setContent {
                HarnessTheme { Column(Modifier.fillMaxSize().padding(16.dp)) {
                    PlanApprovalCard(plan, onApprove = { approved.set(true) }, onDiscard = {})
                } }
            } }
            SystemClock.sleep(750)
            screenshot("plan-initial")
            await { it.text?.toString() == "Show full plan" }
            assertNull(find { it.text?.toString()?.contains("FULL_PLAN_END") == true })
            screenshot("plan-collapsed")
            tap("Show full plan")
            await { it.text?.toString() == "Show less" }
            val scroll = await { it.isScrollable }
            repeat(80) {
                if (find { node -> node.text?.toString()?.contains("FULL_PLAN_END") == true && node.isVisibleToUser } == null) {
                    scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    SystemClock.sleep(100)
                }
            }
            await { it.text?.toString()?.contains("FULL_PLAN_END") == true && it.isVisibleToUser }
            assertFalse(approved.get())
            screenshot("plan-expanded-final-step")
            tap("Approve & execute"); assertTrue(approved.get())
        }
    }

    private fun find(node: AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow,
        predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        node ?: return null
        if (predicate(node)) return node
        for (i in 0 until node.childCount) find(node.getChild(i), predicate)?.let { return it }
        return null
    }
    private fun await(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        var result: AccessibilityNodeInfo? = null
        awaitCondition { find(predicate = predicate).also { result = it } != null }
        return result!!
    }
    private fun awaitCondition(predicate: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < until) { if (predicate()) return; SystemClock.sleep(50) }
        error("UI condition did not complete")
    }
    private fun tap(text: String) {
        var node = await { it.text?.toString() == text }
        while (!node.isClickable && node.parent != null) node = node.parent
        if (!node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            val bounds = Rect(); node.getBoundsInScreen(bounds)
            shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
        }
        SystemClock.sleep(250)
    }
    private fun shell(command: String) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }
    private fun screenshot(name: String) {
        val directory = File(c.appContext.getExternalFilesDir(null), "app-improvements-evidence").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}

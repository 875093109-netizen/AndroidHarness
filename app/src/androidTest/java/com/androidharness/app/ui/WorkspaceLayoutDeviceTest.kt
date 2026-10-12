package com.androidharness.app.ui

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.viewModelScope
import com.androidharness.app.HarnessApp
import com.androidharness.app.data.*
import com.androidharness.app.ui.chat.ChatListTestActivity
import com.androidharness.app.ui.chat.ChatViewModel
import com.androidharness.app.ui.settings.WorkspaceLayoutSection
import com.androidharness.app.ui.settings.SettingsScreen
import com.androidharness.app.ui.theme.HarnessTheme
import io.github.rosemoe.sora.widget.CodeEditor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class WorkspaceLayoutDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as HarnessApp).container

    @Test fun appearanceChoicesAtLargeTextPersist() = runBlocking {
        val previous = c.settings.settings.first().workspaceLayout
        try {
            c.settings.setWorkspaceLayout(WorkspaceLayoutMode.AUTO)
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    val settings by c.settings.settings.collectAsState(initial = AppSettings())
                    val scope = rememberCoroutineScope()
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                        MaterialTheme { Column(Modifier.widthIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                            WorkspaceLayoutSection(settings.workspaceLayout) { mode ->
                                scope.launch { c.settings.setWorkspaceLayout(mode) }
                            }
                        } }
                    }
                } }
                scrollToChoice("Desktop")
                tapLabel("Desktop")
                await { runBlocking { c.settings.settings.first().workspaceLayout == WorkspaceLayoutMode.DESKTOP } }
                screenshot("workspace-appearance-large-text.png")
                tapLabel("Phone")
                await { runBlocking { c.settings.settings.first().workspaceLayout == WorkspaceLayoutMode.PHONE } }
                scrollToChoice("Auto", forward = false)
                tapLabel("Auto")
                await { runBlocking { c.settings.settings.first().workspaceLayout == WorkspaceLayoutMode.AUTO } }
            }
        } finally { c.settings.setWorkspaceLayout(previous) }
    }

    @Test fun splitWorkspaceKeepsDraftsAndEditorChangesAcrossResizing() = runBlocking {
        val saved = c.settings.settings.first()
        val root = File(c.appContext.cacheDir, "wide-layout-${System.nanoTime()}").apply { mkdirs() }
        val target = File(root, "layout-fixture.txt").apply { writeText("original\n") }
        val project = c.workspace.addShellProject(root.absolutePath, activate = false)
        val sid = c.sessions.createSession("Workspace layout fixture", project.id)
        var vm: ChatViewModel? = null
        val width = mutableStateOf(1280f)
        try {
            c.settings.setWorkspaceLayout(WorkspaceLayoutMode.DESKTOP)
            instrumentation.runOnMainSync { vm = ChatViewModel(c, sid) }
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                fun mount() { scenario.onActivity { activity -> activity.setContent {
                    MaterialTheme { Box(Modifier.width(width.value.dp).fillMaxHeight()) {
                        ChatWorkspaceScreen(vm!!, onOpenDrawer = {}, onOpenFile = { _, _ -> }, onNewChat = {},
                            onOpenTerminal = {}, onOpenSubagent = {})
                    } }
                } } }
                mount()
                click("Files")
                await { find("Resize workspace panes") != null }
                await { find("layout-fixture.txt") != null }
                val input = allNodes().first { it.isEditable }
                val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Keep my chat draft") }
                assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
                click("layout-fixture.txt")
                var editor: CodeEditor? = null
                await { scenario.onActivity { editor = editorIn(it.window.decorView) }; editor != null }
                click("Edit")
                scenario.onActivity { editor!!.text.insert(0, 0, "unsaved-") }
                scenario.recreate()
                mount()
                await { scenario.onActivity { editor = editorIn(it.window.decorView) }; editor != null }
                scenario.onActivity { assertEquals("unsaved-original\n", editor!!.text.toString()) }
                await { find("Keep my chat draft") != null }
                screenshot("workspace-wide-editor.png")
                instrumentation.runOnMainSync { width.value = 420f }
                await { find("Resize workspace panes") == null }
                await { find("Keep my chat draft") != null }
                click("Files") // Must ask before replacing the unsaved editor.
                await { find("Unsaved changes") != null }
                click("Cancel")
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                await { find("Unsaved changes") != null }
                click("Cancel")
                instrumentation.runOnMainSync { width.value = 1280f }
                await { find("Resize workspace panes") != null }
                scenario.onActivity { assertEquals("unsaved-original\n", editorIn(it.window.decorView)!!.text.toString()) }
                screenshot("workspace-resized-editor.png")
                click("Terminal")
                await { find("Unsaved changes") != null }
                click("Cancel")
                assertEquals("original\n", target.readText())
                scenario.onActivity { assertEquals("unsaved-original\n", editorIn(it.window.decorView)!!.text.toString()) }
                click("Terminal")
                await { find("Unsaved changes") != null }
                click("Save & exit")
                await { target.readText() == "unsaved-original\n" }
                await { find("Run") != null }
                val inputs = allNodes().filter { it.isEditable }
                val chatBounds = Rect().also(inputs.first()::getBoundsInScreen)
                val terminalBounds = Rect().also(inputs.last()::getBoundsInScreen)
                assertTrue("Terminal belongs beside the conversation", terminalBounds.left >= chatBounds.right)
                val command = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    "echo keep-terminal-draft") }
                assertTrue(inputs.last().performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, command))
                screenshot("workspace-terminal-panel.png")
                click("Files")
                await { find("Run") == null }
                await { find("layout-fixture.txt") != null }
                assertNotNull(find("Keep my chat draft"))
                assertEquals("Only the toolbar and panel heading label Files", 2,
                    allNodes().count { it.text?.toString() == "Files" })
                click("Terminal")
                await { find("echo keep-terminal-draft") != null }
                click("Files")
                await { find("layout-fixture.txt") != null }
                screenshot("workspace-wide-files.png")
            }
        } finally {
            vm?.let { instrumentation.runOnMainSync { it.viewModelScope.cancel() } }
            c.sessions.session(sid)?.let { c.sessions.deleteSession(it) }
            c.workspace.deleteProject(project)
            c.settings.setWorkspaceLayout(saved.workspaceLayout)
            c.settings.setLastActiveSessionId(saved.lastActiveSessionId)
            root.deleteRecursively()
        }
    }

    @Test fun desktopSettingsKeepThemeChoicesSeparateAndNavigateCategories() = runBlocking {
        val saved = c.settings.settings.first()
        try {
            c.settings.setWorkspaceLayout(WorkspaceLayoutMode.DESKTOP)
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    val settings by c.settings.settings.collectAsState(initial = saved)
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDesktopLayout provides true,
                        LocalDensity provides Density(density.density, 1.5f)) {
                        HarnessTheme(settings.themeMode, settings.dynamicColor) {
                            SettingsScreen(c, onBack = {})
                        }
                    }
                } }
                await { find("Settings categories") != null }
                scrollToChoice("Light")
                val light = Rect().also(requireNotNull(find("Light"))::getBoundsInScreen)
                val system = Rect().also(requireNotNull(find("System"))::getBoundsInScreen)
                assertTrue("Light is separate from System", light.left >= system.right)
                tapLabel("Light")
                await { runBlocking { c.settings.settings.first().themeMode == ThemeMode.LIGHT } }
                scrollToChoice("Dark")
                val dark = Rect().also(requireNotNull(find("Dark"))::getBoundsInScreen)
                val visibleLight = Rect().also(requireNotNull(find("Light"))::getBoundsInScreen)
                assertTrue("Theme rows must not overlap", dark.top > visibleLight.bottom)
                screenshot("desktop-settings-light.png")
                tapLabel("Dark")
                await { runBlocking { c.settings.settings.first().themeMode == ThemeMode.DARK } }
                screenshot("desktop-settings-dark.png")
                tapLabel("AMOLED")
                await { runBlocking { c.settings.settings.first().themeMode == ThemeMode.AMOLED } }
                screenshot("desktop-settings-amoled.png")
                tapLabel("Agent behavior")
                await { find("Default permission mode") != null }
                scrollToChoice("Appearance", navigation = true)
                tapLabel("Appearance")
                await { find("Workspace layout") != null }
                assertNotNull(find("Settings categories"))
            }
        } finally {
            c.settings.setWorkspaceLayout(saved.workspaceLayout)
            c.settings.setThemeMode(saved.themeMode)
        }
    }

    @Test fun desktopSidebarProtectsEditsAndKeepsEachChatsWorkspace() = runBlocking {
        val saved = c.settings.settings.first()
        val roots = listOf("first", "second").map { name ->
            File(c.appContext.cacheDir, "desktop-$name-${System.nanoTime()}").apply { mkdirs() }
        }
        File(roots[0], "first-project.txt").writeText("first\n")
        File(roots[1], "second-project.txt").writeText("second\n")
        val projects = roots.map { c.workspace.addShellProject(it.absolutePath, activate = false) }
        val first = c.sessions.createSession("Desktop workspace A", projects[0].id)
        val second = c.sessions.createSession("Desktop workspace B", projects[1].id)
        try {
            c.settings.setWorkspaceLayout(WorkspaceLayoutMode.DESKTOP)
            c.settings.setOnboardingDone(true)
            c.settings.setResumeLastChat(true)
            c.settings.setLastActiveSessionId(first)
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent { MaterialTheme { AppNav(c) } } }
                await { find("Project and chat sidebar") != null }
                click("Files")
                click("first-project.txt")
                click("Edit")
                scenario.onActivity { editorIn(it.window.decorView)!!.text.insert(0, 0, "kept-") }
                click("Desktop workspace B")
                await { find("Unsaved changes") != null }
                click("Cancel")
                scenario.onActivity { assertEquals("kept-first\n", editorIn(it.window.decorView)!!.text.toString()) }
                click("Desktop workspace B")
                await { find("Unsaved changes") != null }
                click("Save & exit")
                await { File(roots[0], "first-project.txt").readText() == "kept-first\n" }
                click("Files")
                await { find("second-project.txt") != null }
                assertNull(find("first-project.txt"))
                screenshot("desktop-sidebar-workspaces.png")
            }
        } finally {
            listOf(first, second).forEach { sid -> c.sessions.session(sid)?.let { c.sessions.deleteSession(it) } }
            projects.forEach { c.workspace.deleteProject(it) }
            c.settings.setWorkspaceLayout(saved.workspaceLayout)
            c.settings.setOnboardingDone(saved.onboardingDone)
            c.settings.setResumeLastChat(saved.resumeLastChat)
            c.settings.setLastActiveSessionId(saved.lastActiveSessionId)
            roots.forEach { it.deleteRecursively() }
        }
    }

    private fun editorIn(view: View): CodeEditor? {
        if (view is CodeEditor) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) editorIn(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun allNodes(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) { result += node; for (i in 0 until node.childCount) node.getChild(i)?.let(::visit) }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
        return result
    }
    private fun find(text: String) = allNodes().firstOrNull { it.text?.toString()?.split('\n')?.contains(text) == true || it.contentDescription?.toString() == text }
    private fun scrollToChoice(text: String, forward: Boolean = true, navigation: Boolean = false) {
        await {
            if (find(text)?.isVisibleToUser == true) return@await true
            val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            allNodes().filter { it.isScrollable }.maxByOrNull {
                Rect().also(it::getBoundsInScreen).left * if (navigation) -1 else 1
            }?.performAction(action)
            false
        }
    }
    private fun click(text: String) {
        await {
            val found = find(text) ?: return@await false
            var node = found
            while (!node.isClickable && node.parent != null) node = node.parent
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        instrumentation.waitForIdleSync()
    }
    private fun tapLabel(text: String) {
        await { find(text)?.isVisibleToUser == true }
        val bounds = Rect().also(requireNotNull(find(text))::getBoundsInScreen)
        val now = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
            val event = MotionEvent.obtain(now, now + if (action == MotionEvent.ACTION_UP) 40 else 0,
                action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
        instrumentation.waitForIdleSync()
    }
    private fun await(condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < until) { if (condition()) return; SystemClock.sleep(80) }
        error("Workspace layout condition timed out")
    }
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(c.appContext.cacheDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}

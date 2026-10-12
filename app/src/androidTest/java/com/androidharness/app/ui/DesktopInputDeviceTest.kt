package com.androidharness.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.agent.PermissionMode
import com.androidharness.app.data.*
import com.androidharness.app.ui.chat.ChatListTestActivity
import com.androidharness.app.ui.chat.components.MessageComposer
import com.androidharness.app.ui.theme.HarnessTheme
import io.github.rosemoe.sora.widget.CodeEditor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class DesktopInputDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as HarnessApp).container

    @Test fun desktopComposerSupportsNativeEditingAndSendsExactlyOnce() {
        val value = mutableStateOf(TextFieldValue(""))
        val sends = AtomicInteger()
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                CompositionLocalProvider(LocalDesktopLayout provides true, LocalDesktopInput provides true) {
                    MaterialTheme { Column(Modifier.fillMaxSize().navigationBarsPadding(), verticalArrangement = Arrangement.Bottom) {
                        MessageComposer(false, value.value, null, { value.value = it }, {},
                            { sends.incrementAndGet() }, {}, {}, {})
                    } }
                }
            } }
            await { find("Message input") != null }
            val inputBounds = bounds(requireNotNull(find("Message input")))
            val density = instrumentation.targetContext.resources.displayMetrics.density
            assertTrue("Empty desktop input stays compact and visible",
                inputBounds.height() / density in 20f..56f)
            assertTrue("Composer actions share the input row",
                kotlin.math.abs(bounds(requireNotNull(find("Message actions"))).centerY() - inputBounds.centerY()) < 12 * density)
            mouseClick("Message input")
            instrumentation.sendStringSync("hello")
            await { value.value.text == "hello" }
            key(KeyEvent.KEYCODE_ENTER)
            await { value.value.text.contains('\n') }
            assertEquals(0, sends.get())
            key(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON)
            await { value.value.selection.min == 0 && value.value.selection.max == value.value.text.length }
            key(KeyEvent.KEYCODE_DEL)
            await { value.value.text.isEmpty() }
            instrumentation.runOnMainSync {
                c.appContext.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("fixture", "native paste"))
            }
            key(KeyEvent.KEYCODE_V, KeyEvent.META_CTRL_ON)
            await { value.value.text == "native paste" }
            key(KeyEvent.KEYCODE_ENTER, KeyEvent.META_CTRL_ON)
            await { sends.get() == 1 }
            key(KeyEvent.KEYCODE_TAB)
            await { nodes().any { it.isFocused && !it.isEditable } }
        }
    }

    @Test fun sidebarSupportsRightClickLongPressAndDeletingTheOpenChat() = withWorkspace { _, first, _ ->
        await { find("Native input chat") != null }
        mouseClick("Native input chat", secondary = true)
        await { find("Rename") != null }
        tap("Close")
        longPress("Native input chat")
        await { find("Delete") != null }
        tap("Close")
        tap("Actions for Native input chat")
        await { find("Delete") != null }
        tap("Delete")
        await { runBlocking { c.sessions.session(first) == null } }
        await { find("What do you want to build?") != null }
        assertNotNull(find("Create a Python hello world and run it"))
        assertNotNull(find("Build a simple HTML landing page"))
        assertNotNull(find("Donate"))
        assertNotNull(find("Native other chat"))
    }

    @Test fun workspaceShortcutsSaveEditsAndRunTerminalWithTheKeyboard() = withWorkspace { root, _, scenario ->
        key(KeyEvent.KEYCODE_L, KeyEvent.META_CTRL_ON)
        await { nodes().any { it.isEditable && it.isFocused } }
        key(KeyEvent.KEYCODE_F, KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON)
        await { find("native-input.txt") != null }
        tap("native-input.txt")
        tap("Edit")
        var editor: CodeEditor? = null
        await { scenario.onActivity { editor = editorIn(it.window.decorView) }; editor != null }
        scenario.onActivity {
            editor!!.text.insert(0, 0, "saved-")
            editor!!.requestFocus()
        }
        key(KeyEvent.KEYCODE_S, KeyEvent.META_CTRL_ON)
        await { File(root, "native-input.txt").readText() == "saved-original\n" }
        key(KeyEvent.KEYCODE_T, KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON)
        await { find("Run") != null }
        val input = nodes().last { it.isEditable }
        val marker = "native-keyboard-${System.nanoTime()}"
        val command = "mkdir -p native-child; cd native-child; printf '$marker\\n'"
        assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, command)
        }))
        mouseClickAt(bounds(input).exactCenterX(), bounds(input).exactCenterY())
        key(KeyEvent.KEYCODE_ENTER)
        await { c.terminal.state.value.lastCommand == command && !c.terminal.state.value.busy }
        await { c.terminal.state.value.lines.any { it.trim() == marker } }
        assertTrue("Returning to the same workspace preserves the shell directory",
            c.terminal.state.value.cwd.endsWith("native-child"))
        key(KeyEvent.KEYCODE_DPAD_UP)
        await { nodes().lastOrNull { it.isEditable }?.text?.toString() == command }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        await { nodes().lastOrNull { it.isEditable }?.text.isNullOrEmpty() }
        tap("Access mode: ${PermissionMode.CONFIRM_RISKY.label}")
        tap(PermissionMode.CONFIRM_ALL.label)
        await { runBlocking { c.settings.settings.first().permissionMode == PermissionMode.CONFIRM_ALL } }
        tap("GitHub")
        await { find("Sign in to GitHub") != null || find("Commit & push") != null }
        tap("Close")
        key(KeyEvent.KEYCODE_L, KeyEvent.META_CTRL_ON)
        key(KeyEvent.KEYCODE_N, KeyEvent.META_CTRL_ON)
        await { find("What do you want to build?") != null }
    }

    private fun withWorkspace(block: (File, String, ActivityScenario<ChatListTestActivity>) -> Unit) = runBlocking {
        val saved = c.settings.settings.first()
        val root = File(c.appContext.cacheDir, "native-input-${System.nanoTime()}").apply { mkdirs() }
        File(root, "native-input.txt").writeText("original\n")
        val project = c.workspace.addShellProject(root.absolutePath, activate = false)
        val first = c.sessions.createSession("Native input chat", project.id)
        val second = c.sessions.createSession("Native other chat", project.id)
        try {
            c.settings.setWorkspaceLayout(WorkspaceLayoutMode.DESKTOP)
            c.settings.setOnboardingDone(true)
            c.settings.setResumeLastChat(true)
            c.settings.setLastActiveSessionId(first)
            c.settings.setPermissionMode(PermissionMode.CONFIRM_RISKY)
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    HarnessTheme(saved.themeMode, saved.dynamicColor) { AppNav(c) }
                } }
                await { find("Project and chat sidebar") != null && find("GitHub") != null }
                block(root, first, scenario)
            }
        } finally {
            c.runManager.stopAndJoin(first)
            listOf(first, second).forEach { sid -> c.sessions.session(sid)?.let { c.sessions.deleteSession(it) } }
            c.workspace.deleteProject(project)
            c.settings.setWorkspaceLayout(saved.workspaceLayout)
            c.settings.setOnboardingDone(saved.onboardingDone)
            c.settings.setResumeLastChat(saved.resumeLastChat)
            c.settings.setLastActiveSessionId(saved.lastActiveSessionId)
            c.settings.setPermissionMode(saved.permissionMode)
            root.deleteRecursively()
        }
    }

    private fun editorIn(view: View): CodeEditor? {
        if (view is CodeEditor) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) editorIn(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun nodes(): List<AccessibilityNodeInfo> = buildList {
        // Programmatic history changes can leave UiAutomation's text cache stale.
        instrumentation.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo) {
            add(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)
        }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
    }
    private fun find(text: String) = nodes().firstOrNull {
        it.text?.toString()?.split('\n')?.contains(text) == true || it.contentDescription?.toString() == text
    }
    private fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
    private fun tap(text: String) {
        await { find(text)?.isVisibleToUser == true }
        val rect = bounds(requireNotNull(find(text)))
        pointer(rect.exactCenterX(), rect.exactCenterY(), InputDevice.SOURCE_TOUCHSCREEN)
        instrumentation.waitForIdleSync()
        awaitRenderedFrame()
    }
    private fun mouseClick(text: String, secondary: Boolean = false) {
        await { find(text)?.isVisibleToUser == true }
        val rect = bounds(requireNotNull(find(text)))
        mouseClickAt(rect.exactCenterX(), rect.exactCenterY(), secondary)
    }
    private fun mouseClickAt(x: Float, y: Float, secondary: Boolean = false) =
        pointer(x, y, InputDevice.SOURCE_MOUSE, secondary)
    private fun longPress(text: String) {
        val rect = bounds(requireNotNull(find(text)))
        pointer(rect.exactCenterX(), rect.exactCenterY(), InputDevice.SOURCE_TOUCHSCREEN, holdMs = 650)
    }
    private fun pointer(x: Float, y: Float, source: Int, secondary: Boolean = false, holdMs: Long = 0) {
        val now = SystemClock.uptimeMillis()
        val properties = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0; toolType = if (source == InputDevice.SOURCE_MOUSE) MotionEvent.TOOL_TYPE_MOUSE else MotionEvent.TOOL_TYPE_FINGER
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f })
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
            if (action == MotionEvent.ACTION_UP && holdMs > 0) SystemClock.sleep(holdMs)
            val buttons = if (source == InputDevice.SOURCE_MOUSE && action == MotionEvent.ACTION_DOWN)
                if (secondary) MotionEvent.BUTTON_SECONDARY else MotionEvent.BUTTON_PRIMARY else 0
            val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, 1, properties, coords,
                0, buttons, 1f, 1f, 0, 0, source, 0)
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
        instrumentation.waitForIdleSync()
    }
    private fun key(code: Int, modifiers: Int = 0) {
        val now = SystemClock.uptimeMillis()
        val held = buildList {
            if (modifiers and KeyEvent.META_CTRL_ON != 0) add(KeyEvent.KEYCODE_CTRL_LEFT)
            if (modifiers and KeyEvent.META_ALT_ON != 0) add(KeyEvent.KEYCODE_ALT_LEFT)
        }
        val meta = modifiers or (if (KeyEvent.KEYCODE_CTRL_LEFT in held) KeyEvent.META_CTRL_LEFT_ON else 0) or
            (if (KeyEvent.KEYCODE_ALT_LEFT in held) KeyEvent.META_ALT_LEFT_ON else 0)
        held.forEach { instrumentation.sendKeySync(KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN,
            it, 0, meta, 0, 0, 0, InputDevice.SOURCE_KEYBOARD)) }
        listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP).forEach { action ->
            instrumentation.sendKeySync(KeyEvent(now, SystemClock.uptimeMillis(), action, code, 0,
                meta, KeyEvent.KEYCODE_UNKNOWN, 0, 0, InputDevice.SOURCE_KEYBOARD))
        }
        held.reversed().forEach { instrumentation.sendKeySync(KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP,
            it, 0, 0, 0, 0, 0, InputDevice.SOURCE_KEYBOARD)) }
        instrumentation.waitForIdleSync()
        awaitRenderedFrame()
    }
    private fun awaitRenderedFrame() {
        // waitForIdleSync does not wait for Compose's next rendering frame.
        // Let controlled selection/text updates reach the actual input node.
        val rendered = CountDownLatch(1)
        instrumentation.runOnMainSync {
            android.view.Choreographer.getInstance().postFrameCallback {
                android.view.Choreographer.getInstance().postFrameCallback { rendered.countDown() }
            }
        }
        assertTrue(rendered.await(2, TimeUnit.SECONDS))
    }
    private fun await(condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < until) {
            if (condition()) return
            SystemClock.sleep(80)
        }
        error("Native desktop input condition timed out; editable fields: ${nodes().filter { it.isEditable }.map { it.text }}")
    }
}

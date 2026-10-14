package com.androidharness.app.ui.settings

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.data.*
import com.androidharness.app.llm.*
import com.androidharness.app.ui.LocalDesktopLayout
import com.androidharness.app.ui.LocalDesktopInput
import com.androidharness.app.ui.chat.ChatListTestActivity
import com.androidharness.app.ui.chat.components.ModelPickerSheet
import com.androidharness.app.ui.theme.HarnessTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FallbackDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as HarnessApp).container
    private val desktop get() = InstrumentationRegistry.getArguments().getString("desktop") == "true"
    private val dark get() = InstrumentationRegistry.getArguments().getString("dark") == "true"

    @Test fun defaultChainReordersRestrictsRoutingAndPersists() = withProviders { first, second ->
        val seeded = FallbackPolicy(true, listOf(FallbackTarget(first.id, first.model), FallbackTarget(second.id, second.model)))
        runBlocking { c.settings.setFallbackPolicy(seeded) }
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { theme {
                val settings by c.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
                FallbackSettingsSection(c, settings.fallbackPolicy)
            } } }
            await { find("Fallbacks") != null }
            tap("Fallbacks")
            await { find("Automatic fallbacks") != null }
            scrollTo("Fallback actions 2"); tap("Fallback actions 2"); tap("Move up")
            scrollTo("3 retries"); tap("3 retries"); tap("1 retries")
            scrollTo("Privacy & routing"); tap("Privacy & routing")
            scrollTo("Stay within selected provider"); tap("Stay within selected provider")
            assertVisibleWithinWindow("Stay within selected provider")
            screenshot("fallback-privacy")
            tap("Save fallbacks")
            await { runBlocking { c.settings.settings.first().fallbackPolicy.retries == 1 } }
            val saved = runBlocking { SettingsRepository(c.appContext).settings.first().fallbackPolicy }
            assertEquals(listOf(second.id, first.id), saved.targets.map { it.providerId })
            assertTrue(saved.enabled); assertTrue(saved.sameProviderOnly)
            await { find("Close fallbacks") == null }
            tap("Fallbacks"); await { find("Default for chats") != null }
            scrollTo("Fallback actions 1"); screenshot("fallback-default")
        }
    }

    @Test fun pickerOverrideAddsModelWithoutChangingDefaultOrSelectedModel() = withProviders { first, second ->
        val seeded = FallbackPolicy(true, listOf(FallbackTarget(first.id, first.model)))
        runBlocking { c.settings.setFallbackPolicy(seeded) }
        val sid = UUID.randomUUID().toString()
        val chosen = FallbackTarget(second.id, second.model)
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { theme {
                val settings by c.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
                val record by c.runManager.controls.flow(sid).collectAsStateWithLifecycle()
                val providers by c.providers.providers.collectAsStateWithLifecycle(initialValue = emptyList())
                val catalogs by c.providers.catalogs.collectAsStateWithLifecycle(initialValue = emptyMap())
                var editor by remember { mutableStateOf(false) }
                if (editor) FallbackEditorSheet(c, settings.fallbackPolicy,
                    onDismiss = { editor = false }, chatMode = record.fallbackMode, customPolicy = record.fallbackOverride,
                    onSave = { mode, policy -> c.runManager.controls.update(sid) { it.copy(fallbackMode = requireNotNull(mode), fallbackOverride = policy.takeIf { mode == FallbackMode.CUSTOM }) } })
                else ModelPickerSheet(providers, first.id, first.model, catalogs, {}, { _, _ -> }, { null }, {},
                    fallbackSummary = "Use default", onEditFallbacks = { editor = true })
            } } }
            await { find("Fallbacks") != null }
            SystemClock.sleep(500)
            tap("Fallbacks"); await { find("For this chat") != null }; tap("Custom chain")
            scrollTo("Add model"); tap("Add model")
            await { nodes().any { it.isEditable && it.isVisibleToUser } }
            scrollTo("Manage providers…"); tap("Manage providers…")
            await { find("Providers") != null && nodes().any { it.isEditable && it.isVisibleToUser } }
            tap("Close")
            await { find("Choose model") != null && nodes().any { it.isEditable && it.isVisibleToUser } }
            if (desktop) {
                for (step in 0 until 20) {
                    if (nodes().any { it.isEditable && it.isFocused }) break
                    instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_TAB)
                    SystemClock.sleep(100)
                }
                await { nodes().any { it.isEditable && it.isFocused } }
                instrumentation.sendStringSync(second.model)
                await { nodes().any { it.isEditable && it.text?.toString() == second.model } }
            } else search(second.model)
            if (instrumentation.uiAutomation.windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }) {
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                instrumentation.waitForIdleSync()
                SystemClock.sleep(300)
            }
            scrollTo(second.model); tap(second.model)
            await { find("For this chat") != null }
            scrollTo("Save fallbacks"); screenshot("fallback-custom"); tap("Save fallbacks")
            await { c.runManager.controls.flow(sid).value.fallbackMode == FallbackMode.CUSTOM }
            assertTrue(chosen in c.runManager.controls.flow(sid).value.fallbackOverride!!.targets)
            assertEquals(seeded, runBlocking { c.settings.settings.first().fallbackPolicy })
            assertEquals(first.id, runBlocking { c.settings.settings.first().activeProviderId })
            assertEquals(first.model, runBlocking { c.settings.settings.first().activeModel })
            await { find("Choose model") != null }
            SystemClock.sleep(500)
            tap("Fallbacks"); await { find("For this chat") != null }; tap("Off"); scrollTo("Save fallbacks"); tap("Save fallbacks")
            await { c.runManager.controls.flow(sid).value.fallbackMode == FallbackMode.OFF }
            assertFalse(effectiveFallback(seeded, FallbackMode.OFF, null)!!.enabled)
            val restored = com.androidharness.app.agent.TaskControlStore(File(c.appContext.filesDir, "task-controls")).flow(sid).value
            assertEquals(FallbackMode.OFF, restored.fallbackMode)
        }
        File(c.appContext.filesDir, "task-controls/$sid.json").delete()
    }

    private fun withProviders(block: (ProviderConfig, ProviderConfig) -> Unit) = runBlocking {
        val saved = c.settings.settings.first()
        val first = c.providers.add("Fallback Primary with a long connection name", ProviderType.OPENAI_COMPAT, "https://example.test", "fallback-model-primary", "fixture-key")
        val second = c.providers.add("Fallback Backup with an exceptionally long connection name", ProviderType.OPENAI_COMPAT, "https://example.test", "fallback-model-backup", "fixture-key")
        c.providers.saveCatalog(first.id, listOf(ModelEntry(first.model)))
        c.providers.saveCatalog(second.id, listOf(ModelEntry(second.model)))
        c.settings.setActiveSelection(first.id, first.model)
        try { block(first, second) } finally {
            c.providers.delete(first.id); c.providers.delete(second.id); c.settings.restorePortable(saved)
        }
    }
    @Composable private fun theme(content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalDesktopLayout provides desktop, LocalDesktopInput provides desktop) {
            HarnessTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false) {
                Surface(Modifier.fillMaxSize()) { content() }
            }
        }
    }
    private fun nodes(): List<AccessibilityNodeInfo> = buildList {
        instrumentation.uiAutomation.clearCache()
        fun visit(node: AccessibilityNodeInfo) {
            add(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)
        }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
    }
    private fun find(text: String) = nodes().firstOrNull {
        it.isVisibleToUser && !it.isEditable && (it.text?.toString()?.contains(text) == true || it.contentDescription?.toString() == text)
    }
    private fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)
    private fun search(value: String) {
        repeat(15) {
            if (nodes().any { it.isEditable && it.isVisibleToUser }) return@repeat
            scrollingList()?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            SystemClock.sleep(150)
        }
        val input = nodes().first { it.isEditable && it.isVisibleToUser }
        assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }))
        instrumentation.waitForIdleSync()
        SystemClock.sleep(200)
    }
    private fun tap(text: String) {
        if (find(text) == null) await { find(text) != null || scrollingList()?.isVisibleToUser == true }
        if (find(text) == null) scrollTo(text)
        await { find(text) != null }
        val rect = bounds(requireNotNull(find(text)))
        val source = if (desktop) InputDevice.SOURCE_MOUSE else InputDevice.SOURCE_TOUCHSCREEN
        val now = SystemClock.uptimeMillis()
        val properties = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0; toolType = if (desktop) MotionEvent.TOOL_TYPE_MOUSE else MotionEvent.TOOL_TYPE_FINGER
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { x = rect.exactCenterX(); y = rect.exactCenterY(); pressure = 1f; size = 1f })
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
            val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, 1, properties, coords,
                0, if (desktop && action == MotionEvent.ACTION_DOWN) MotionEvent.BUTTON_PRIMARY else 0,
                1f, 1f, 0, 0, source, 0)
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
        instrumentation.waitForIdleSync()
        SystemClock.sleep(200)
    }
    private fun scrollingList() = nodes().firstOrNull {
        it.isScrollable && it.contentDescription?.toString() !in listOf("Drag handle", "Close sheet")
    }
    private fun scrollTo(text: String) {
        for (action in listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
            for (step in 0 until 12) {
                if (find(text) != null) return
                val list = scrollingList() ?: break
                if (list.actionList.none { it.id == action } || !list.performAction(action)) break
                instrumentation.waitForIdleSync()
                SystemClock.sleep(400)
            }
        }
        screenshot("scroll-failure")
        error("Could not scroll to $text; nodes: ${nodes().map { Triple(it.text, it.contentDescription, it.isScrollable) }}")
    }
    private fun assertVisibleWithinWindow(text: String) {
        val rect = bounds(requireNotNull(find(text)))
        val screen = instrumentation.targetContext.resources.displayMetrics
        assertTrue("$text outside window: $rect", rect.left >= 0 && rect.right <= screen.widthPixels && rect.top >= 0 && rect.bottom <= screen.heightPixels)
    }
    private fun screenshot(name: String) {
        val tag = InstrumentationRegistry.getArguments().getString("variant") ?: "default"
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(c.appContext.getExternalFilesDir(null), "$tag-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        error("Provider UI condition timed out; nodes: ${nodes().map { it.text ?: it.contentDescription }.filterNotNull()}")
    }
}

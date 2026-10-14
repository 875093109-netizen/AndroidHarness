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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ProviderManagementDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as HarnessApp).container
    private val desktop get() = InstrumentationRegistry.getArguments().getString("desktop") == "true"
    private val dark get() = InstrumentationRegistry.getArguments().getString("dark") == "true"

    @Test fun providerScreenReordersHidesSearchesAndPersistsWithoutChangingExistingSelection() = withProviders { first, second ->
        runBlocking { c.settings.setActiveSelection(first.id, "shared-model") }
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { theme { ProvidersScreen(c, {}) } } }
            await { find("Search providers or models") != null }
            search("Fixture")
            scrollTo("Select ${first.name}")
            tap("Actions for ${first.name}"); tap("Move down")
            await { runBlocking { c.providers.providers.first().filter { it.id in setOf(first.id, second.id) }.first().id == second.id } }
            scrollTo("Select ${second.name}")
            if (find("Select ${first.name}") != null) {
                assertTrue(bounds(requireNotNull(find("Select ${second.name}"))).top < bounds(requireNotNull(find("Select ${first.name}"))).top)
            }
            screenshot("providers-reordered")
            tap("Enable ${first.name}")
            await { runBlocking { c.providers.providers.first().first { it.id == first.id }.enabled == false } }
            search("")
            await { find("Select ${first.name}") == null }
            assertEquals(first.id, runBlocking { c.settings.settings.first().activeProviderId })
            assertEquals("shared-model", runBlocking { c.settings.settings.first().activeModel })
            val reloaded = runBlocking { SettingsRepository(c.appContext).settings.first() }
            assertTrue(first.id in reloaded.disabledProviderIds)
            assertTrue(reloaded.providerOrder.indexOf(second.id) < reloaded.providerOrder.indexOf(first.id))
            search("Fixture Primary")
            scrollTo("Enable & use")
            assertVisibleWithinWindow("Enable & use")
            screenshot("providers-disabled-search")
            tap("Enable & use")
            await { runBlocking { first.id !in c.settings.settings.first().disabledProviderIds } }
            search("no-provider-or-model-matches")
            await { find("No matching providers") != null }
            screenshot("providers-empty-search")
        }
    }

    @Test fun managerSheetReordersAndRestoresDisabledProviders() = withProviders { first, second ->
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { theme {
                val providers by c.providers.providers.collectAsStateWithLifecycle(initialValue = emptyList())
                val scope = rememberCoroutineScope()
                ProviderManagerSheet(providers, first.id, c.providers::apiKey, {}, {},
                    onDelete = {},
                    onEnabledChange = { id, enabled -> scope.launch { c.providers.setEnabled(id, enabled) } },
                    onMove = { id, direction, visible -> scope.launch { c.providers.move(id, direction, visible) } },
                    onSave = { _, _, _, _, _, _ -> })
            } } }
            await { find("Search providers") != null }
            search("Fixture")
            scrollTo("Actions for ${second.name}")
            tap("Actions for ${second.name}"); tap("Move up")
            await { runBlocking { c.providers.providers.first().filter { it.id in setOf(first.id, second.id) }.first().id == second.id } }
            tap("Enable ${second.name}")
            await { runBlocking { second.id in c.settings.settings.first().disabledProviderIds } }
            search("")
            await { find("Select ${second.name}") == null }
            search("Fixture Secondary")
            scrollTo("Enable & use")
            screenshot("providers-manager-disabled")
            tap("Enable & use")
            await { runBlocking { second.id !in c.settings.settings.first().disabledProviderIds } }
        }
    }

    @Test fun unifiedSearchKeepsProviderIdentityAndEnablesTheChosenHiddenProvider() {
        val first = ProviderConfig("fixture:one", "Fixture Primary with a long connection name", ProviderType.OPENAI_COMPAT,
            "https://example.test", "shared-model")
        val second = first.copy(id = "fixture:two", name = "Fixture Backup with an exceptionally long connection name", enabled = false)
        val providers = mutableStateOf(listOf(first, second))
        val selected = AtomicReference<ModelShortcut?>()
        val enabled = AtomicReference<String?>()
        val catalog = mapOf(first.id to listOf(ModelEntry("shared-model", displayName = "Shared primary", reasoning = true)),
            second.id to listOf(ModelEntry("shared-model", displayName = "Shared backup", reasoning = false)))
        val shortcuts = ModelShortcuts(c.appContext, "provider-test-${System.nanoTime()}")
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { theme {
                var open by remember { mutableStateOf(true) }
                if (open) ModelPickerSheet(providers.value, first.id, first.model, catalog,
                    onDismiss = { open = false }, onSelect = { id, model -> selected.set(ModelShortcut(id, requireNotNull(model))) },
                    onRefreshCatalog = { "Offline fixture: saved catalog retained" }, onManageProviders = {},
                    modelShortcuts = shortcuts, searchAllProvidersInitially = true,
                    onEnableProvider = { id -> enabled.set(id); providers.value = providers.value.map { if (it.id == id) it.copy(enabled = true) else it } })
            } } }
            await { find("All providers") != null }
            scrollTo("Search models or providers")
            if (desktop) {
                // Tab brings the complete field into view, including in short desktop windows.
                for (step in 0 until 16) {
                    if (nodes().any { it.isEditable && it.isFocused }) break
                    instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_TAB)
                    SystemClock.sleep(100)
                }
                await { nodes().any { it.isEditable && it.isFocused } }
                instrumentation.sendStringSync("shared")
                await { nodes().any { it.isEditable && it.text?.toString() == "shared" } }
            } else search("shared")
            if (instrumentation.uiAutomation.windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }) {
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                instrumentation.waitForIdleSync()
                SystemClock.sleep(400)
            }
            scrollTo("Refresh models"); screenshot("models-before-refresh"); tap("Refresh models")
            await { nodes().any { it.text?.contains("Offline fixture") == true } }
            scrollTo("Shared backup")
            scrollTo("Disabled · Enable & use")
            tap("Favourite Shared backup")
            scrollTo("Favourites"); tap("Favourites")
            scrollTo("Shared backup")
            await { find("Shared backup") != null }
            assertNull(find("Shared primary"))
            screenshot("models-unified-favourites")
            tap("Disabled · Enable & use")
            await { selected.get() != null }
            assertEquals(ModelShortcut(second.id, "shared-model"), selected.get())
            assertEquals(second.id, enabled.get())
            assertTrue(providers.value.last().enabled)
        }
    }

    private fun withProviders(block: (ProviderConfig, ProviderConfig) -> Unit) = runBlocking {
        val saved = c.settings.settings.first()
        val first = c.providers.add("Fixture Primary with a long connection name", ProviderType.OPENAI_COMPAT,
            "https://example.test", "shared-model", "")
        val second = c.providers.add("Fixture Secondary", ProviderType.OPENAI_COMPAT, "https://example.test", "shared-model", "")
        try {
            listOf(first, second).forEach { c.providers.saveCatalog(it.id, listOf(ModelEntry("shared-model"))) }
            val ids = c.providers.providers.first().map { it.id }
            c.settings.restorePortable(saved.copy(providerOrder = listOf(first.id, second.id) + ids.filterNot { it in setOf(first.id, second.id) }))
            block(first, second)
        } finally {
            c.providers.delete(first.id); c.providers.delete(second.id)
            c.settings.restorePortable(saved)
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
        it.isVisibleToUser && (it.text?.toString()?.contains(text) == true || it.contentDescription?.toString() == text)
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

package com.androidharness.app.ui.settings

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.data.ModelShortcut
import com.androidharness.app.data.ModelShortcuts
import com.androidharness.app.llm.*
import com.androidharness.app.ui.chat.ChatListTestActivity
import com.androidharness.app.ui.chat.ChatScreen
import com.androidharness.app.ui.chat.ChatViewModel
import com.androidharness.app.ui.chat.components.ModelPickerSheet
import com.androidharness.app.ui.files.*
import com.androidharness.app.ui.theme.HarnessTheme
import io.github.rosemoe.sora.widget.CodeEditor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class AssistantToolsDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as HarnessApp).container

    @Test fun favouritesAndRecentPersistWithoutChangingModelOnStar() {
        val name = "shortcuts-test-${System.nanoTime()}"
        val repo = ModelShortcuts(c.appContext, name)
        val chosen = AtomicReference<String?>()
        val calls = AtomicInteger()
        val provider = ProviderConfig("shortcut-provider", "Picker test", ProviderType.OPENAI_COMPAT, "http://127.0.0.1", "alpha")
        try {
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { host -> host.setContent { HarnessTheme {
                    ModelPickerSheet(listOf(provider), provider.id, "alpha", mapOf(provider.id to listOf(ModelEntry("alpha"), ModelEntry("beta"))),
                        onDismiss = {}, onSelect = { _, model -> chosen.set(model); calls.incrementAndGet() },
                        onRefreshCatalog = { null }, onManageProviders = {}, modelShortcuts = repo)
                } } }
                tap("Favourite beta")
                assertEquals(0, calls.get())
                assertEquals(listOf(ModelShortcut(provider.id, "beta")), repo.state.value.favourites)
                tap("Favourites"); awaitText("beta")
                screenshot("models-favourites")
                awaitCondition { find { it.contentDescription?.toString() == "Favourite alpha" } == null }
                tap("beta"); assertEquals("beta", chosen.get())
                tap("Recent"); awaitText("beta")
                screenshot("models-recent")
                val restarted = ModelShortcuts(c.appContext, name)
                val key = ModelShortcut(provider.id, "beta")
                assertEquals(listOf(key), restarted.state.value.favourites)
                assertEquals(listOf(key), restarted.state.value.recent)
                tap("Clear recent"); awaitText("Models you choose will appear here.")
                assertTrue(ModelShortcuts(c.appContext, name).state.value.recent.isEmpty())
                assertEquals(listOf(key), repo.state.value.favourites)
                tap("Favourites"); tap("Remove beta from favourites")
                awaitText("Tap a star beside a model to keep it here.")
                assertEquals(1, calls.get())
            }
        } finally { c.appContext.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit() }
    }

    @Test fun unavailableFavouriteCannotSelectAndCanBeRemoved() {
        val name = "shortcuts-missing-${System.nanoTime()}"
        val repo = ModelShortcuts(c.appContext, name)
        val provider = ProviderConfig("missing-provider", "Picker test", ProviderType.OPENAI_COMPAT, "http://127.0.0.1", "alpha")
        val chosen = AtomicInteger()
        repo.toggle(ModelShortcut(provider.id, "removed-model"))
        try {
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { host -> host.setContent { HarnessTheme {
                    ModelPickerSheet(listOf(provider), provider.id, "alpha", mapOf(provider.id to listOf(ModelEntry("alpha"))),
                        onDismiss = {}, onSelect = { _, _ -> chosen.incrementAndGet() },
                        onRefreshCatalog = { null }, onManageProviders = {}, modelShortcuts = repo)
                } } }
                tap("Favourites"); awaitText("removed-model")
                awaitText("Unavailable · refresh or choose another model")
                val bounds = Rect().also(awaitText("removed-model")::getBoundsInScreen)
                shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
                SystemClock.sleep(200); assertEquals(0, chosen.get())
                tap("Remove removed-model from favourites")
                awaitText("Tap a star beside a model to keep it here.")
            }
        } finally { c.appContext.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit() }
    }

    @Test fun selectedCodeActionsCaptureTheRightProjectAndProtectUnsavedChanges(): Unit = runBlocking {
        val root = File(c.appContext.cacheDir, "selected-code-${System.nanoTime()}").apply { mkdirs() }
        val file = File(root, "sample.kt").apply { writeText("before\nfun sample() = 1\nafter\n") }
        val project = c.workspace.addShellProject(root.path, activate = false)
        val captured = AtomicReference<Pair<String?, String>?>()
        try {
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { host -> host.setContent { HarnessTheme {
                    CodeEditorScreen(c, "sample.kt", projectId = project.id, onBack = {},
                        onAskAgent = { id, prompt -> captured.set(id to prompt) })
                } } }
                var editor: CodeEditor? = null
                awaitCondition { scenario.onActivity { editor = editorIn(it.window.decorView) }; editor != null }
                CodeAction.entries.forEach { action ->
                    scenario.onActivity { editor!!.setSelectionRegion(1, 0, 2, 0) }
                    tap("Ask agent"); awaitText("Ask about selected code")
                    if (action == CodeAction.EXPLAIN) screenshot("selected-code-actions")
                    tap(action.label)
                    awaitCondition { captured.get()?.second?.startsWith(action.instruction) == true }
                    val result = captured.get()!!
                    assertEquals(project.id, result.first)
                    assertTrue(result.second.contains("Lines: 2–2"))
                    assertTrue(result.second.contains("fun sample() = 1\n"))
                    assertFalse(result.second.contains("\nbefore\n"))
                    assertFalse(result.second.contains("\nafter\n"))
                    captured.set(null)
                }
                scenario.onActivity { editor!!.text.insert(1, 16, " + 2"); editor!!.setSelectionRegion(1, 0, 2, 0) }
                tap("Ask agent"); tap("Fix"); awaitText("Unsaved changes")
                tap("Cancel"); assertNull(captured.get())
                assertEquals("before\nfun sample() = 1\nafter\n", file.readText())
                scenario.onActivity { editor!!.setSelectionRegion(1, 0, 2, 0) }
                tap("Ask agent"); tap("Fix"); tap("Save & exit")
                awaitCondition { captured.get() != null }
                assertTrue(captured.get()!!.second.contains("unsaved editor buffer"))
                assertTrue(captured.get()!!.second.contains("+ 2"))
                assertTrue(file.readText().contains("+ 2"))
            }
        } finally { c.workspace.deleteProject(project); root.deleteRecursively() }
    }

    @Test fun codeHandoffWaitsForItsChatAndPreservesComposerWithoutSending(): Unit = runBlocking {
        val project = c.workspace.currentProjectOnce()
        val sid = c.sessions.createProjectSession(project.id)
        val saved = c.pendingCodeDraft.value
        val store = ViewModelStore()
        try {
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { host ->
                    val vm = ChatViewModel(c, sid); store.put("chat", vm)
                    host.setContent { HarnessTheme { ChatScreen(vm, {}, { _, _ -> }, {}, {}, onOpenSubagent = {}) } }
                }
                val input = await { it.isEditable }
                input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Keep my draft")
                })
                await { it.text?.toString() == "Keep my draft" }
                val other = CodeChatDraft("another-chat", "Must not consume this")
                c.pendingCodeDraft.value = other
                SystemClock.sleep(500); assertEquals(other, c.pendingCodeDraft.value)
                c.pendingCodeDraft.value = CodeChatDraft(sid, "Explain sample.kt lines 2–3")
                await { it.text?.toString()?.contains("Keep my draft\n\nExplain sample.kt lines 2–3") == true }
                assertNull(c.pendingCodeDraft.value)
                assertTrue(c.sessions.messages(sid).isEmpty())
                screenshot("selected-code-chat-draft")
            }
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            c.pendingCodeDraft.value = saved
            c.sessions.session(sid)?.let { c.sessions.deleteSession(it) }
        }
    }

    @Test fun troubleshootingChecksFailureRetryAndExportsOnlySafeStates(): Unit = runBlocking {
        val server = ServerSocket(0)
        val code = AtomicInteger(401)
        val requests = AtomicInteger()
        val thread = Thread {
            while (!server.isClosed) try {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val reader = socket.getInputStream().bufferedReader()
                    while (reader.readLine()?.isNotEmpty() == true) { }
                    requests.incrementAndGet()
                    val body = if (code.get() == 200) "{\"data\":[{\"id\":\"test-model\"}]}" else "{\"error\":\"secret-body\"}"
                    socket.getOutputStream().write(("HTTP/1.1 ${code.get()} Test\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body").toByteArray())
                }
            } catch (_: Exception) { }
        }.apply { isDaemon = true; start() }
        val settings = c.settings.settings.first()
        val provider = c.providers.add("private-provider-name", ProviderType.OPENAI_COMPAT, "http://127.0.0.1:${server.localPort}", "test-model", "private-credential-value")
        val opened = AtomicReference<String?>()
        try {
            c.settings.setActiveSelection(provider.id, provider.model)
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { host -> host.setContent { HarnessTheme {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        TroubleshootingSection(c) { opened.set(it.name) }
                    }
                } } }
                awaitText("Default provider")
                tap("Check models")
                await { it.text?.toString()?.contains(CatalogCheck.AUTH.detail) == true }
                screenshot("troubleshooting-provider-failure")
                tap("Check models") // retry must remain available after failure
                await { it.text?.toString()?.contains(CatalogCheck.AUTH.detail) == true }
                awaitCondition { requests.get() >= 2 }; SystemClock.sleep(250)
                code.set(200); tap("Check models"); await { it.text?.toString()?.contains(CatalogCheck.REACHABLE.detail) == true }
                tap("Copy report"); awaitText("Copied")
                var report = ""
                scenario.onActivity { report = (it.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip!!.getItemAt(0).text.toString() }
                assertTrue(report.contains("Default provider: Catalog reachable"))
                assertFalse(report.contains("private-provider-name"))
                assertFalse(report.contains("private-credential-value"))
                assertFalse(report.contains("127.0.0.1"))
                assertFalse(report.contains("secret-body"))
                assertFalse(report.contains(projectPath()))
                screenshot("troubleshooting-provider-reachable")
                scrollTo("Notifications · optional")
                awaitText("Notification settings"); screenshot("troubleshooting-optional-features")
                scrollBackTo("Manage provider"); tap("Manage provider")
                assertEquals(SettingsPage.MODELS.name, opened.get())
            }
        } finally {
            if (settings.activeProviderId != null) c.settings.setActiveSelection(settings.activeProviderId, settings.activeModel)
            else { c.settings.setActiveProvider(null); c.settings.setActiveModel(settings.activeModel) }
            c.providers.delete(provider.id)
            server.close(); thread.join(1000)
        }
    }

    @Test fun editorReturnsToExistingChatDraftAcrossWorkspaceNavigation(): Unit = runBlocking {
        codeNavigation(com.androidharness.app.data.WorkspaceLayoutMode.PHONE)
    }

    @Test fun selectedCodeWorksInDesktopWorkspace(): Unit = runBlocking {
        org.junit.Assume.assumeTrue(c.appContext.resources.configuration.screenWidthDp >= 720)
        codeNavigation(com.androidharness.app.data.WorkspaceLayoutMode.DESKTOP)
    }

    private suspend fun codeNavigation(layout: com.androidharness.app.data.WorkspaceLayoutMode) {
        val saved = c.settings.settings.first()
        val defaultProject = c.workspace.currentProjectOnce()
        val root = File(c.appContext.cacheDir, "code-navigation-${System.nanoTime()}").apply { mkdirs() }
        File(root, "sample.kt").writeText("fun selected() = 42\n")
        val project = c.workspace.addShellProject(root.path, activate = false)
        val sid = c.sessions.createProjectSession(project.id)
        try {
            c.settings.setOnboardingDone(true)
            c.settings.setResumeLastChat(true)
            c.settings.setLastActiveSessionId(sid)
            c.settings.setWorkspaceLayout(layout)
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { host -> host.setContent { HarnessTheme { com.androidharness.app.ui.AppNav(c) } } }
                val input = awaitComposer()
                input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, android.os.Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Keep existing chat draft")
                })
                await { it.text?.toString() == "Keep existing chat draft" }
                tap(if (layout == com.androidharness.app.data.WorkspaceLayoutMode.PHONE) "Workspace files" else "Files"); tap("sample.kt")
                var editor: CodeEditor? = null
                awaitCondition { scenario.onActivity { editor = editorIn(it.window.decorView) }; editor != null }
                scenario.onActivity { editor!!.setSelectionRegion(0, 0, 1, 0) }
                tap("Ask agent"); tap("Explain")
                await { it.isEditable && it.text?.toString()?.contains("Keep existing chat draft\n\nExplain the selected code") == true }
                assertEquals(project.id, c.sessions.session(sid)?.projectId)
                assertEquals(defaultProject.id, c.workspace.currentProjectOnce().id)
                assertTrue(c.sessions.messages(sid).isEmpty())
                screenshot("selected-code-existing-chat-${layout.name.lowercase()}")
            }
        } finally {
            c.settings.setOnboardingDone(saved.onboardingDone)
            c.settings.setResumeLastChat(saved.resumeLastChat)
            c.settings.setLastActiveSessionId(saved.lastActiveSessionId)
            c.settings.setWorkspaceLayout(saved.workspaceLayout)
            c.pendingCodeDraft.value = null
            c.sessions.session(sid)?.let { c.sessions.deleteSession(it) }
            c.workspace.deleteProject(project); root.deleteRecursively()
        }
    }

    @Test fun settingsOpensTroubleshootingAndRepairLinks() {
        val saved = c.pendingSettingsScroll.value
        try {
            c.pendingSettingsScroll.value = "troubleshooting"
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                scenario.onActivity { host -> host.setContent { HarnessTheme { SettingsScreen(c, onBack = {}) } } }
                awaitText("Troubleshooting")
                awaitText("Default provider")
                screenshot("troubleshooting-settings-page")
                scrollTo("Open terminal settings"); tap("Open terminal settings")
                awaitText("Terminal & device")
                awaitText("Terminal & environment")
            }
        } finally { c.pendingSettingsScroll.value = saved }
    }

    private fun projectPath(): String = c.appContext.filesDir.absolutePath
    private fun editorIn(view: View): CodeEditor? {
        if (view is CodeEditor) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) editorIn(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun awaitComposer(): AccessibilityNodeInfo {
        var input: AccessibilityNodeInfo? = null
        awaitCondition {
            val fields = mutableListOf<AccessibilityNodeInfo>()
            fun visit(node: AccessibilityNodeInfo?) {
                if (node == null) return
                if (node.isEditable && node.isVisibleToUser) fields.add(node)
                for (index in 0 until node.childCount) visit(node.getChild(index))
            }
            visit(freshRoot())
            // The desktop sidebar's search field precedes the chat composer.
            input = fields.lastOrNull()
            input != null
        }
        return input!!
    }
    private fun freshRoot(): AccessibilityNodeInfo? {
        if (android.os.Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        return instrumentation.uiAutomation.rootInActiveWindow
    }
    private fun find(node: AccessibilityNodeInfo? = freshRoot(),
        predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (predicate(node) && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) find(node.getChild(i), predicate)?.let { return it }
        return null
    }
    private fun await(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        var result: AccessibilityNodeInfo? = null
        awaitCondition { find(predicate = predicate).also { result = it } != null }
        return result!!
    }
    private fun awaitText(text: String) = await { it.text?.toString() == text || it.contentDescription?.toString() == text }
    private fun awaitCondition(predicate: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 20000
        while (SystemClock.uptimeMillis() < until) { if (predicate()) return; SystemClock.sleep(50) }
        error("UI condition did not complete")
    }
    private fun tap(text: String) {
        var node = awaitText(text)
        while (!node.isClickable && node.parent != null) node = node.parent
        if (!node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            val bounds = Rect().also(node::getBoundsInScreen)
            shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
        }
        SystemClock.sleep(200)
    }
    private fun scrollTo(text: String) { scroll(text, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) }
    private fun scrollBackTo(text: String) { scroll(text, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) }
    private fun scroll(text: String, action: Int) {
        repeat(40) {
            if (find { it.text?.toString() == text && it.isVisibleToUser } != null) return
            val scroller = find { it.isScrollable }
            val bounds = Rect().also { scroller?.getBoundsInScreen(it) }
            if (!bounds.isEmpty) {
                // Small touch gestures cannot skip a short repair button between whole-page jumps.
                val low = bounds.top + bounds.height() * 3 / 4
                val high = bounds.top + bounds.height() / 4
                val start = if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) low else high
                val end = if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) high else low
                shell("input swipe ${bounds.centerX()} $start ${bounds.centerX()} $end 300")
            }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(200)
        }
        screenshot("scroll-missing-target")
        val visible = mutableListOf<String>()
        fun describe(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (node.isVisibleToUser) {
                node.text?.let { visible.add(it.toString()) }
                if (node.isScrollable) visible.add("scroll actions=" + node.actionList.toString())
            }
            for (index in 0 until node.childCount) describe(node.getChild(index))
        }
        describe(instrumentation.uiAutomation.rootInActiveWindow)
        error("Could not scroll to $text. Visible UI: $visible")
    }
    private fun shell(command: String) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }
    private fun screenshot(name: String) {
        val directory = File(c.appContext.getExternalFilesDir(null), "assistant-tools-evidence").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}

package com.androidharness.app.ui.chat

import android.content.Intent
import android.content.pm.ActivityInfo
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.room.Room
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.MainActivity
import com.androidharness.app.agent.*
import com.androidharness.app.core.*
import com.androidharness.app.data.*
import com.androidharness.app.data.db.*
import com.androidharness.app.llm.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import java.net.ServerSocket
import okio.buffer
import okio.source
import kotlinx.serialization.json.*

@RunWith(AndroidJUnit4::class)
class ModelLockingDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val c get() = (instrumentation.targetContext.applicationContext as HarnessApp).container

    @Test fun migrationAndReopeningPreserveHistoryAndChatSelections() = runBlocking {
        val context = instrumentation.targetContext
        val name = "model-locking-${System.nanoTime()}.db"
        var db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        try {
            var sessions = SessionRepository(db)
            val sid = sessions.createSession("Existing chat")
            sessions.addMessage(sid, ChatMessage(role = Role.USER, text = "Keep this history"))
            db.close()
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use {
                it.execSQL("ALTER TABLE sessions DROP COLUMN modelSelectionJson")
                it.version = 14
            }
            db = Room.databaseBuilder(context, AppDatabase::class.java, name)
                .addMigrations(AppDatabase.MIGRATION_14_15).build()
            sessions = SessionRepository(db)
            assertNull(sessions.session(sid)?.modelSelectionJson)
            assertEquals("Keep this history", sessions.messages(sid).single().text)
            val selection = ChatModelSelection(active = ChatModelChoice("provider-a", "model-a"),
                planning = ChatModelChoice("provider-b", "plan-b"), thinking = ThinkingLevel.HIGH)
            sessions.initializeModelSelection(sid, selection)
            val another = sessions.createSession("Other chat")
            sessions.initializeModelSelection(another, selection.copy(active = ChatModelChoice("provider-b", "model-b")))
            // A later default must not overwrite an existing snapshot.
            assertEquals(selection, sessions.initializeModelSelection(sid, ChatModelSelection()))
            db.close()
            db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            sessions = SessionRepository(db)
            assertEquals(selection, ChatModelSelection.decode(sessions.session(sid)?.modelSelectionJson))
            assertEquals("model-b", ChatModelSelection.decode(sessions.session(another)?.modelSelectionJson)?.active?.model)
            assertEquals("Keep this history", sessions.messages(sid).single().text)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun perChatModelsDriveRequestsAndMissingProvidersNeverSendElsewhere() = runBlocking {
        fixture { _, provider, a, b, requests ->
            c.chatModels.setShared(a, false)
            c.chatModels.initialize(b)
            c.chatModels.select(a, ChatModelTarget.ACTIVE, provider.id, "alpha-model")
            c.chatModels.select(b, ChatModelTarget.ACTIVE, provider.id, "beta-model")
            c.chatModels.select(a, ChatModelTarget.PLANNING, provider.id, "plan-model")
            c.chatModels.select(a, ChatModelTarget.EXECUTION, provider.id, "exec-model")
            c.chatModels.setThinking(a, ThinkingLevel.HIGH)
            c.chatModels.setThinking(b, ThinkingLevel.LOW)
            val vmA = onMain { ChatViewModel(c, a) }
            val vmB = onMain { ChatViewModel(c, b) }
            vmA.state.first { it.modelSelectionReady }; vmB.state.first { it.modelSelectionReady }
            assertEquals("alpha-model", vmA.state.value.effectiveModel)
            assertEquals("beta-model", vmB.state.value.effectiveModel)
            assertEquals(ThinkingLevel.HIGH, vmA.state.value.thinkingLevel)
            assertEquals(ThinkingLevel.LOW, vmB.state.value.thinkingLevel)
            assertTrue(onMain { vmA.send("Alpha request") })
            awaitRun(a, "Alpha request")
            onMain { vmB.send("Beta request") }
            awaitRun(b, "Beta request")
            assertEquals(listOf("alpha-model", "beta-model"), requests.toList())
            assertFalse(c.runManager.controls.flow(a).value.allowModelFallback)
            onMain { vmA.toggleDualPlanning() }
            assertEquals(AgentMode.PLAN, vmA.state.value.mode)
            onMain { vmA.send("Plan request") }
            awaitRun(a, "Plan request")
            assertEquals("plan-model", requests.last())
            onMain { vmA.executePendingPlan() }
            awaitRun(a, "The plan above is approved. Execute it step by step now.")
            assertEquals("exec-model", requests.last())
            // Shared choices and automation defaults are not rewritten by private chat picks.
            assertEquals("alpha-model", c.settings.settings.first().activeModel)
            c.chatModels.setShared(b, true)
            vmA.state.first { it.sameModelAcrossChats && it.effectiveModel == "beta-model" }
            c.chatModels.select(null, ChatModelTarget.ACTIVE, provider.id, "shared-model")
            vmA.state.first { it.effectiveModel == "shared-model" }
            c.chatModels.setShared(b, false)
            vmA.state.first { !it.sameModelAcrossChats && it.effectiveModel == "alpha-model" }
            vmB.state.first { !it.sameModelAcrossChats && it.effectiveModel == "beta-model" }
            c.providers.update(provider.copy(model = "changed-provider-default"), "fixture")
            assertEquals("alpha-model", c.chatModels.observe(a).first().selection.active?.model)
            c.providers.delete(provider.id)
            vmB.state.first { it.providers.none { p -> p.id == provider.id } }
            val count = requests.size
            assertFalse(onMain { vmB.send("Must not use another provider") })
            assertTrue(vmB.state.value.error.orEmpty().contains("unavailable"))
            assertEquals(count, requests.size)
            assertEquals("beta-model", ChatModelSelection.decode(c.sessions.session(b)?.modelSelectionJson)?.active?.model)
            assertTrue(onMain { vmB.send("/cost") })
            assertTrue(vmB.state.value.showCostDialog)
            c.chatModels.select(b, ChatModelTarget.ACTIVE, HarnessProvider.ID, "removed-pinned-model")
            vmB.state.first { it.effectiveModel == "removed-pinned-model" }
            assertFalse(onMain { vmB.send("Must not replace the pinned model") })
            assertTrue(vmB.state.value.error.orEmpty().contains("model is no longer available"))
            assertEquals(count, requests.size)
            onMain { vmA.viewModelScope.cancel(); vmB.viewModelScope.cancel() }
        }
    }

    @Test fun pickerSwitchChatNavigationAndRecreationKeepSelectionsAndDraft() = runBlocking {
        fixture { activity, provider, a, b, _ ->
            awaitText("Alpha model chat")
            setText("Keep my unsent draft")
            shell("input keyevent 4")
            tap("Switch model")
            awaitText("Use the same model in every chat")
            screenshot("model-picker-shared")
            tap("Use the same model in every chat")
            withTimeout(15000) { c.settings.settings.first { !it.sameModelAcrossChats } }
            awaitText("Each chat remembers its own model.")
            screenshot("model-picker-this-chat")
            scrollTo("beta-model"); tap("beta-model")
            withTimeout(15000) { c.chatModels.observe(a).first { it.selection.active?.model == "beta-model" } }
            awaitText("This chat"); awaitText("Keep my unsent draft")
            tap("Open navigation"); scrollTo("Beta model chat"); tap("Beta model chat")
            awaitText("Beta model chat"); awaitText("This chat")
            assertEquals("alpha-model", c.chatModels.observe(b).first().selection.active?.model)
            tap("Switch model"); scrollTo("exec-model"); tap("exec-model")
            withTimeout(15000) { c.chatModels.observe(b).first { it.selection.active?.model == "exec-model" } }
            shell("input keyevent 4"); awaitText("Alpha model chat")
            awaitText("Keep my unsent draft")
            assertEquals("beta-model", c.chatModels.observe(a).first().selection.active?.model)
            tap("Switch model"); awaitText("Each chat remembers its own model.")
            tap("Use the same model in every chat")
            withTimeout(15000) { c.settings.settings.first { it.sameModelAcrossChats && it.activeModel == "beta-model" } }
            tap("Use the same model in every chat")
            withTimeout(15000) { c.settings.settings.first { !it.sameModelAcrossChats } }
            tap("Close")
            val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
            instrumentation.runOnMainSync { activity.recreate() }
            val recreated = instrumentation.waitForMonitorWithTimeout(monitor, 10000) as? MainActivity
            instrumentation.removeMonitor(monitor)
            assertNotNull(recreated)
            try {
                awaitText("This chat"); awaitText("Keep my unsent draft")
                tap("Switch model"); awaitText("Each chat remembers its own model.")
                screenshot("model-picker-restored")
                instrumentation.runOnMainSync { recreated!!.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                awaitLandscape()
                scrollTo("beta-model"); screenshot("model-picker-landscape")
                tap("beta-model"); awaitText("This chat")
                assertEquals(provider.id, c.chatModels.observe(a).first().selection.active?.providerId)
            } finally { recreated?.let { instrumentation.runOnMainSync { it.finish() } } }
        }
    }

    @Test fun pickerControlsRemainReachableInLandscape() = runBlocking {
        fixture { activity, _, a, _, _ ->
            awaitText("Alpha model chat")
            tap("Switch model")
            tap("Use the same model in every chat")
            withTimeout(15000) { c.settings.settings.first { !it.sameModelAcrossChats } }
            awaitText("Each chat remembers its own model.")
            screenshot("model-picker-accessible")
            instrumentation.runOnMainSync { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            awaitLandscape()
            scrollTo("beta-model")
            screenshot("model-picker-accessible-landscape")
            tap("beta-model")
            withTimeout(15000) { c.chatModels.observe(a).first { it.selection.active?.model == "beta-model" } }
            awaitText("This chat")
        }
    }

    private suspend fun fixture(block: suspend (MainActivity, ProviderConfig, String, String, MutableList<String>) -> Unit) {
        val saved = c.settings.settings.first()
        val previous = c.workspace.currentProjectOnce()
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val server = FixtureServer(requests)
        val root = File(c.appContext.cacheDir, "model-locking-ui-${System.nanoTime()}").apply { mkdirs() }
        val project = c.workspace.addShellProject(root.path, activate = false)
        val provider = c.providers.add("Model locking fixture", ProviderType.OPENAI_COMPAT,
            "http://127.0.0.1:${server.port}/v1", "alpha-model", "fixture")
        c.providers.saveCatalog(provider.id, listOf("alpha-model", "beta-model", "plan-model", "exec-model", "shared-model").map { ModelEntry(it) })
        withTimeout(10000) { c.providers.providers.first { list -> list.any { it.id == provider.id } } }
        val a = c.sessions.createSession("Alpha model chat", project.id)
        val b = c.sessions.createSession("Beta model chat", project.id)
        c.settings.setModelSelectionScope(true)
        c.settings.setActiveSelection(provider.id, "alpha-model")
        c.settings.setPlanningModel(null, null); c.settings.setExecutionModel(null, null)
        c.settings.setPlanningModelsPromoSeen(true)
        c.settings.setOnboardingDone(true); c.settings.setResumeLastChat(true); c.settings.setLastActiveSessionId(a)
        c.settings.setThemeMode(if (InstrumentationRegistry.getArguments().getString("dark") == "true") ThemeMode.DARK else ThemeMode.LIGHT)
        shell("pm grant ${c.appContext.packageName} android.permission.POST_NOTIFICATIONS")
        val activity = instrumentation.startActivitySync(Intent(c.appContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try { withTimeout(90000) { block(activity, provider, a, b, requests) } }
        finally {
            c.runManager.stopAndJoin(a); c.runManager.stopAndJoin(b)
            instrumentation.runOnMainSync { activity.finish() }
            c.sessions.session(a)?.let { c.sessions.deleteSession(it) }
            c.sessions.session(b)?.let { c.sessions.deleteSession(it) }
            c.workspace.deleteProject(project); c.workspace.setActiveProject(previous.id)
            c.providers.delete(provider.id); server.close()
            c.settings.restorePortable(saved)
            c.settings.setPlanningModelsPromoSeen(saved.planningModelsPromoSeen)
            c.settings.setLastActiveSessionId(saved.lastActiveSessionId)
            c.settings.setOnboardingDone(saved.onboardingDone)
            shell("settings put system accelerometer_rotation 0"); shell("settings put system user_rotation 0")
            root.deleteRecursively()
        }
    }

    /** Real requests go through the production OpenAI-compatible transport, entirely on the emulator. */
    private class FixtureServer(private val requests: MutableList<String>) : AutoCloseable {
        private val socket = ServerSocket(0)
        val port get() = socket.localPort
        private val worker = Thread {
            while (!socket.isClosed) {
                try {
                    socket.accept().use { client ->
                        client.soTimeout = 15000
                        val source = client.getInputStream().source().buffer()
                        val request = source.readUtf8Line() ?: return@use
                        var length = 0L
                        while (true) {
                            val header = source.readUtf8Line() ?: break
                            if (header.isBlank()) break
                            if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toLong()
                        }
                        val body = if (length > 0) source.readUtf8(length) else ""
                        val model = if (body.isNotEmpty()) Json.parseToJsonElement(body).jsonObject["model"]?.jsonPrimitive?.content else null
                        val catalog = """{"data":[{"id":"alpha-model"},{"id":"beta-model"},{"id":"plan-model"},{"id":"exec-model"},{"id":"shared-model"}]}"""
                        val response = if (request.contains("/chat/completions") && model != null) {
                            requests += model
                            "data: {\"choices\":[{\"delta\":{\"content\":\"Fixture reply from $model\"}}]}\n\n" +
                                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":5}}\n\n" +
                                "data: [DONE]\n\n"
                        } else catalog
                        val bytes = response.toByteArray(Charsets.UTF_8)
                        val type = if (request.contains("/chat/completions")) "text/event-stream" else "application/json"
                        client.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(bytes); flush()
                        }
                    }
                } catch (e: Exception) { if (!socket.isClosed) throw e }
            }
        }.apply { isDaemon = true; start() }
        override fun close() { socket.close(); worker.join(1000) }
    }

    private suspend fun awaitRun(sid: String, prompt: String) = withTimeout(20000) {
        while (c.runManager.controls.flow(sid).value.let { !it.completed || it.initialPrompt != prompt }) {
            c.runManager.live(sid).value.error?.let { error(it) }
            delay(50)
        }
    }
    private fun <T> onMain(action: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(action) }
        return result!!.getOrThrow()
    }
    private fun find(node: AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow,
        predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        node ?: return null
        if (predicate(node)) return node
        for (i in 0 until node.childCount) find(node.getChild(i), predicate)?.let { return it }
        return null
    }
    private fun matches(node: AccessibilityNodeInfo, text: String) = node.isVisibleToUser &&
        (node.text?.toString()?.split('\n')?.contains(text) == true || node.contentDescription?.toString() == text)
    private fun clickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var target = node
        while (!target.isClickable) target = target.parent ?: return null
        return target
    }
    private fun awaitText(text: String, clickableOnly: Boolean = false): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < deadline) {
            find { matches(it, text) && (!clickableOnly || clickableParent(it) != null) }?.let { return it }
            SystemClock.sleep(100)
        }
        screenshot("model-locking-failure")
        error("Missing UI text: $text")
    }
    private fun tap(text: String) {
        awaitText(text, clickableOnly = true); SystemClock.sleep(350)
        val target = clickableParent(awaitText(text, clickableOnly = true))!!
        if (!target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            val bounds = Rect(); target.getBoundsInScreen(bounds)
            shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
        }
        SystemClock.sleep(300)
    }
    private fun setText(text: String) {
        val editable = find { it.isEditable } ?: error("No composer")
        editable.performAction(AccessibilityNodeInfo.ACTION_CLICK); SystemClock.sleep(300)
        assertTrue(editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }))
        SystemClock.sleep(250)
    }
    private fun awaitLandscape() {
        val deadline = SystemClock.uptimeMillis() + 10000
        while (instrumentation.targetContext.resources.displayMetrics.let { it.widthPixels <= it.heightPixels }) {
            check(SystemClock.uptimeMillis() < deadline) { "Landscape did not settle" }
            SystemClock.sleep(100)
        }
        SystemClock.sleep(1000)
    }
    private fun scrollTo(text: String) {
        repeat(10) {
            if (find { matches(it, text) && clickableParent(it) != null } != null) return
            val scrollable = find { it.isScrollable }
            if (scrollable?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) != true) {
                val d = instrumentation.targetContext.resources.displayMetrics
                shell("input swipe ${d.widthPixels / 2} ${d.heightPixels * 3 / 4} ${d.widthPixels / 2} ${d.heightPixels / 3} 300")
            }
            SystemClock.sleep(300)
        }
        awaitText(text, clickableOnly = true)
    }
    private fun shell(command: String) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }
    private fun screenshot(name: String) {
        SystemClock.sleep(250)
        val folder = File(c.appContext.getExternalFilesDir(null), "model-locking-evidence").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }
}

package com.androidharness.app.telegram

import android.app.NotificationManager
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.agent.*
import com.androidharness.app.core.*
import com.androidharness.app.llm.*
import com.androidharness.app.ui.chat.ChatListTestActivity
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real foreground service, Room chats, RunManager, approvals, checkpoints and file tools.
 * Only the Telegram transport and model responses are fixtures. */
@RunWith(AndroidJUnit4::class)
class TelegramAgentDeviceTest {
    @Test fun minimizedAppEditsAssignedProjectWithExactApprovalsAndContinuousHistory() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val c = (instrumentation.targetContext.applicationContext as HarnessApp).container
        val original = c.telegram
        original.pause()
        val prefs = c.settings.settings.first()
        val previous = c.workspace.currentProjectOnce()
        val root = File(c.appContext.cacheDir, "telegram-agent-${System.nanoTime()}").apply { mkdirs() }
        val aRoot = File(root, "alpha").apply { mkdirs() }
        val bRoot = File(root, "beta").apply { mkdirs() }
        val a = c.workspace.addShellProject(aRoot.path)
        val b = c.workspace.addShellProject(bRoot.path)
        val sid = c.sessions.createSession("Telegram integration", a.id)
        val config = c.providers.add("Telegram fixture", ProviderType.OPENAI_COMPAT, "https://example.invalid", "fixture", "fixture")
        val firstId = System.currentTimeMillis()
        val count = AtomicInteger()
        val histories = mutableListOf<List<ChatMessage>>()
        val provider = object : LlmProvider {
            override fun streamChat(config: ProviderConfig, apiKey: String, systemPrompt: String,
                messages: List<ChatMessage>, tools: List<ToolSchema>, options: RequestOptions) = flow {
                synchronized(histories) { histories += messages.toList() }
                when (count.incrementAndGet()) {
                    1 -> emit(StreamEvent.ToolCallReady(ToolCallData("reused-id", "write_file", """{"path":"approved.txt","content":"approved change"}""")))
                    2 -> emit(StreamEvent.ToolCallReady(ToolCallData("reused-id", "write_file", """{"path":"denied.txt","content":"must not be written"}""")))
                    3 -> emit(StreamEvent.ToolCallReady(ToolCallData("question-id", "ask_user", """{"question":"Which label should I use?","options":["Alpha","Beta"]}""")))
                    4 -> emit(StreamEvent.TextDelta("Finished the approved edit with Alpha."))
                    else -> emit(StreamEvent.TextDelta("I remember the approved edit and Alpha."))
                }
                emit(StreamEvent.Done(if (count.get() <= 3) "tool_calls" else "stop"))
            }
        }
        val engine = AgentEngine({ provider }, c.registry, c.checkpoints, c.images, c.linuxEnv, c.shizuku, c.skills)
        val runs = RunManager(c.appContext, engine, c.sessions, c.checkpoints, c.workspace,
            c.linuxEnv, c.settings, TodoStore(), c.providers)
        val api = TelegramDeviceApi()
        var credentials: String? = null
        val setup = TelegramSetupRepository({ credentials }, { credentials = it }, { api })
        val store = TelegramGatewayStore(File(root, "gateway.json"))
        val manager = TelegramManager(c, { api }, AndroidTelegramAgentBridge(c, runs), setup, store)
        c.telegram = manager
        val notifications = c.appContext.getSystemService(NotificationManager::class.java)
        suspend fun await(predicate: () -> Boolean) {
            try { withTimeout(30000) { while (!predicate()) delay(100) } }
            catch (e: TimeoutCancellationException) {
                throw AssertionError("Condition timed out: calls=${count.get()}, requests=${store.state.value.requests}, " +
                    "runtime=${manager.runtime.value}, error=${manager.error.value}, native=${runs.controls.flow(sid).value.status}, " +
                    "enabled=${setup.state.value.enabled}, notifications=${notifications.activeNotifications.map { it.id }}, " +
                    "outbox=${api.sent.map { it.text.take(160) }}", e)
            }
        }
        suspend fun button(label: String): Pair<TelegramDeviceMessage, TelegramButton> {
            await { api.sent.any { it.buttons.flatten().any { b -> b.text == label } } }
            val message = api.sent.last { it.buttons.flatten().any { b -> b.text == label } }
            return message to message.buttons.flatten().first { it.text == label }
        }
        try {
            c.settings.restorePortable(prefs.copy(planningModelsEnabled = false, sameModelAcrossChats = true,
                activeProviderId = config.id, activeModel = "fixture", maxIterations = 8))
            c.settings.setPermissionMode(PermissionMode.CONFIRM_ALL)
            setup.connect("123456:abcdefghijklmnopqrstuvwxyzABCDE")
            val challenge = setup.beginPairing()
            setup.confirmPairing(TelegramPairingCandidate(challenge, TelegramAccount(42, 42, "Fixture account")))
            setup.selectDestination(a.id, sid)
            instrumentation.uiAutomation.executeShellCommand("pm grant ${c.appContext.packageName} android.permission.POST_NOTIFICATIONS").close()
            ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                manager.enable()
                api.message(firstId, "Edit this project, then ask me which label to use")
                await { notifications.activeNotifications.any { it.id == TelegramService.ID } }
                scenario.onActivity { it.moveTaskToBack(true) }
                await { scenario.state == Lifecycle.State.CREATED }
                val first = button("Approve once")
                val live = runs.live(sid).value
                val fingerprint = RemotePromptIdentity.approval(requireNotNull(live.pendingApproval))
                assertFalse(File(aRoot, "approved.txt").exists())
                assertEquals(b.id, c.workspace.currentProjectOnce().id)
                api.click(firstId + 1, first.first, first.second)
                await { runs.live(sid).value.pendingApproval?.call?.argumentsJson?.contains("denied.txt") == true }
                assertFalse(runs.respondToRemoteApproval(sid, requireNotNull(live.turnId), "reused-id", true, fingerprint))
                await { api.sent.count { it.buttons.flatten().any { b -> b.text == "Deny" } } >= 2 }
                val denied = button("Deny")
                api.click(firstId + 2, denied.first, denied.second)
                val question = button("Alpha")
                runs.inject(sid, "Phone follow-up queued during the Telegram task")
                api.click(firstId + 3, question.first, question.second)
                await { store.request(firstId)?.status == TelegramRequestStatus.COMPLETED }
                await { api.sent.any { it.text.contains("Finished the approved edit with Alpha") } }
                assertEquals("approved change\n", File(aRoot, "approved.txt").readText())
                assertFalse(File(aRoot, "denied.txt").exists())
                assertFalse(File(bRoot, "approved.txt").exists())
                assertTrue(c.checkpoints.turnsWithCheckpoints(sid).isNotEmpty())
                api.message(firstId + 4, "What did we just edit?")
                await { store.request(firstId + 4)?.status == TelegramRequestStatus.COMPLETED }
                assertEquals(sid, store.request(firstId + 4)?.sessionId)
                assertTrue(synchronized(histories) { histories.last().any { it.text.contains("Finished the approved edit with Alpha") } })
                assertEquals(3, c.sessions.messages(sid).count { it.role == Role.USER })
                // The visible notification provides Pause and Stop even with no activity resumed.
                val notification = notifications.activeNotifications.first { it.id == TelegramService.ID }.notification
                assertEquals(listOf("Pause", "Stop task"), notification.actions.map { it.title.toString() })
                notification.actions.first().actionIntent.send()
                await { !setup.state.value.enabled && notifications.activeNotifications.none { it.id == TelegramService.ID } }
            }
        } finally {
            manager.stopAndPause(); runs.stopAllAndJoin()
            c.telegram = original
            c.sessions.session(sid)?.let { c.sessions.deleteSession(it) }
            c.workspace.deleteProject(a); c.workspace.deleteProject(b); c.workspace.setActiveProject(previous.id)
            c.providers.delete(config.id)
            c.settings.restorePortable(prefs); c.settings.setPermissionMode(prefs.permissionMode)
            root.deleteRecursively()
        }
    }
}

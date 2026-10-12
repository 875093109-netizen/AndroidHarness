package com.androidharness.app.ui.settings

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.agent.PermissionMode
import com.androidharness.app.llm.*
import com.androidharness.app.telegram.*
import com.androidharness.app.ui.chat.ChatListTestActivity
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in real Kilo inference, native project tools, foreground service and Telegram delivery.
 * Controlled incoming requests avoid consuming or acknowledging the owner's actual bot inbox.
 * Uses the already encrypted, phone-confirmed connection; no token in test sources or arguments. */
@RunWith(AndroidJUnit4::class)
class TelegramLiveSetupDeviceTest {
    @Test fun kiloModelsEditRealProjectAndDeliverThroughOfficialGateway() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveTelegramKilo") == "true")
        val c = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HarnessApp).container
        val original = c.telegram
        val connection = requireNotNull(original.setup.gatewayConnection()) { "Pair the test bot on the phone first." }
        original.pause()
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        var credentials: String? = json.encodeToString(TelegramConnection.serializer(), connection.copy(enabled = false))
        val incoming = Channel<List<TelegramUpdate>>(Channel.UNLIMITED)
        val real = TelegramBotClient(connection.token)
        val transport = object : TelegramBotApi by real {
            override suspend fun getUpdates(offset: Long) = incoming.receive().filter { it.id >= offset }
        }
        val setup = TelegramSetupRepository({ credentials }, { credentials = it }, { transport })
        val root = File(c.appContext.cacheDir, "telegram-live-${System.nanoTime()}").apply { mkdirs() }
        val project = c.workspace.addShellProject(root.path, activate = false)
        val previous = c.settings.settings.first()
        val sessions = mutableListOf<String>()
        val store = TelegramGatewayStore(File(root, "gateway.json"))
        val manager = TelegramManager(c, { transport }, AndroidTelegramAgentBridge(c), setup, store)
        c.telegram = manager
        try {
            val catalog = ModelCatalog.listModels(HarnessProvider.config, c.providers.harnessApiKey())
            assertTrue("Live Kilo catalog must be available", catalog is ModelCatalog.Result.Models)
            // Harness's Kilo catalog assigns this note only after checking wire isFree == true.
            val free = (catalog as ModelCatalog.Result.Models).models.filter { it.note == HarnessProvider.KILO_NOTE }.map { it.id }.toSet()
            val models = InstrumentationRegistry.getArguments().getString("kiloModels")?.split(',')
                ?: listOf("kilo-auto/free", "inclusionai/ling-3.1-flash")
            assertTrue("Only catalog-confirmed free models are tested", models.all { it in free })
            c.settings.restorePortable(previous.copy(sameModelAcrossChats = true, planningModelsEnabled = false,
                activeProviderId = HarnessProvider.ID, maxIterations = 12, maxOutputTokens = 4096))
            c.settings.setPermissionMode(PermissionMode.FULL_AUTO)
            models.forEachIndexed { index, model ->
                ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
                    manager.pause()
                    val sid = manager.bridge.newChat(project.id, "Telegram live $model").also { sessions += it }
                    setup.selectDestination(project.id, sid)
                    c.settings.setActiveSelection(HarnessProvider.ID, model)
                    File(root, "input-$index.txt").writeText("HARNESS_TELEGRAM_OK_$index")
                    manager.enable()
                    scenario.onActivity { it.moveTaskToBack(true) }
                    val updateId = connection.offset + index + 1
                    incoming.send(listOf(TelegramUpdate(updateId,
                        TelegramAccount(requireNotNull(connection.ownerId), requireNotNull(connection.ownerChatId), "Test owner"),
                        "AndroidHarness authorized integration test. Read input-$index.txt with your file tool, then write output-$index.txt with exactly the same text. Reply with the text you read and confirm the file was written. Do not run shell commands or change other files.")))
                    withTimeout(180000) { while (store.request(updateId)?.status !in listOf(TelegramRequestStatus.COMPLETED,
                        TelegramRequestStatus.FAILED, TelegramRequestStatus.INTERRUPTED)) delay(250) }
                    val request = requireNotNull(store.request(updateId))
                    assertEquals(request.error, TelegramRequestStatus.COMPLETED, request.status)
                    assertEquals("HARNESS_TELEGRAM_OK_$index", File(root, "output-$index.txt").readText().trim())
                    withTimeout(45000) { while (store.state.value.deliveries.none {
                        it.key.startsWith("final-$updateId-") && it.status == TelegramDeliveryStatus.SENT }) delay(200) }
                    assertTrue(c.sessions.messages(sid).any { it.toolName == "write_file" })
                    InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
                        putString("stream", "Real Kilo project editing and Telegram delivery passed: $model\n") })
                }
            }
        } finally {
            manager.stopAndPause()
            withTimeout(10000) { while (c.appContext.getSystemService(android.app.NotificationManager::class.java)
                .activeNotifications.any { it.id == TelegramService.ID }) delay(100) }
            c.telegram = original
            sessions.forEach { sid -> c.sessions.session(sid)?.let { c.sessions.deleteSession(it) } }
            c.workspace.deleteProject(project)
            c.settings.restorePortable(previous); c.settings.setPermissionMode(previous.permissionMode)
            root.deleteRecursively()
        }
    }
}

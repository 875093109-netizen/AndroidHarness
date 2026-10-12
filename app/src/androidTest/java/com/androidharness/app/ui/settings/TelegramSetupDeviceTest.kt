package com.androidharness.app.ui.settings

import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.telegram.*
import com.androidharness.app.ui.chat.ChatListTestActivity
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import com.androidharness.app.llm.ModelEntry
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the real setup UI with a fixture transport; never contacts Telegram. */
@RunWith(AndroidJUnit4::class)
class TelegramSetupDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun foregroundKiloListenerStopsWhenTheAppIsBackgrounded() {
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val api = object : TelegramBotApi {
            override suspend fun getMe() = TelegramBotIdentity(123456, "fixture_bot")
            override suspend fun getUpdates(offset: Long): List<TelegramUpdate> {
                started.complete(Unit)
                try { awaitCancellation() } finally { stopped.complete(Unit) }
            }
            override suspend fun sendMessage(chatId: Long, text: String) { assertEquals(42L, chatId) }
        }
        val saved = Json.encodeToString(TelegramConnection.serializer(), TelegramConnection(
            "123456:abcdefghijklmnopqrstuvwx", 123456, "fixture_bot", 42, 42, "Fixture account"))
        val repository = TelegramSetupRepository({ saved }, {}, { api })
        val tester = TelegramKiloReplyTest({ listOf(ModelEntry("kilo-auto/free")) }, { error("No question was supplied; no model should be called") })
        launch(repository, 1.5f, tester).use { scenario ->
            click("Listen for one question")
            awaitCondition { started.isCompleted }
            screenshot("telegram-kilo-listening-large-text.png")
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            awaitCondition { stopped.isCompleted }
        }
    }

    @Test fun telegramSearchOpensProductionSetupPage() {
        val container = (instrumentation.targetContext.applicationContext as HarnessApp).container
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { MaterialTheme { SettingsScreen(container, onBack = {}) } } }
            val search = await { it.isEditable }
            assertTrue(search.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "telegram")
            }))
            click("Telegram")
            await { it.text?.toString() == "Telegram remote control" }
            screenshot("telegram-settings-entry.png")
        }
    }

    @Test fun setupRetryPairingConfirmationDestinationAndTestDelivery() {
        var saved: String? = null
        var rejected = true
        val updates = CompletableDeferred<List<TelegramUpdate>>()
        val deliveries = mutableListOf<Long>()
        val api = object : TelegramBotApi {
            override suspend fun getMe(): TelegramBotIdentity {
                if (rejected) throw TelegramSetupException(TelegramSetupProblem.TOKEN_REJECTED)
                return TelegramBotIdentity(123456, "fixture_bot")
            }
            override suspend fun getUpdates(offset: Long) = updates.await()
            override suspend fun sendMessage(chatId: Long, text: String) { deliveries += chatId }
        }
        val repository = TelegramSetupRepository({ saved }, { saved = it }, { api })
        launch(repository, 1f).use {
            click("Connect bot")
            val field = await { it.isEditable }
            assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "123456:abcdefghijklmnopqrstuvwx")
            }))
            click("Verify bot")
            await { it.text?.toString()?.contains("Telegram rejected this token") == true }
            assertNull(repository.state.value.botUsername)
            rejected = false
            click("Verify bot")
            await { it.text?.toString()?.contains("@fixture_bot") == true }
            click("Pair account")
            val code = await { it.text?.toString()?.startsWith("/pair ") == true }.text.toString()
            updates.complete(listOf(TelegramUpdate(5, TelegramAccount(42, 42, "Fixture account"), code)))
            await { it.text?.toString() == "Pair this account?" }
            assertNull(repository.state.value.ownerId)
            click("Pair account")
            await { it.text?.toString()?.contains("Fixture account · Paired") == true }
            click("Choose project")
            click("Demo project")
            awaitCondition { repository.state.value.projectId == "project-a" }
            click("Send test message")
            awaitCondition { deliveries == listOf(42L) }
            click("Preview command flow")
            await { it.text?.toString()?.contains("nothing is sent or executed") == true }
            screenshot("telegram-command-preview.png")
            click("Done")
        }
        launch(repository, 1.5f).use {
            await { it.text?.toString()?.contains("Telegram remote control") == true }
            screenshot("telegram-setup-large-text.png")
            click("Preview command flow")
            click("Done")
        }
    }

    private fun launch(repository: TelegramSetupRepository, fontScale: Float, kiloTester: TelegramKiloReplyTest? = null): ActivityScenario<ChatListTestActivity> {
        val scenario = ActivityScenario.launch(ChatListTestActivity::class.java)
        scenario.onActivity { activity -> activity.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MaterialTheme {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.widthIn(max = 360.dp).fillMaxHeight().statusBarsPadding().navigationBarsPadding()
                            .verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            TelegramSetupContent(repository, listOf("project-a" to "Demo project", "project-b" to "Other project"),
                                listOf(TelegramChatChoice("chat-a", "project-a", "Existing chat")), activity, kiloTester)
                        }
                    }
                }
            }
        } }
        return scenario
    }

    private fun await(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        var result: AccessibilityNodeInfo? = null
        awaitCondition { result = instrumentation.uiAutomation.rootInActiveWindow?.let { find(it, predicate) }; result != null }
        return requireNotNull(result)
    }
    private fun find(node: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (predicate(node)) return node
        for (i in 0 until node.childCount) node.getChild(i)?.let { find(it, predicate)?.let { result -> return result } }
        return null
    }
    private fun awaitCondition(block: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) { if (block()) return; SystemClock.sleep(50) }
        error("Telegram setup did not reach the expected state")
    }
    private fun click(text: String) {
        repeat(10) {
            val node = instrumentation.uiAutomation.rootInActiveWindow?.let { root -> find(root) {
                it.text?.toString() == text || it.contentDescription?.toString() == text
            } }
            if (node != null && node.isVisibleToUser) {
                var target: AccessibilityNodeInfo = node
                while (!target.isClickable && target.parent != null) target = target.parent
                if (!target.isEnabled) { SystemClock.sleep(150); return@repeat }
                assertTrue("Cannot click $text", target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                SystemClock.sleep(150)
                return
            }
            val metrics = instrumentation.targetContext.resources.displayMetrics
            val x = metrics.widthPixels / 2
            val start = (metrics.heightPixels * .8f).toInt()
            val end = (metrics.heightPixels * .3f).toInt()
            val fd = instrumentation.uiAutomation.executeShellCommand("input swipe $x $start $x $end 300")
            fd.close()
            SystemClock.sleep(350)
        }
        error("Telegram control unavailable: $text")
    }
    private fun screenshot(name: String) {
        SystemClock.sleep(400)
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.cacheDir, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
}

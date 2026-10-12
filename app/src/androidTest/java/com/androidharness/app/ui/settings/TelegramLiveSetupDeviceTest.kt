package com.androidharness.app.ui.settings

import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.telegram.TelegramSetupRepository
import com.androidharness.app.ui.chat.ChatListTestActivity
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in live setup. Supply a temporary token file in the target app's private cache.
 * The token is consumed and deleted before opening the real production settings UI.
 * Only the person holding the displayed pairing code can receive the setup message.
 */
@RunWith(AndroidJUnit4::class)
class TelegramLiveSetupDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun kiloSamplesReachPairedAccount() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveTelegramKilo") == "true")
        val context = instrumentation.targetContext
        val container = (context.applicationContext as HarnessApp).container
        val setup = TelegramSetupRepository(container.keys::telegramConnection, container.keys::putTelegramConnection)
        assertNotNull("Pair an account before this live test", setup.state.value.ownerId)
        container.pendingSettingsScroll.value = "telegram"
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { MaterialTheme { SettingsScreen(container, onBack = {}) } } }
            await { it.text?.toString() == "Telegram remote control" }
            var selected = "kilo-auto/free"
            val models = InstrumentationRegistry.getArguments().getString("kiloModels")?.split(',')
                ?: listOf(selected, "inclusionai/ling-3.1-flash")
            models.forEach { model ->
                if (model != selected) { scrollToTop(); click(selected); click(model); selected = model }
                status("Requesting live Kilo sample: $model")
                click("Send Kilo sample")
                scrollToTop()
                await(125_000) { it.text?.toString() == "Kilo reply sent. Check your Telegram chat." }
                repeat(5) { swipe(false) }
                val answer = await { it.text?.toString()?.contains("391") == true }.text.toString()
                status("Live Kilo model and Telegram delivery passed: $model. Answer: $answer")
                screenshot("telegram-kilo-${if (model == "kilo-auto/free") "auto" else "ling"}.png")
            }
        }
    }

    @Test fun kiloQuestionFromPairedAccountGetsModelReply() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveTelegramKiloQuestion") == "true")
        val container = (instrumentation.targetContext.applicationContext as HarnessApp).container
        assertNotNull(TelegramSetupRepository(container.keys::telegramConnection, container.keys::putTelegramConnection).state.value.ownerId)
        container.pendingSettingsScroll.value = "telegram"
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { MaterialTheme { SettingsScreen(container, onBack = {}) } } }
            await { it.text?.toString() == "Telegram remote control" }
            click("Listen for one question")
            scrollToTop()
            await { it.text?.toString()?.startsWith("Listening for /ask") == true }
            status("Live receiver is listening. Send /ask followed by one question to your paired bot within 10 minutes.")
            await(700_000) { it.text?.toString() == "Kilo reply sent. Check your Telegram chat." }
            repeat(5) { swipe(false) }
            status("Real Telegram question, Kilo inference and Telegram reply passed.")
            screenshot("telegram-kilo-live-question.png")
        }
    }

    private fun swipe(toTop: Boolean) {
        val metrics = instrumentation.targetContext.resources.displayMetrics
        val start = (metrics.heightPixels * if (toTop) .3f else .8f).toInt()
        val end = (metrics.heightPixels * if (toTop) .8f else .3f).toInt()
        instrumentation.uiAutomation.executeShellCommand("input swipe ${metrics.widthPixels / 2} $start ${metrics.widthPixels / 2} $end 300").close()
        SystemClock.sleep(350)
    }

    private fun scrollToTop() { repeat(5) { swipe(true) } }

    private fun screenshot(name: String) {
        SystemClock.sleep(400)
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.cacheDir, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    @Test fun verifyPairAndSendSetupMessage() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveTelegramSetup") == "true")
        val context = instrumentation.targetContext
        val credential = File(context.cacheDir, "telegram-live-token")
        assumeTrue(credential.isFile)
        val token = try { credential.readText().trim() } finally { check(credential.delete()) }
        val container = (context.applicationContext as HarnessApp).container
        container.pendingSettingsScroll.value = "telegram"
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { MaterialTheme { SettingsScreen(container, onBack = {}) } } }
            await { it.text?.toString() == "Telegram remote control" }
            val connected = TelegramSetupRepository(container.keys::telegramConnection, container.keys::putTelegramConnection)
                .state.value.botUsername != null
            click(if (connected) "Change token" else "Connect bot")
            val field = await { it.isEditable }
            assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, token)
            }))
            click("Verify bot")
            await(45_000) { it.text?.toString()?.contains("Bot verified") == true }
            val saved = TelegramSetupRepository(container.keys::telegramConnection, container.keys::putTelegramConnection)
            assertNotNull(saved.state.value.botUsername)
            status("Live bot verification and encrypted storage passed.")
            click(if (saved.state.value.ownerId == null) "Pair account" else "Pair again")
            val command = await { it.text?.toString()?.startsWith("/pair ") == true }.text.toString()
            status("Send this command to your bot: $command")
            await(125_000) { it.text?.toString() == "Pair this account?" }
            // The user authorized this test and proves control of their Telegram account by sending the code.
            click("Pair account")
            await { it.text?.toString()?.endsWith("· Paired") == true }
            click("Send test message")
            // The confirmation lives in the first panel, above the send button.
            val metrics = context.resources.displayMetrics
            repeat(3) {
                instrumentation.uiAutomation.executeShellCommand("input swipe ${metrics.widthPixels / 2} ${(metrics.heightPixels * .3f).toInt()} ${metrics.widthPixels / 2} ${(metrics.heightPixels * .8f).toInt()} 300").close()
                SystemClock.sleep(350)
            }
            await(45_000) { it.text?.toString() == "Test message sent. Check your Telegram chat." }
            val restored = TelegramSetupRepository(container.keys::telegramConnection, container.keys::putTelegramConnection)
            assertNotNull(restored.state.value.ownerId)
            status("Live pairing, encrypted owner persistence and Telegram test delivery passed.")
            instrumentation.waitForIdleSync()
            SystemClock.sleep(400)
            val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            File(context.cacheDir, "telegram-live-setup.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
    }

    private fun status(message: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$message\n") })

    private fun find(node: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (predicate(node)) return node
        for (i in 0 until node.childCount) node.getChild(i)?.let { find(it, predicate)?.let { match -> return match } }
        return null
    }

    private fun await(timeout: Long = 15_000, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.uiAutomation.rootInActiveWindow?.let { find(it, predicate) }?.let { return it }
            SystemClock.sleep(100)
        }
        error("Live Telegram setup did not reach the expected state")
    }

    private fun click(text: String) {
        repeat(20) {
            val node = instrumentation.uiAutomation.rootInActiveWindow?.let { root -> find(root) {
                (it.text?.toString() == text || it.contentDescription?.toString() == text) &&
                    it.isVisibleToUser && clickableParent(it) != null
            } }
            if (node != null && node.isVisibleToUser) {
                val target = requireNotNull(clickableParent(node))
                if (!target.isEnabled) { SystemClock.sleep(200); return@repeat }
                assertTrue("Cannot activate Telegram setup control", target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                SystemClock.sleep(200)
                return
            }
            val metrics = instrumentation.targetContext.resources.displayMetrics
            instrumentation.uiAutomation.executeShellCommand("input swipe ${metrics.widthPixels / 2} ${(metrics.heightPixels * .8f).toInt()} ${metrics.widthPixels / 2} ${(metrics.heightPixels * .3f).toInt()} 300").close()
            SystemClock.sleep(350)
        }
        error("Telegram setup control unavailable")
    }

    private fun clickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var candidate: AccessibilityNodeInfo? = node
        while (candidate != null) {
            if (candidate.isClickable) return candidate
            candidate = candidate.parent
        }
        return null
    }
}

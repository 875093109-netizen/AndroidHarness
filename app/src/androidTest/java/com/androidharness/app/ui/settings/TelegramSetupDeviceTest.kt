package com.androidharness.app.ui.settings

import android.Manifest
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.data.*
import com.androidharness.app.llm.*
import com.androidharness.app.telegram.*
import com.androidharness.app.ui.chat.ChatListTestActivity
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real production controls with isolated credentials and fixture transport; never sends to Telegram. */
@RunWith(AndroidJUnit4::class)
class TelegramSetupDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val container get() = (instrumentation.targetContext.applicationContext as HarnessApp).container

    @Test fun telegramSearchOpensOfficialSettingsPage() {
        ActivityScenario.launch(ChatListTestActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.setContent { MaterialTheme { SettingsScreen(container, onBack = {}) } } }
            setText("telegram")
            click("Telegram")
            await { it.text?.toString() == "Remote control" }
            await { it.contentDescription?.toString() == "Enable Telegram" }
            assertNull(find(instrumentation.uiAutomation.rootInActiveWindow) { it.text?.toString() == "Preview" })
            screenshot("telegram-official-settings.png")
        }
    }

    @Test fun verificationRetryVisibilityDeepLinkPairingDestinationModelAndControls() {
        val fixture = TelegramUiFixture(container)
        fixture.api.rejected = true
        try {
            launch(fixture, 1f).use {
                click("Connect bot")
                setText("123456:abcdefghijklmnopqrstuvwx")
                click("Show bot token")
                await { it.contentDescription?.toString() == "Hide bot token" }
                click("Hide bot token")
                click("Verify bot")
                await { it.text?.toString()?.contains("Telegram rejected this token") == true }
                assertNull(fixture.setup.state.value.botUsername)
                fixture.api.rejected = false
                click("Verify bot")
                await { it.text?.toString()?.contains("@fixture_bot") == true }
                click("Pair account")
                await { it.text?.toString()?.startsWith("/pair ") == true }
                val challenge = requireNotNull(fixture.manager.pairing.value.challenge)
                assertEquals(16, challenge.code.length)
                await { it.text?.toString() == "Open bot and pair" }
                runBlocking { fixture.api.message(1, "/start pair_${challenge.code}") }
                await { it.text?.toString() == "Pair this account?" }
                assertNull(fixture.setup.state.value.ownerId)
                click("Pair account")
                await { it.text?.toString()?.contains("Fixture account · Paired") == true }
                click("Choose project"); click("Demo project")
                until { fixture.setup.state.value.projectId == "project-a" }
                click("New Telegram chat"); click("Existing chat")
                until { fixture.setup.state.value.sessionId == "chat-a" }
                click("kilo-auto/free"); click("inclusionai/ling-3.1-flash")
                until { fixture.bridge.selectedModel.value == "inclusionai/ling-3.1-flash" }
                click("More options", toTop = true)
                click("Send test message")
                until { fixture.api.sent.any { it.text.contains("paired account are connected") } }
                click("Help & commands")
                await { it.text?.toString()?.contains("/stop") == true }
                click("Done")
                click("More options", toTop = true)
                await { it.text?.toString() == "Change token" }
                await { it.text?.toString() == "Pair again" }
                click("Change token")
                click("Cancel")
                screenshot("telegram-settings-paired.png")
            }
            launch(fixture, 1.5f, dark = true).use {
                await { it.text?.toString() == "Remote control" }
                screenshot("telegram-official-large-text.png")
                click("Help & commands")
                screenshot("telegram-official-commands.png")
                click("Done")
            }
            assertEquals("chat-a", TelegramSetupRepository({ fixture.credentials }, {}).state.value.sessionId)
        } finally { runBlocking { fixture.manager.stopAndPause() } }
    }

    @Test fun leavingTheSettingsScreenDoesNotCancelEnabledTelegram() {
        val fixture = TelegramUiFixture(container)
        runBlocking {
            fixture.setup.connect("123456:abcdefghijklmnopqrstuvwx")
            val challenge = fixture.setup.beginPairing()
            fixture.api.message(1, "/pair ${challenge.code}")
            fixture.setup.confirmPairing(fixture.setup.awaitPairing(challenge))
            fixture.manager.selectDestination("project-a", "chat-a")
        }
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, Manifest.permission.POST_NOTIFICATIONS)
        try {
            launch(fixture, 1f).use { scenario ->
                click("Enable Telegram")
                until { fixture.setup.state.value.enabled && fixture.manager.runtime.value.running }
                runBlocking { fixture.api.incoming.send(emptyList()) }
                until { fixture.manager.runtime.value.connected }
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                assertTrue(fixture.setup.state.value.enabled)
                assertTrue(fixture.manager.runtime.value.running)
            }
            assertTrue(fixture.manager.runtime.value.running)
            runBlocking { fixture.manager.pause() }
            assertFalse(fixture.manager.runtime.value.running)
            assertFalse(fixture.setup.state.value.enabled)
        } finally { runBlocking { fixture.manager.stopAndPause() } }
    }

    private fun launch(fixture: TelegramUiFixture, fontScale: Float, dark: Boolean = false): ActivityScenario<ChatListTestActivity> {
        val scenario = ActivityScenario.launch(ChatListTestActivity::class.java)
        scenario.onActivity { activity -> activity.setContent {
            val density = LocalDensity.current
            val model by fixture.bridge.selectedModel.collectAsState()
            val state = ChatModelState(AppSettings(sameModelAcrossChats = false),
                listOf(ProviderConfig("fixture", "Fixture provider", ProviderType.OPENAI_COMPAT, "http://127.0.0.1", model)),
                ChatModelSelection(active = ChatModelChoice("fixture", model)))
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) { Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxHeight().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        TelegramSettingsContent(fixture.manager, listOf(TelegramChoiceItem("project-a", "Demo project"), TelegramChoiceItem("project-b", "Other project")),
                            listOf(TelegramChoiceItem("chat-a", "Existing chat")), state)
                    }
                } }
            }
        } }
        return scenario
    }
    private fun setText(text: String) {
        val field = await { it.isEditable }
        assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }))
    }
    private fun await(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        var result: AccessibilityNodeInfo? = null
        until { result = find(instrumentation.uiAutomation.rootInActiveWindow, predicate); result != null }
        return requireNotNull(result)
    }
    private fun find(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        node ?: return null
        if (predicate(node)) return node
        for (i in 0 until node.childCount) find(node.getChild(i), predicate)?.let { return it }
        return null
    }
    private fun until(predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) { if (predicate()) return; SystemClock.sleep(100) }
        error("Telegram controls did not reach the expected state")
    }
    private fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        while (current != null) { if (current.isClickable) return current; current = current.parent }
        return null
    }
    private fun click(text: String, toTop: Boolean = false) {
        repeat(16) {
            val node = find(instrumentation.uiAutomation.rootInActiveWindow) {
                (it.text?.toString() == text || it.contentDescription?.toString() == text) && it.isVisibleToUser && clickable(it) != null
            }
            if (node != null) {
                val target = requireNotNull(clickable(node))
                if (!target.isEnabled) { SystemClock.sleep(200); return@repeat }
                assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK)); SystemClock.sleep(200); return
            }
            val metrics = instrumentation.targetContext.resources.displayMetrics
            val start = if (toTop) .3f else .8f
            val end = if (toTop) .8f else .3f
            instrumentation.uiAutomation.executeShellCommand("input swipe ${metrics.widthPixels / 2} ${(metrics.heightPixels * start).toInt()} ${metrics.widthPixels / 2} ${(metrics.heightPixels * end).toInt()} 300").close()
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

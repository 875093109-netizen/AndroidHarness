package com.androidharness.app.ui.settings

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.agent.*
import com.androidharness.app.core.Role
import com.androidharness.app.llm.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.ServerSocket
import java.util.Collections
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class FallbackRunDeviceTest {
    private val c get() = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HarnessApp).container

    @Test fun quotaFallbackCompletesActualRunAndKeepsPrimarySelection() = fixture(exhausted = false)
    @Test fun exhaustedChainPausesAndKeepsQueuedWork() = fixture(exhausted = true)

    private fun fixture(exhausted: Boolean) = runBlocking {
        val saved = c.settings.settings.first()
        WireFixture(exhausted).use { wire ->
            val a = c.providers.add("Fallback wire primary", ProviderType.OPENAI_COMPAT, "http://127.0.0.1:${wire.port}/primary", "fixture-primary", "primary-fixture-key")
            val b = c.providers.add("Fallback wire backup", ProviderType.OPENAI_COMPAT, "http://127.0.0.1:${wire.port}/backup", "fixture-backup", "backup-fixture-key")
            c.providers.saveCatalog(a.id, listOf(ModelEntry(a.model))); c.providers.saveCatalog(b.id, listOf(ModelEntry(b.model)))
            val project = c.workspace.currentProjectOnce()
            val sid = c.sessions.createSession("Fallback wire test", project.id)
            try {
                c.settings.setActiveSelection(a.id, a.model)
                c.settings.setFallbackPolicy(FallbackPolicy(true, listOf(FallbackTarget(b.id, b.model)), retries = 0))
                if (exhausted) c.runManager.controls.update(sid) { it.copy(queue = listOf(QueuedPrompt("kept", "Review saved work later"))) }
                c.runManager.startRun(sid, "Reply with the fixture result", emptyList(), a, "primary-fixture-key", PermissionMode.CONFIRM_RISKY,
                    AgentMode.ACT, 1000, 100000, ThinkingLevel.OFF, 3, notifyOnFinish = false, allowModelFallback = false)
                val deadline = SystemClock.uptimeMillis() + 30000
                while (c.runManager.isRunning(sid) && SystemClock.uptimeMillis() < deadline) delay(100)
                assertFalse("Run did not finish: ${c.runManager.controls.flow(sid).value.reason}", c.runManager.isRunning(sid))
                val record = c.runManager.controls.flow(sid).value
                assertEquals(listOf("/primary/chat/completions", "/backup/chat/completions"), wire.paths.toList())
                assertEquals(listOf("Bearer primary-fixture-key", "Bearer backup-fixture-key"), wire.authorizations.toList())
                assertEquals(a.id, c.settings.settings.first().activeProviderId)
                assertEquals(a.model, c.settings.settings.first().activeModel)
                assertEquals(b.id, record.provider?.id)
                assertEquals(setOf(a.id, b.id), record.usedProviderIds)
                assertTrue(c.sessions.messages(sid).any { it.role == Role.SYSTEM && it.text.contains("Quota exhausted") })
                if (exhausted) {
                    assertFalse(record.completed); assertEquals("paused", record.status)
                    assertEquals("kept", record.queue.single().id)
                    assertTrue(record.reason.orEmpty().contains("No allowed fallback"))
                } else {
                    assertTrue("Run failed: ${record.reason}", record.completed)
                    assertTrue(c.sessions.messages(sid).any { it.role == Role.ASSISTANT && it.text == "Fixture completed" })
                }
            } finally {
                c.runManager.stopAndJoin(sid)
                c.sessions.session(sid)?.let { c.sessions.deleteSession(it) }
                File(c.appContext.filesDir, "task-controls/$sid.json").delete()
                c.providers.delete(a.id); c.providers.delete(b.id); c.settings.restorePortable(saved)
            }
        }
    }

    /** Loopback HTTP fixtures exercise the real factory, stream parser, engine and durable run state. */
    private class WireFixture(private val exhausted: Boolean) : AutoCloseable {
        private val server = ServerSocket(0)
        val port get() = server.localPort
        val paths = Collections.synchronizedList(mutableListOf<String>())
        val authorizations = Collections.synchronizedList(mutableListOf<String>())
        private val worker = thread(isDaemon = true, name = "fallback-wire-fixture") {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                socket.use {
                    it.soTimeout = 10000
                    val input = it.getInputStream()
                    val header = StringBuilder()
                    while (!header.endsWith("\r\n\r\n")) {
                        val next = input.read(); if (next < 0) break; header.append(next.toChar())
                    }
                    val lines = header.toString().split("\r\n")
                    val path = lines.first().split(" ")[1]
                    paths += path
                    authorizations += lines.firstOrNull { line -> line.startsWith("Authorization:", true) }?.substringAfter(':')?.trim().orEmpty()
                    val size = lines.firstOrNull { line -> line.startsWith("Content-Length:", true) }?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
                    input.readNBytes(size)
                    val quota = path.startsWith("/primary") || exhausted
                    val body = if (quota) """{"error":{"code":"insufficient_quota","message":"Fixture quota exhausted"}}"""
                        else "data: {\"choices\":[{\"delta\":{\"content\":\"Fixture completed\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                    val bytes = body.toByteArray()
                    val response = "HTTP/1.1 ${if (quota) "429 Too Many Requests" else "200 OK"}\r\nContent-Type: ${if (quota) "application/json" else "text/event-stream"}\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    it.getOutputStream().apply { write(response.toByteArray()); write(bytes); flush() }
                }
            }
        }
        override fun close() { server.close(); worker.join(1000) }
    }
}

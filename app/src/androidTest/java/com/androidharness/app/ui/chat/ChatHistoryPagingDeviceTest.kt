package com.androidharness.app.ui.chat

import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.androidharness.app.HarnessApp
import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.data.SessionRepository
import com.androidharness.app.data.db.AppDatabase
import com.androidharness.app.data.db.MessageEntity
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatHistoryPagingDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun equalTimestampsCompleteTurnsLiveAppendsSearchAndRewindKeepAllHistory() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(instrumentation.targetContext, AppDatabase::class.java).build()
        try {
            val repo = SessionRepository(db)
            val sid = repo.createSession("Paged history")
            suspend fun turn(index: Int) {
                listOf("USER", "ASSISTANT", "TOOL", "ASSISTANT").forEachIndexed { part, role ->
                    db.dao().insertMessage(MessageEntity(id = "$index-$part", sessionId = sid, role = role,
                        text = "turn-$index-part-$part", toolCallsJson = "[]", toolCallId = null,
                        toolName = null, isError = false, turnId = "turn-$index", createdAt = 1000))
                }
            }
            repeat(120) { turn(it) }
            val original = repo.messages(sid).map { it.id }
            val initial = repo.messageWindowStart(sid)
            var start = initial
            var window = repo.messageWindowFlow(sid, start).first()
            assertEquals(80, window.messages.size)
            assertEquals("100-0", window.messages.first().id)
            assertTrue(window.hasOlder)
            assertEquals(480, repo.historyFor(sid).second.size)
            turn(120)
            window = repo.messageWindowFlow(sid, start).first()
            assertEquals("100-0", window.messages.first().id)
            assertEquals("120-3", window.messages.last().id)
            assertEquals(84, window.messages.size)
            var pages = 0
            while (window.hasOlder) {
                assertTrue("paging did not terminate", pages++ < 8)
                start = repo.messageWindowStart(sid, before = start)
                window = repo.messageWindowFlow(sid, start).first()
                assertEquals(Role.USER, window.messages.first().role)
                assertEquals(window.messages.size, window.messages.map { it.id }.distinct().size)
                val ids = window.messages.dropLast(4).map { it.id }
                assertEquals(original.takeLast(ids.size), ids)
            }
            assertEquals(original + listOf("120-0", "120-1", "120-2", "120-3"), window.messages.map { it.id })
            val search = repo.messagePosition(sid, "0-3")!!
            assertEquals("0-0", repo.messageWindowFlow(sid, search).first().messages.first().id)
            assertNull(repo.messagePosition(sid, "missing"))
            db.dao().deleteMessagesFrom(sid, "100-0")
            val removed = repo.messageWindowFlow(sid, initial).first()
            assertTrue(removed.messages.isEmpty()); assertTrue(removed.hasOlder)
            val reset = repo.messageWindowFlow(sid, repo.messageWindowStart(sid)).first()
            assertEquals("80-0", reset.messages.first().id)
            assertEquals("99-3", reset.messages.last().id)
            assertEquals(400, repo.historyFor(sid).second.size)
        } finally { db.close() }
    }

    @Test fun viewModelLoadsRecentMessagesThenOlderAndSearchOnDemand() = runBlocking {
        val c = (instrumentation.targetContext.applicationContext as HarnessApp).container
        val sid = c.sessions.createProjectSession(c.workspace.currentProjectOnce().id)
        var vm: ChatViewModel? = null
        try {
            repeat(240) { c.sessions.addMessage(sid, ChatMessage(Role.USER, "saved-$it")) }
            instrumentation.runOnMainSync { vm = ChatViewModel(c, sid) }
            val model = vm!!
            val recent = withTimeout(10000) { model.state.first { !it.isLoadingMessages } }
            assertEquals(80, recent.messages.size)
            assertEquals("saved-160", recent.messages.first().text)
            assertTrue(recent.hasOlderMessages)
            instrumentation.runOnMainSync { model.loadOlderMessages() }
            val expanded = withTimeout(10000) { model.state.first { it.messages.size == 160 } }
            assertEquals("saved-80", expanded.messages.first().text)
            val target = c.sessions.messages(sid).first().id!!
            assertTrue(model.revealMessage(target))
            assertEquals(240, model.state.value.messages.size)
            assertEquals(240, c.sessions.historyFor(sid).second.size)
            assertFalse(model.revealMessage("missing"))
        } finally {
            instrumentation.runOnMainSync { vm?.viewModelScope?.cancel() }
            c.sessions.session(sid)?.let { c.sessions.deleteSession(it) }
        }
    }
}

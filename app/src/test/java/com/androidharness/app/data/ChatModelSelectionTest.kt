package com.androidharness.app.data

import com.androidharness.app.agent.ThinkingLevel
import com.androidharness.app.llm.ProviderConfig
import com.androidharness.app.llm.ProviderType
import org.junit.Assert.*
import org.junit.Test

class ChatModelSelectionTest {
    private val a = ProviderConfig("a", "A", ProviderType.OPENAI_COMPAT, "https://example.test/v1", "default-a")
    private val b = a.copy(id = "b", name = "B", model = "default-b")

    @Test fun `existing settings keep shared scope by default`() {
        assertTrue(AppSettings().sameModelAcrossChats)
    }

    @Test fun `snapshots resolve provider defaults and retain separate roles`() {
        val selection = ChatModelSelection.from(AppSettings(activeProviderId = a.id, activeModel = "picked-a",
            planningProviderId = b.id, executionModel = "exec-a", thinkingLevel = ThinkingLevel.HIGH), listOf(a, b))
        assertEquals(ChatModelChoice(a.id, "picked-a"), selection.active)
        assertEquals(ChatModelChoice(b.id, "default-b"), selection.planning)
        assertEquals(ChatModelChoice(a.id, "exec-a"), selection.execution)
        assertEquals(ThinkingLevel.HIGH, selection.thinking)
        // Capturing concrete IDs preserves the choice when provider defaults are edited later.
        assertEquals("default-b", selection.planning?.model)
    }

    @Test fun `choosing one role does not change the other roles`() {
        val initial = ChatModelSelection.from(AppSettings(activeProviderId = a.id), listOf(a, b))
        val selected = initial.choose(ChatModelTarget.PLANNING, ChatModelChoice(b.id, "plan-b"))
        assertEquals(initial.active, selected.active)
        assertEquals(initial.execution, selected.execution)
        assertEquals(ChatModelChoice(b.id, "plan-b"), selected.planning)
    }

    @Test fun `account switches cannot overwrite a newer model pick`() {
        val selection = ChatModelSelection(active = ChatModelChoice(a.id, "newer-model"),
            planning = ChatModelChoice(a.id, a.model), execution = ChatModelChoice(b.id, b.model))
        val changed = selection.switchIfMatching(a, b)
        assertEquals(selection.active, changed.active)
        assertEquals(ChatModelChoice(b.id, b.model), changed.planning)
        assertEquals(selection.execution, changed.execution)
    }

    @Test fun `saved choices round trip and accept future fields`() {
        val selection = ChatModelSelection(active = ChatModelChoice(a.id, "picked"), thinking = ThinkingLevel.MAX)
        assertEquals(selection, ChatModelSelection.decode(ChatModelSelection.encode(selection)))
        assertEquals(selection, ChatModelSelection.decode(ChatModelSelection.encode(selection).dropLast(1) + ",\"future\":true}"))
        assertNull(ChatModelSelection.decode(null))
    }

    @Test fun `unreadable choices never silently fall back to shared defaults`() {
        assertTrue(runCatching { ChatModelSelection.decode("broken") }.isFailure)
    }

    @Test fun `chat backups retain model choices while older backups remain readable`() {
        val selected = ChatModelSelection.encode(ChatModelSelection(active = ChatModelChoice(a.id, "picked")))
        val file = ChatBackupFile(sessions = listOf(BackupSession("a", "Chat", 1, 2, modelSelectionJson = selected)))
        assertEquals(selected, ChatBackupCodec.decode(ChatBackupCodec.encode(file)).sessions.single().modelSelectionJson)
        assertNull(ChatBackupCodec.decode("""{"sessions":[{"id":"a","title":"Chat","createdAt":1,"updatedAt":2}]}""").sessions.single().modelSelectionJson)
    }
}

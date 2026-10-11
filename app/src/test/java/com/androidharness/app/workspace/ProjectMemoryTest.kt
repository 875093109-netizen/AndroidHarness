package com.androidharness.app.workspace

import com.androidharness.app.agent.MemoryNotes
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectMemoryTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun staleEditorCannotReplaceAgentNotesAndOtherProjectsStaySeparate() {
        val a = FileFs(temp.newFolder("alpha"))
        val b = FileFs(temp.newFolder("beta"))
        ProjectMemory.save(a, "", "Alpha notes")
        ProjectMemory.save(b, "", "Beta notes")
        val baseline = ProjectMemory.read(a)
        ProjectMemory.save(a, baseline, "Newer notes")
        assertTrue(runCatching { ProjectMemory.save(a, baseline, "Stale draft") }.isFailure)
        assertEquals("Newer notes", ProjectMemory.read(a))
        assertEquals("Beta notes", ProjectMemory.read(b))
    }

    @Test fun editorRejectsOversizeInsteadOfSilentlyTruncatingAndClearIsExplicit() {
        val fs = FileFs(temp.newFolder())
        val notes = "Keep these notes\n"
        ProjectMemory.save(fs, "", notes)
        assertTrue(runCatching { ProjectMemory.save(fs, notes, "x".repeat(MemoryNotes.MAX_CHARS + 1)) }.isFailure)
        assertEquals(notes, ProjectMemory.read(fs))
        ProjectMemory.save(fs, notes, "")
        assertEquals("", ProjectMemory.read(fs))
    }
}

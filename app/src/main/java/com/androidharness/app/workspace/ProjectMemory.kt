package com.androidharness.app.workspace

import com.androidharness.app.agent.MemoryNotes

/** Raw editor contents, without the prompt loader's trimming or truncation. */
object ProjectMemory {
    private const val PATH = ".harness/memory.md"

    fun read(fs: WorkspaceFs): String = fs.resolve(PATH).let { if (it.exists) it.readText() else "" }

    /** Caller holds RunManager's workspace guard so agent writes cannot race this save. */
    fun save(fs: WorkspaceFs, expected: String, content: String) {
        require(content.length <= MemoryNotes.MAX_CHARS) { "Keep memory within ${MemoryNotes.MAX_CHARS} characters." }
        check(read(fs) == expected) { "Memory changed. Your draft is kept; reload the latest notes before saving." }
        fs.resolve(PATH).writeText(content)
    }
}

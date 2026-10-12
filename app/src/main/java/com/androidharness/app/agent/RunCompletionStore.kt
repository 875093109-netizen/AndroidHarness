package com.androidharness.app.agent

import java.io.File
import java.io.FileOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable data class RunCompletion(val promptId: String, val sessionId: String, val turnId: String,
    val completed: Boolean, val cancelled: Boolean, val error: String? = null)

/** A remote run's outcome remains identifiable after another turn starts in the same chat. */
class RunCompletionStore(private val directory: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private fun file(id: String): File {
        require(id.matches(Regex("[a-zA-Z0-9-]{1,200}")))
        return File(directory, "$id.json")
    }
    @Synchronized fun read(id: String): RunCompletion? = file(id).takeIf { it.exists() }?.let {
        runCatching { json.decodeFromString(RunCompletion.serializer(), it.readText()) }.getOrNull()
    }
    @Synchronized fun save(value: RunCompletion) {
        directory.mkdirs()
        val target = file(value.promptId)
        val temporary = File(directory, target.name + ".tmp")
        FileOutputStream(temporary).use { out -> out.write(json.encodeToString(RunCompletion.serializer(), value).toByteArray()); out.fd.sync() }
        check(temporary.renameTo(target)) { "Could not save the remote run outcome." }
    }
}

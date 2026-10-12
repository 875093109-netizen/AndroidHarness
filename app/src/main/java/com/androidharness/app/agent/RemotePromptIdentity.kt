package com.androidharness.app.agent

/** Bind a remote decision to the actual displayed operation, even if an upstream reuses call IDs. */
object RemotePromptIdentity {
    private fun hash(vararg parts: String): String {
        val text = parts.joinToString("") { "${it.length}:$it" }
        return java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    fun approval(request: ApprovalRequest) = hash(request.call.name, request.call.argumentsJson, request.toolDescription, request.diffPreview.orEmpty())
    fun question(request: QuestionRequest) = hash(request.question, request.options.joinToString("\u0000"), request.multiSelect.toString())
    fun environment(request: EnvironmentRequest) = hash(request.command, request.repair.toString(), request.missingTool.orEmpty())
}

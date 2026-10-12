package com.androidharness.app.agent

import com.androidharness.app.core.ToolCallData
import org.junit.Assert.*
import org.junit.Test

class RemotePromptIdentityTest {
    @Test fun reusedCallIdsCannotKeepAnApprovalWhenItsPathOrContentChanges() {
        fun request(path: String, content: String) = ApprovalRequest(ToolCallData("same-id", "write_file", """{"path":"$path","content":"$content"}"""), "Write file")
        val original = RemotePromptIdentity.approval(request("a.txt", "one"))
        assertEquals(original, RemotePromptIdentity.approval(request("a.txt", "one")))
        assertNotEquals(original, RemotePromptIdentity.approval(request("b.txt", "one")))
        assertNotEquals(original, RemotePromptIdentity.approval(request("a.txt", "two")))
    }
    @Test fun changingQuestionOptionsOrEnvironmentCommandsInvalidatesTheRemoteDecision() {
        assertNotEquals(RemotePromptIdentity.question(QuestionRequest("same-id", "Choose", listOf("one"))),
            RemotePromptIdentity.question(QuestionRequest("same-id", "Choose", listOf("two"))))
        fun environment(command: String) = EnvironmentRequest(ToolCallData("same-id", "shell", "{}"), command, emptyList())
        assertNotEquals(RemotePromptIdentity.environment(environment("node --version")), RemotePromptIdentity.environment(environment("python --version")))
    }
}

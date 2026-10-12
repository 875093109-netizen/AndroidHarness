package com.androidharness.app.telegram

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test

class TelegramGatewayTest {
    private class Bridge : TelegramAgentBridge {
        val starts = mutableListOf<TelegramRequest>()
        val decisions = mutableListOf<String>()
        val snapshots = mutableMapOf<Long, TelegramRunSnapshot>()
        var hold = false
        var pending: TelegramDecision? = null
        var model = "kilo-auto/free"
        var stopCount = 0
        var newChats = 0
        override suspend fun projects() = listOf(TelegramChoiceItem("project-a", "Project A"), TelegramChoiceItem("project-b", "Project B"))
        override suspend fun chats(projectId: String) = listOf(TelegramChoiceItem("chat-a", "Chat A"))
        override suspend fun providers() = listOf(TelegramChoiceItem("harness", "Harness"))
        override suspend fun models(providerId: String) = listOf(TelegramChoiceItem(model, model))
        override suspend fun target(projectId: String, sessionId: String?, mode: String) = TelegramTarget(projectId, projectId, sessionId ?: newChat(projectId), "Chat", "harness", "Harness", model)
        override suspend fun newChat(projectId: String, title: String) = "chat-${++newChats}"
        override suspend fun selectModel(sessionId: String, providerId: String, model: String, mode: String) { this.model = model }
        override suspend fun snapshot(request: TelegramRequest, binding: String) = snapshots[request.id] ?: TelegramRunSnapshot()
        override suspend fun start(request: TelegramRequest, binding: String) {
            starts += request
            snapshots[request.id] = TelegramRunSnapshot(running = hold, matches = true, started = true, completed = !hold,
                turnId = "turn-${request.id}", progress = if (pending != null) "Waiting for your approval" else "Working…",
                output = "Answer to: ${request.text}", decision = pending)
        }
        override suspend fun stop(sessionId: String) {
            stopCount++
            snapshots.replaceAll { _, snap -> snap.copy(running = false, cancelled = true, decision = null) }
        }
        override suspend fun isBusy(sessionId: String) = snapshots.values.any { it.running }
        override suspend fun decide(sessionId: String, turnId: String, callId: String, kind: String, value: String, fingerprint: String?): Boolean {
            val (id, snap) = snapshots.entries.firstOrNull { it.value.turnId == turnId } ?: return false
            if (!snap.running || snap.decision?.callId != callId || snap.decision.kind != kind) return false
            decisions += value
            snapshots[id] = snap.copy(running = false, completed = true, decision = null, output = "Approved task finished")
            return true
        }
    }
    private data class Published(val id: Long, val text: String, val buttons: List<List<TelegramButton>>)
    private class Api : TelegramBotApi {
        val incoming = Channel<List<TelegramUpdate>>(Channel.UNLIMITED)
        val messages = mutableListOf<Published>()
        val edits = mutableListOf<String>()
        val offsets = mutableListOf<Long>()
        var receiverFailure = false
        var uncertainSend = false
        var rateLimitOnce = false
        var publishes = 0
        override suspend fun getMe() = TelegramBotIdentity(1, "fixture_bot")
        override suspend fun getUpdates(offset: Long): List<TelegramUpdate> {
            offsets += offset
            if (receiverFailure) { receiverFailure = false; throw TelegramSetupException(TelegramSetupProblem.NETWORK) }
            return incoming.receive().filter { it.id >= offset }
        }
        override suspend fun sendMessage(chatId: Long, text: String) { publish(chatId, text) }
        override suspend fun publish(chatId: Long, text: String, buttons: List<List<TelegramButton>>): Long {
            assertEquals(42L, chatId)
            publishes++
            if (rateLimitOnce) { rateLimitOnce = false; throw TelegramSetupException(TelegramSetupProblem.RATE_LIMIT, 1) }
            val id = messages.size.toLong() + 1
            messages += Published(id, text, buttons)
            if (uncertainSend) { uncertainSend = false; throw TelegramSetupException(TelegramSetupProblem.NETWORK, deliveryUncertain = true) }
            return id
        }
        override suspend fun edit(chatId: Long, messageId: Long, text: String, buttons: List<List<TelegramButton>>) { assertEquals(42L, chatId); edits += text }
    }
    private class Fixture {
        var saved: String? = null
        var connection = TelegramConnection("123456:abcdefghijklmnopqrstuvwx", 1, "fixture_bot", ownerId = 42, ownerChatId = 42,
            ownerName = "Owner", projectId = "project-a", sessionId = "chat-a", enabled = true)
        val store = TelegramGatewayStore({ saved }, { saved = it })
        val bridge = Bridge()
        val api = Api()
        val gateway = TelegramGateway(store, { connection }, { p, s -> connection = connection.copy(projectId = p, sessionId = s) },
            { mode -> connection = connection.copy(mode = mode) }, { offset -> connection = connection.copy(offset = offset) },
            { connection = connection.copy(enabled = false) }, bridge, { api })
        fun message(id: Long, text: String, owner: Long = 42) = TelegramUpdate(id, TelegramAccount(owner, owner, "User"), text)
    }
    private suspend fun until(predicate: () -> Boolean) = withTimeout(10_000) { while (!predicate()) delay(20) }

    @Test fun continuousMessagesCaptureTheSameProjectAndChatAndIgnoreOtherUsers() = runBlocking {
        val f = Fixture()
        val job = launch { f.gateway.run() }
        try {
            f.api.incoming.send(listOf(f.message(1, "First message"), f.message(2, "Other person's message", 99)))
            until { f.store.request(1)?.status == TelegramRequestStatus.COMPLETED }
            f.api.incoming.send(listOf(f.message(3, "Continue our conversation")))
            until { f.store.request(3)?.status == TelegramRequestStatus.COMPLETED }
            assertEquals(listOf("First message", "Continue our conversation"), f.bridge.starts.map { it.text })
            assertTrue(f.bridge.starts.all { it.projectId == "project-a" && it.sessionId == "chat-a" })
            assertNull(f.store.request(2))
            assertEquals(4L, f.store.state.value.offset)
            val restored = TelegramGatewayStore({ f.saved }, {})
            assertEquals(4L, restored.state.value.offset)
            assertEquals(2, restored.state.value.requests.size)
        } finally { job.cancelAndJoin() }
    }

    @Test fun staleApprovalButtonsCannotApproveANewerToolOrAnotherAccount() = runBlocking {
        val f = Fixture()
        f.bridge.hold = true
        f.bridge.pending = TelegramDecision("approval", "call-one", "Edit file?", "write_file")
        val job = launch { f.gateway.run() }
        try {
            f.api.incoming.send(listOf(f.message(1, "Edit the file")))
            until { f.api.messages.any { it.buttons.isNotEmpty() } }
            val card = f.api.messages.first { it.buttons.isNotEmpty() }
            val callback = TelegramCallback("click", card.id, card.buttons.first().first().data)
            val old = f.bridge.snapshots.getValue(1)
            f.bridge.snapshots[1] = old.copy(decision = TelegramDecision("approval", "call-two", "Edit another file?", "write_file"))
            f.api.incoming.send(listOf(TelegramUpdate(2, TelegramAccount(99, 99, "Other"), null, callback = callback)))
            f.api.incoming.send(listOf(TelegramUpdate(3, TelegramAccount(42, 42, "Owner"), null, callback = callback)))
            until { f.store.request(3)?.status == TelegramRequestStatus.COMPLETED }
            assertTrue(f.bridge.decisions.isEmpty())
            assertNull(f.store.request(2))
            assertEquals("call-two", f.bridge.snapshots.getValue(1).decision?.callId)
        } finally { job.cancelAndJoin() }
    }

    @Test fun stopIsHandledWhileAnAgentIsWorkingAndCancelsItsQueuedRequests() = runBlocking {
        val f = Fixture()
        f.bridge.hold = true
        val job = launch { f.gateway.run() }
        try {
            f.api.incoming.send(listOf(f.message(1, "Long task")))
            until { f.store.request(1)?.status == TelegramRequestStatus.RUNNING }
            f.api.incoming.send(listOf(f.message(2, "Queued task"), f.message(3, "/stop")))
            until { f.store.request(3)?.status == TelegramRequestStatus.COMPLETED }
            assertEquals(1, f.bridge.stopCount)
            assertEquals(TelegramRequestStatus.CANCELLED, f.store.request(1)?.status)
            assertEquals(TelegramRequestStatus.CANCELLED, f.store.request(2)?.status)
            assertEquals(1, f.bridge.starts.size)
        } finally { job.cancelAndJoin() }
    }

    @Test fun restartingTheReceiverReattachesToWorkWithoutStartingTheAgentAgain() = runBlocking {
        val f = Fixture()
        f.bridge.hold = true
        var job = launch { f.gateway.run() }
        try {
            f.api.incoming.send(listOf(f.message(1, "Work")))
            until { f.store.request(1)?.status == TelegramRequestStatus.RUNNING }
            job.cancelAndJoin()
            assertTrue(f.bridge.snapshots.getValue(1).running)
            job = launch { f.gateway.run() }
            delay(200)
            f.bridge.snapshots[1] = f.bridge.snapshots.getValue(1).copy(running = false, completed = true, output = "Finished once")
            until { f.store.request(1)?.status == TelegramRequestStatus.COMPLETED }
            assertEquals(1, f.bridge.starts.size)
            assertEquals(1, f.store.state.value.deliveries.count { it.text.contains("Finished once") })
        } finally { job.cancelAndJoin() }
    }

    @Test fun interruptedWorkWaitsForExplicitResumeAndKeepsTheOriginalPromptIdentity() = runBlocking {
        val f = Fixture()
        f.store.bind(f.connection)
        val request = TelegramRequest(1, "Edit once", 1, status = TelegramRequestStatus.RUNNING, sessionId = "chat-a", projectId = "project-a",
            providerId = "harness", model = "kilo-auto/free", turnId = "turn-1")
        f.store.change { it.copy(requests = listOf(request)) }
        f.bridge.snapshots[1] = TelegramRunSnapshot(matches = true, started = true, interrupted = true, turnId = "turn-1", error = "HTTP 429: provider metadata and internal account details")
        val job = launch { f.gateway.run() }
        try {
            until { f.store.request(1)?.status == TelegramRequestStatus.INTERRUPTED }
            assertTrue(f.bridge.starts.isEmpty())
            assertTrue(f.store.request(1)!!.error!!.contains("rate limited"))
            assertFalse(f.store.request(1)!!.error!!.contains("metadata"))
            f.bridge.model = "inclusionai/ling-3.1-flash"
            f.gateway.retry(1)
            until { f.store.request(1)?.status == TelegramRequestStatus.COMPLETED }
            assertTrue(f.bridge.starts.single().resume)
            assertEquals("inclusionai/ling-3.1-flash", f.bridge.starts.single().model)
            assertEquals(request.promptId(f.store.state.value.binding), f.bridge.starts.single().promptId(f.store.state.value.binding))
        } finally { job.cancelAndJoin() }
    }

    @Test fun uncertainDeliveryIsNeverAutomaticallyRepeatedAndResendingDoesNotRunTheAgent() = runBlocking {
        val f = Fixture()
        f.store.bind(f.connection)
        f.store.enqueue(TelegramDelivery("saved-answer", "An already completed answer"))
        f.api.uncertainSend = true
        val job = launch { f.gateway.run() }
        try {
            until { f.store.state.value.deliveries.single().status == TelegramDeliveryStatus.UNCERTAIN }
            delay(300)
            assertEquals(1, f.api.publishes)
            f.gateway.resend("saved-answer")
            until { f.store.state.value.deliveries.single().status == TelegramDeliveryStatus.SENT }
            assertEquals(2, f.api.publishes)
            assertTrue(f.bridge.starts.isEmpty())
        } finally { job.cancelAndJoin() }
    }

    @Test fun rateLimitedDeliveryRetriesTheSavedReplyUsingTelegramsDelay() = runBlocking {
        val f = Fixture()
        f.store.bind(f.connection)
        f.store.enqueue(TelegramDelivery("saved-answer", "Answer"))
        f.api.rateLimitOnce = true
        val job = launch { f.gateway.run() }
        try {
            until { f.store.state.value.deliveries.single().status == TelegramDeliveryStatus.SENT }
            assertEquals(2, f.api.publishes)
            assertTrue(f.bridge.starts.isEmpty())
        } finally { job.cancelAndJoin() }
    }

    @Test fun resumeInTheSameProcessRecoversAnInterruptedSendWithoutRepeatingIt() = runBlocking {
        val f = Fixture()
        f.store.bind(f.connection)
        f.store.enqueue(TelegramDelivery("interrupted-send", "An answer", status = TelegramDeliveryStatus.SENDING))
        f.store.enqueue(TelegramDelivery("interrupted-edit", "Progress", messageId = 10, status = TelegramDeliveryStatus.SENDING))
        val job = launch { f.gateway.run() }
        try {
            until { f.store.state.value.deliveries.first { it.key == "interrupted-edit" }.status == TelegramDeliveryStatus.SENT }
            assertEquals(TelegramDeliveryStatus.UNCERTAIN, f.store.state.value.deliveries.first { it.key == "interrupted-send" }.status)
            assertEquals(0, f.api.publishes)
            assertEquals(listOf("Progress"), f.api.edits)
        } finally { job.cancelAndJoin() }
    }

    @Test fun helpButtonsChooseProjectsAndCannotControlADifferentChat() = runBlocking {
        val f = Fixture()
        val job = launch { f.gateway.run() }
        try {
            f.api.incoming.send(listOf(f.message(1, "/help")))
            until { f.api.messages.any { it.buttons.isNotEmpty() } }
            val help = f.api.messages.first { it.buttons.isNotEmpty() }
            val project = help.buttons.flatten().first { it.text == "Projects" }
            f.api.incoming.send(listOf(TelegramUpdate(2, TelegramAccount(42,42,"Owner"), null,
                callback = TelegramCallback("projects", help.id, project.data))))
            until { f.api.messages.any { it.buttons.flatten().any { it.text == "Project B" } } }
            val menu = f.api.messages.first { it.buttons.flatten().any { it.text == "Project B" } }
            f.api.incoming.send(listOf(TelegramUpdate(3, TelegramAccount(42,42,"Owner"), null,
                callback = TelegramCallback("select", menu.id, menu.buttons.flatten().first { it.text == "Project B" }.data))))
            until { f.connection.projectId == "project-b" }
            f.api.incoming.send(listOf(TelegramUpdate(4, TelegramAccount(42,42,"Owner"), null,
                callback = TelegramCallback("stale-stop", help.id, help.buttons.flatten().first { it.text == "Stop task" }.data))))
            until { f.store.request(4)?.status == TelegramRequestStatus.FAILED }
            assertEquals(0, f.bridge.stopCount)
        } finally { job.cancelAndJoin() }
    }

    @Test fun inboxCursorDoesNotAdvanceWhenTheDurableWriteFails() {
        var failWrite = false
        var saved: String? = null
        val f = Fixture()
        val store = TelegramGatewayStore({ saved }, { if (failWrite) error("disk full") else saved = it })
        store.bind(f.connection)
        failWrite = true
        assertThrows(IllegalStateException::class.java) { store.ingest(listOf(f.message(10, "Work")), 42, 1) }
        assertEquals(0, store.state.value.offset)
        assertTrue(store.state.value.requests.isEmpty())
        assertEquals(0, TelegramGatewayStore({ saved }, {}).state.value.offset)
    }

    @Test fun aFullBatchCannotOverflowTheQueueAndStillAllowsStop() {
        val f = Fixture()
        f.store.bind(f.connection)
        f.store.ingest((1L..102L).map { f.message(it, "/ask work $it") } + f.message(103, "/stop"), 42, 1)
        assertEquals(100, f.store.state.value.requests.count { it.status == TelegramRequestStatus.RECEIVED && it.text.startsWith("/ask") })
        assertEquals(TelegramRequestStatus.RECEIVED, f.store.request(103)?.status)
        assertEquals(2, f.store.state.value.deliveries.count { it.text.contains("Queue is full") })
        assertEquals(104L, f.store.state.value.offset)
    }

    @Test fun processDeathMarksAnUnacknowledgedSendAsUncertainAndPreservesFailures() {
        var saved: String? = null
        val store = TelegramGatewayStore({ saved }, { saved = it })
        store.change { it.copy(deliveries = listOf(TelegramDelivery("unknown", "Answer", status = TelegramDeliveryStatus.SENDING)),
            requests = listOf(TelegramRequest(1, "Request", 1, status = TelegramRequestStatus.FAILED, error = "Retry me"))) }
        val restored = TelegramGatewayStore({ saved }, {})
        assertEquals(TelegramDeliveryStatus.UNCERTAIN, restored.state.value.deliveries.single().status)
        assertEquals("Retry me", restored.request(1)?.error)
    }

    @Test fun pairingDeepLinkStillRequiresPhoneConfirmationAndHasTenMinuteExpiry() = runBlocking {
        var saved: String? = null
        val api = Api()
        val setup = TelegramSetupRepository({ saved }, { saved = it }, { api })
        setup.connect("123456:abcdefghijklmnopqrstuvwx")
        val pair = setup.beginPairing()
        assertEquals(16, pair.code.length)
        assertTrue(pair.expiresAt - System.currentTimeMillis() > 590_000)
        api.incoming.send(listOf(TelegramUpdate(1, TelegramAccount(42, 42, "Owner"), "/start pair_${pair.code}")))
        val candidate = setup.awaitPairing(pair)
        assertNull(setup.state.value.ownerId)
        setup.confirmPairing(candidate)
        assertEquals(42L, setup.state.value.ownerId)
        assertFalse(setup.state.value.enabled)
    }

    @Test fun formattingEscapesMarkupAndNeverSplitsAnEmojiSurrogatePair() {
        assertEquals("<b>bold</b> &lt;script&gt;&amp;", telegramHtml("**bold** <script>&"))
        assertEquals("<pre>a &lt; b\n</pre>", telegramHtml("```kotlin\na < b\n```"))
        val text = "a".repeat(99) + "😀" + "b".repeat(120)
        val parts = telegramChunks(text, 100)
        assertEquals(text, parts.joinToString(""))
        assertTrue(parts.none { Character.isHighSurrogate(it.last()) || Character.isLowSurrogate(it.first()) })
    }
}

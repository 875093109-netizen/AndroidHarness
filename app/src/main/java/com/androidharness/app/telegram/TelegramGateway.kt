package com.androidharness.app.telegram

import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class TelegramRuntimeState(val running: Boolean = false, val connected: Boolean = false, val reconnecting: Boolean = false,
    val message: String? = null, val action: String? = null, val activeRequestId: Long? = null, val lastContactAt: Long? = null) {
    val label get() = when { !running -> "Paused"; !connected -> if (reconnecting) "Reconnecting" else "Connecting";
        action?.contains("approval", true) == true -> "Needs approval"; action?.contains("answer", true) == true -> "Needs answer";
        activeRequestId != null -> "Working"; else -> "Listening" }
}

/** One receiver, durable intake, a serial agent worker, and a separate delivery worker. */
internal class TelegramGateway(
    val store: TelegramGatewayStore,
    private val connection: () -> TelegramConnection?,
    private val selectDestination: (String, String?) -> Unit,
    private val selectMode: (String) -> Unit,
    private val advanceOffset: (Long) -> Unit,
    private val pause: suspend () -> Unit,
    val agent: TelegramAgentBridge,
    private val api: (String) -> TelegramBotApi = ::TelegramBotClient,
    private val redact: (String) -> String = { com.androidharness.app.tools.SecretRedactor.redact(it) },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutable = MutableStateFlow(TelegramRuntimeState())
    val state = mutable.asStateFlow()
    private val controls = Mutex()
    private var binding = store.state.value.binding
    private fun current(): TelegramConnection {
        val c = connection()?.takeIf { it.enabled } ?: throw CancellationException("Telegram paused")
        check(telegramBinding(c) == binding) { "Telegram account changed." }
        return c
    }
    private fun safe(text: String): String {
        val token = connection()?.token
        return redact(if (token.isNullOrEmpty()) text else text.replace(token, "[redacted]"))
    }
    suspend fun run(): Unit = coroutineScope {
        val c = connection()?.takeIf { it.enabled } ?: return@coroutineScope
        store.bind(c)
        store.recoverSending()
        check(store.loadError == null) { store.loadError ?: "Telegram requests are unavailable." }
        binding = telegramBinding(c)
        mutable.value = TelegramRuntimeState(running = true, message = "Connecting to Telegram…")
        try {
            val transport = api(c.token)
            check(!transport.webhookActive()) { "This bot uses a webhook elsewhere. Remove it there or use a separate bot." }
            // Registering commands is cosmetic; a transient failure must not disable receiving.
            try { transport.registerCommands() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
            launch { receiveLoop() }
            launch { processLoop() }
            launch { workLoop() }
            launch { deliveryLoop() }
            awaitCancellation()
        } finally { mutable.update { it.copy(running = false, connected = false, action = null, activeRequestId = null) } }
    }

    private suspend fun receiveLoop() {
        var failures = 0
        while (currentCoroutineContext().isActive) {
            val c = current()
            try {
                val updates = api(c.token).getUpdates(store.state.value.offset)
                if (current().token != c.token) continue
                store.ingest(updates, requireNotNull(c.ownerId), now())
                advanceOffset(store.state.value.offset)
                failures = 0
                mutable.update { it.copy(connected = true, reconnecting = false, message = null, lastContactAt = now()) }
            } catch (e: CancellationException) { throw e
            } catch (e: TelegramSetupException) {
                if (e.problem in listOf(TelegramSetupProblem.RECEIVER_CONFLICT, TelegramSetupProblem.TOKEN_REJECTED)) throw e
                mutable.update { it.copy(connected = false, reconnecting = true, message = e.message) }
                delay((e.retryAfterSeconds?.toLong()?.times(1_000) ?: reconnectDelay(++failures)))
            } catch (_: java.io.IOException) {
                mutable.update { it.copy(connected = false, reconnecting = true, message = "Connection lost. Saved requests and replies will be kept.") }
                delay(reconnectDelay(++failures))
            }
            delay(100)
        }
    }

    private suspend fun processLoop() {
        while (currentCoroutineContext().isActive) {
            current()
            val received = store.state.value.requests.firstOrNull { it.status == TelegramRequestStatus.RECEIVED }
            if (received == null) { delay(150); continue }
            try {
                if (received.callback != null) handleCallback(received) else handleMessage(received)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                val message = safe(e.message ?: "Could not handle this request. It has been kept for review.")
                store.updateRequest(received.id) { it.copy(status = TelegramRequestStatus.FAILED, error = message) }
                reply("command-error-${received.id}", message)
            }
        }
    }

    private suspend fun target(): TelegramTarget = controls.withLock {
        val c = current()
        val project = c.projectId ?: error("Choose a project in AndroidHarness or use /projects.")
        val target = agent.target(project, c.sessionId, c.mode)
        if (c.sessionId != target.sessionId) selectDestination(project, target.sessionId)
        target
    }

    private suspend fun handleMessage(request: TelegramRequest) {
        val text = request.text.trim()
        val first = text.substringBefore(' ').substringBefore('\n')
        val command = first.substringBefore('@').lowercase()
        val arguments = text.substringAfter(first, "").trim()
        when (command) {
            "/start", "/help" -> {
                val choices = listOf("Projects" to "/projects", "Chats" to "/chats", "Model" to "/model",
                    "Status" to "/status", "New chat" to "/new", "Stop task" to "/stop")
                val actions = choices.map { TelegramAction(kind = "control", value = it.second,
                    sessionId = current().sessionId, expiresAt = now() + 600_000) }
                reply("help-${request.id}", HELP, choices.mapIndexed { index, choice ->
                    TelegramStoredButton(choice.first, actions[index].id)
                }.chunked(2), actions)
            }
            "/status" -> {
                val t = target()
                val queued = store.state.value.requests.count { it.status == TelegramRequestStatus.QUEUED }
                reply("status-${request.id}", "Project: ${t.projectName}\nChat: ${t.chatTitle}\nModel: ${t.providerName} · ${t.model}\nMode: ${current().mode.lowercase()}\nStatus: ${state.value.label}\nQueued requests: $queued")
            }
            "/projects" -> menu("projects-${request.id}", "Choose a project. A new chat will be used for that project.", agent.projects(), "project")
            "/chats" -> {
                val c = current()
                val project = c.projectId ?: error("Choose a project first with /projects.")
                menu("chats-${request.id}", "Choose a chat in this project.", agent.chats(project), "chat", projectId = project)
            }
            "/model" -> {
                ensureNoActive()
                val t = target()
                if (arguments.isNotBlank()) {
                    agent.selectModel(t.sessionId, t.providerId, arguments, current().mode)
                    reply("model-${request.id}", "Model selected: $arguments")
                } else menu("providers-${request.id}", "Choose a provider. Model changes follow this chat's model-sharing setting in AndroidHarness.",
                    agent.providers(), "provider", sessionId = t.sessionId, projectId = t.projectId, mode = current().mode)
            }
            "/new" -> controls.withLock {
                ensureNoActive()
                val c = current()
                val project = c.projectId ?: error("Choose a project first with /projects.")
                val sid = agent.newChat(project, arguments.ifBlank { "Telegram chat" })
                selectDestination(project, sid)
                reply("new-${request.id}", "New chat ready. Send your message to continue.")
            }
            "/mode" -> {
                ensureNoActive()
                val mode = arguments.uppercase()
                check(mode in listOf("ACT", "PLAN")) { "Use /mode plan for inspection or /mode act for editing and tools." }
                selectMode(mode)
                reply("mode-${request.id}", if (mode == "PLAN") "Plan mode: inspect and propose changes." else "Act mode: use tools with your AndroidHarness permission settings.")
            }
            "/stop" -> { stopCurrent(); reply("stop-${request.id}", "Stopped this chat's task and cancelled its Telegram queue. Saved messages and file changes are kept.") }
            "/retry", "/resume" -> {
                val id = arguments.toLongOrNull() ?: store.state.value.requests.lastOrNull { it.retryable && it.sessionId != null && it.callback == null }?.id
                    ?: error("No saved request needs retrying.")
                retry(id)
                reply("retry-${request.id}", "Request #$id is queued for retry or safe resume.")
            }
            "/pause" -> {
                reply("pause-${request.id}", "Telegram paused. Resume it from Settings → Telegram. Existing agent work remains available in the app.")
                store.updateRequest(request.id) { it.copy(status = TelegramRequestStatus.COMPLETED) }
                // Preserve the acknowledgement before pausing; delivery can continue on resume if offline.
                delay(600)
                pause()
                return
            }
            "/pair" -> reply("pair-${request.id}", "This account is already paired. To change accounts, start pairing in AndroidHarness.")
            else -> {
                val prompt = if (command == "/ask") arguments else text
                if (command.startsWith('/') && command != "/ask" && command != "/answer") {
                    reply("unknown-${request.id}", "Unknown command. Use /help for the available controls.")
                } else {
                    val t = target()
                    val active = store.state.value.requests.firstOrNull { it.active && it.sessionId == t.sessionId }
                    val snap = active?.let { agent.snapshot(it, binding) }
                    if (snap?.decision?.kind == "question" && snap.turnId != null) {
                        val accepted = agent.decide(t.sessionId, snap.turnId, snap.decision.callId, "question", if (command == "/answer") arguments else prompt, snap.decision.fingerprint)
                        reply("answer-${request.id}", if (accepted) "Answer received." else "That question is no longer waiting for an answer.")
                    } else {
                        check(command != "/answer") { "No question is waiting for an answer." }
                        check(prompt.isNotBlank()) { "Send a message or /ask followed by your question." }
                        store.updateRequest(request.id) { it.copy(text = prompt, status = TelegramRequestStatus.QUEUED,
                            projectId = t.projectId, sessionId = t.sessionId, providerId = t.providerId, model = t.model, mode = current().mode) }
                        reply("accepted-${request.id}", "Request #${request.id} queued · ${t.projectName}\n${t.chatTitle} · ${t.model}", requestId = request.id, progress = true)
                        return
                    }
                }
            }
        }
        store.updateRequest(request.id) { it.copy(status = TelegramRequestStatus.COMPLETED) }
    }

    private fun ensureNoActive() {
        check(store.state.value.requests.none { it.active || it.status == TelegramRequestStatus.QUEUED }) { "Use /stop or wait for queued tasks before changing the destination or model." }
    }

    private fun reply(key: String, text: String, buttons: List<List<TelegramStoredButton>> = emptyList(),
        actions: List<TelegramAction> = emptyList(), requestId: Long? = null, progress: Boolean = false, priority: Int = 2) {
        telegramChunks(safe(text)).forEachIndexed { index, part ->
            store.enqueue(TelegramDelivery("$key-$index", part, if (index == 0) buttons else emptyList(), requestId = requestId, progress = progress && index == 0,
                priority = if (buttons.isNotEmpty()) 3 else priority),
                if (index == 0) actions else emptyList())
        }
    }
    private fun menu(key: String, title: String, choices: List<TelegramChoiceItem>, kind: String,
        sessionId: String? = null, projectId: String? = null, extra: String = "", page: Int = 0, mode: String? = null) {
        val slice = choices.drop(page * 12).take(12)
        val actions = slice.map { TelegramAction(kind = kind, value = it.id, extra = extra, sessionId = sessionId, projectId = projectId, expiresAt = now() + 600_000, mode = mode) }.toMutableList()
        val buttons = slice.mapIndexed { index, choice -> listOf(TelegramStoredButton(choice.label.take(70), actions[index].id)) }.toMutableList()
        if ((page + 1) * 12 < choices.size) {
            val more = TelegramAction(kind = "page-$kind", value = (page + 1).toString(), extra = extra, sessionId = sessionId, projectId = projectId, expiresAt = now() + 600_000, mode = mode)
            actions += more; buttons += listOf(TelegramStoredButton("More…", more.id))
        }
        reply(key, if (choices.isEmpty()) "$title\nNo choices available. Create one in AndroidHarness first." else title, buttons, actions)
    }

    private suspend fun handleCallback(request: TelegramRequest) {
        val callback = requireNotNull(request.callback)
        val nonce = callback.data.takeIf { it.startsWith("tg:") }?.removePrefix("tg:")
        val action = nonce?.let { store.consumeAction(it, callback.messageId, now()) }
        if (action == null) {
            acknowledge(callback.id, "This button expired or was already used. Open the controls again.")
            store.updateRequest(request.id) { it.copy(status = TelegramRequestStatus.COMPLETED) }
            return
        }
        val key = "button-${request.id}"
        try {
            when (action.kind) {
                "control" -> {
                    check(current().sessionId == action.sessionId) { "The chat changed. Use /help for fresh controls." }
                    check(action.value in listOf("/projects", "/chats", "/model", "/status", "/new", "/stop"))
                    handleMessage(request.copy(text = action.value, callback = null))
                }
                "approval", "environment", "question" -> {
                    val accepted = agent.decide(requireNotNull(action.sessionId), requireNotNull(action.turnId), requireNotNull(action.callId), action.kind, action.value, action.extra)
                    acknowledge(callback.id, if (accepted) "Received." else "This action no longer belongs to the pending task.")
                    store.enqueue(TelegramDelivery(key, if (accepted) "Decision received: ${action.value.take(100)}" else "This action is no longer pending.", messageId = callback.messageId))
                }
                "project" -> controls.withLock {
                    ensureNoActive()
                    check(agent.projects().any { it.id == action.value }) { "This project was removed." }
                    val sid = agent.newChat(action.value)
                    selectDestination(action.value, sid)
                    reply(key, "Project selected. A new Telegram chat is ready.")
                }
                "chat" -> controls.withLock {
                    ensureNoActive()
                    check(current().projectId == action.projectId) { "The selected project changed. Use /chats again." }
                    val t = agent.target(requireNotNull(action.projectId), action.value, current().mode)
                    selectDestination(t.projectId, t.sessionId)
                    reply(key, "Chat selected: ${t.chatTitle}\nModel: ${t.model}")
                }
                "provider" -> {
                    ensureNoActive()
                    check(current().sessionId == action.sessionId && (action.mode == null || current().mode == action.mode)) { "The chat or mode changed. Use /model again." }
                    menu(key, "Choose a model.", agent.models(action.value), "model", action.sessionId, action.projectId, action.value, mode = action.mode)
                }
                "model" -> {
                    ensureNoActive()
                    check(current().sessionId == action.sessionId && (action.mode == null || current().mode == action.mode)) { "The chat or mode changed. Use /model again." }
                    agent.selectModel(requireNotNull(action.sessionId), action.extra, action.value, current().mode)
                    reply(key, "Model selected: ${action.value}")
                }
                "retry" -> { retry(action.value.toLong()); reply(key, "Saved request queued for safe retry.") }
                "resend" -> { resend(action.value); reply(key, "Saved reply queued for delivery. The model will not run again.") }
                else -> if (action.kind.startsWith("page-")) {
                    val kind = action.kind.removePrefix("page-")
                    val choices = when (kind) { "project" -> agent.projects(); "chat" -> agent.chats(requireNotNull(action.projectId));
                        "provider" -> agent.providers(); "model" -> agent.models(action.extra); else -> emptyList() }
                    menu(key, "Choose an option.", choices, kind, action.sessionId, action.projectId, action.extra, action.value.toInt(), action.mode)
                } else error("This control is no longer available.")
            }
            if (action.kind !in listOf("approval", "environment", "question")) acknowledge(callback.id, "Received.")
            store.updateRequest(request.id) { it.copy(status = TelegramRequestStatus.COMPLETED) }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { acknowledge(callback.id, safe(e.message ?: "Could not finish this action.")); throw e }
    }
    private suspend fun acknowledge(id: String, message: String) {
        try { api(current().token).answerCallback(id, message) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
    }

    suspend fun retry(id: Long) = controls.withLock {
        val row = store.request(id)?.takeIf { it.retryable && it.callback == null && it.sessionId != null }
            ?: error("This request cannot be retried. Review it in AndroidHarness.")
        val snap = agent.snapshot(row, binding)
        check(!snap.running) { "This chat is already running." }
        check(!snap.started || snap.matches && snap.interrupted) { "This request's task has moved on. Review its chat before retrying." }
        val t = agent.target(requireNotNull(row.projectId), row.sessionId, row.mode)
        store.updateRequest(id) { it.copy(status = TelegramRequestStatus.QUEUED, resume = snap.started,
            providerId = t.providerId, model = t.model, error = null, attempt = it.attempt + 1) }
    }
    fun resend(key: String) {
        val delivery = store.state.value.deliveries.firstOrNull { it.key == key }
            ?: error("This saved reply is no longer available.")
        check(delivery.status in listOf(TelegramDeliveryStatus.FAILED, TelegramDeliveryStatus.UNCERTAIN)) { "This reply is already queued or sent." }
        store.updateDelivery(key) { it.copy(status = TelegramDeliveryStatus.PENDING, retryAt = 0, error = null) }
    }
    suspend fun stopCurrent() = controls.withLock {
        val sid = connection()?.sessionId ?: return@withLock
        agent.stop(sid)
        store.change { saved -> saved.copy(requests = saved.requests.map { if (it.sessionId == sid &&
            (it.active || it.status == TelegramRequestStatus.QUEUED)) it.copy(status = TelegramRequestStatus.CANCELLED, error = "Stopped by you.") else it }) }
    }

    private suspend fun workLoop() {
        while (currentCoroutineContext().isActive) {
            current()
            val row = store.state.value.requests.firstOrNull { it.active }
                ?: store.state.value.requests.firstOrNull { it.status == TelegramRequestStatus.QUEUED }
            if (row == null) { mutable.update { it.copy(action = null, activeRequestId = null) }; delay(300); continue }
            try {
                val snapshot = agent.snapshot(row, binding)
                if (snapshot.started && !snapshot.matches) { interrupted(row, snapshot.error ?: "This chat moved on. Review it in AndroidHarness."); continue }
                if (snapshot.completed) { finish(row, snapshot.output); continue }
                if (snapshot.cancelled) { store.updateRequest(row.id) { it.copy(status = TelegramRequestStatus.CANCELLED) }; continue }
                if (snapshot.interrupted && !row.resume) { interrupted(row, snapshot.error ?: "Task interrupted. Use /retry ${row.id} to resume safely."); continue }
                if (!snapshot.running) {
                    if (!row.resume && agent.isBusy(requireNotNull(row.sessionId))) { delay(500); continue }
                    controls.withLock {
                        if (store.request(row.id)?.status !in listOf(TelegramRequestStatus.QUEUED, TelegramRequestStatus.STARTING)) return@withLock
                        store.updateRequest(row.id) { it.copy(status = TelegramRequestStatus.STARTING) }
                        agent.start(row, binding)
                        val started = agent.snapshot(row, binding)
                        store.updateRequest(row.id) { it.copy(status = TelegramRequestStatus.RUNNING, turnId = started.turnId, resume = false) }
                    }
                    continue
                }
                mutable.update { it.copy(action = safe(snapshot.progress), activeRequestId = row.id) }
                if (snapshot.decision != null && snapshot.turnId != null) publishDecision(row, snapshot)
                publishProgress(row, snapshot)
                delay(900)
            } catch (e: CancellationException) { throw e
            } catch (e: com.androidharness.app.agent.RunBusyException) {
                store.updateRequest(row.id) { it.copy(status = TelegramRequestStatus.QUEUED) }; delay(500)
            } catch (e: Exception) { interrupted(row, safe(e.message ?: "The task failed. Your request is saved."), TelegramRequestStatus.FAILED) }
        }
    }
    private fun interrupted(row: TelegramRequest, error: String, status: TelegramRequestStatus = TelegramRequestStatus.INTERRUPTED) {
        val message = telegramAgentError(safe(error))
        val retry = TelegramAction(kind = "retry", value = row.id.toString(), expiresAt = now() + 600_000)
        reply("failed-${row.id}-${row.attempt}", "Request #${row.id} needs attention.\n$message\nUse /retry ${row.id} to retry or resume saved work.",
            listOf(listOf(TelegramStoredButton("Retry / resume", retry.id))), listOf(retry))
        store.updateRequest(row.id) { if (it.status == TelegramRequestStatus.CANCELLED) it else it.copy(status = status, error = message) }
    }
    private fun finish(row: TelegramRequest, output: String) {
        val chunks = telegramChunks(safe("Request #${row.id} finished\n\n${output.ifBlank { "Completed. Open the chat in AndroidHarness to review its tool results." }}"))
        // Completion and every reply chunk share one durable write.
        store.change { saved ->
            if (saved.requests.any { it.id == row.id && it.status == TelegramRequestStatus.CANCELLED }) saved
            else {
                val kept = saved.deliveries.filterNot { it.progress && it.requestId == row.id && it.messageId != null && it.status == TelegramDeliveryStatus.PENDING }
                val keys = kept.mapTo(HashSet()) { it.key }
                val replies = chunks.mapIndexed { index, part -> TelegramDelivery("final-${row.id}-${row.attempt}-$index", part) }.filter { it.key !in keys }
                saved.copy(deliveries = kept.filter { it.status == TelegramDeliveryStatus.SENT }.takeLast(200) +
                    kept.filter { it.status != TelegramDeliveryStatus.SENT } + replies,
                    requests = saved.requests.map { if (it.id == row.id) it.copy(status = TelegramRequestStatus.COMPLETED, error = null) else it })
            }
        }
    }
    private fun publishProgress(row: TelegramRequest, snapshot: TelegramRunSnapshot) {
        val messageId = store.request(row.id)?.progressMessageId ?: return
        val content = safe("Request #${row.id} · ${snapshot.progress}\n${row.model.orEmpty()}" +
            snapshot.streamingText.takeLast(2_000).takeIf { it.isNotBlank() }?.let { "\n\n$it" }.orEmpty())
        if (row.lastProgress == content || snapshot.decision != null) return
        val key = "progress-${row.id}-${UUID.randomUUID()}"
        store.change { saved -> saved.copy(
            deliveries = saved.deliveries.filterNot { it.progress && it.requestId == row.id && it.messageId != null && it.status == TelegramDeliveryStatus.PENDING } +
                TelegramDelivery(key, content, messageId = messageId, requestId = row.id, progress = true, priority = 0),
            requests = saved.requests.map { if (it.id == row.id) it.copy(lastProgress = content) else it }) }
    }
    private fun publishDecision(row: TelegramRequest, snapshot: TelegramRunSnapshot) {
        val d = requireNotNull(snapshot.decision)
        val signature = "${snapshot.turnId}:${d.kind}:${d.callId}:${d.fingerprint}"
        if (d.kind == "question" && d.options.isEmpty() && row.decisionKey == signature) return
        if (store.state.value.actions.any { it.sessionId == row.sessionId && it.turnId == snapshot.turnId && it.callId == d.callId &&
            it.kind == d.kind && it.extra == d.fingerprint && !it.consumed && it.expiresAt > now() }) return
        val choices = when (d.kind) { "approval" -> listOf("Approve once" to "approve", "Deny" to "deny")
            "environment" -> listOf("Install / repair" to "install", "Skip" to "skip")
            else -> d.options.take(12).map { it to it } }
        val actions = choices.map { (_, value) -> TelegramAction(kind = d.kind, value = value, extra = d.fingerprint, sessionId = row.sessionId,
            turnId = snapshot.turnId, callId = d.callId, expiresAt = now() + 600_000) }
        val buttons = choices.mapIndexed { index, (label, _) -> listOf(TelegramStoredButton(label.take(70), actions[index].id)) }
        reply("decision-${row.id}-${d.callId}-${now()}", "Request #${row.id} · ${d.title}\n\n${d.detail.take(6_000)}", buttons, actions)
        store.updateRequest(row.id) { it.copy(decisionKey = signature) }
    }

    private suspend fun deliveryLoop() {
        while (currentCoroutineContext().isActive) {
            val c = current()
            val item = store.state.value.deliveries.filter { it.status == TelegramDeliveryStatus.PENDING && it.retryAt <= now() }.maxByOrNull { it.priority }
            if (item == null) { delay(250); continue }
            store.updateDelivery(item.key) { it.copy(status = TelegramDeliveryStatus.SENDING, attempts = it.attempts + 1) }
            try {
                val buttons = item.buttons.map { row -> row.map { TelegramButton(it.text, "tg:${it.actionId}") } }
                val transport = api(c.token)
                val id = if (item.messageId != null) { transport.edit(requireNotNull(c.ownerChatId), item.messageId, item.text, buttons); item.messageId }
                    else transport.publish(requireNotNull(c.ownerChatId), item.text, buttons)
                current()
                store.delivered(item.key, id)
                // Limit edits and messages to roughly one operation per second for this private chat.
                delay(1_100)
            } catch (e: CancellationException) { throw e
            } catch (e: TelegramSetupException) {
                val rateLimited = e.problem == TelegramSetupProblem.RATE_LIMIT
                val safeEdit = item.messageId != null && e.problem == TelegramSetupProblem.NETWORK
                store.updateDelivery(item.key) { it.copy(status = when {
                    rateLimited || safeEdit -> TelegramDeliveryStatus.PENDING
                    e.deliveryUncertain && item.messageId == null -> TelegramDeliveryStatus.UNCERTAIN
                    else -> TelegramDeliveryStatus.FAILED },
                    retryAt = now() + (e.retryAfterSeconds?.toLong()?.times(1_000) ?: reconnectDelay(it.attempts)),
                    error = if (e.deliveryUncertain && item.messageId == null) "Delivery is uncertain. Check Telegram before resending this saved reply." else e.message) }
                if (rateLimited) delay((e.retryAfterSeconds ?: 2).toLong() * 1_000)
            } catch (_: Exception) {
                store.updateDelivery(item.key) { it.copy(status = if (item.messageId != null) TelegramDeliveryStatus.PENDING else TelegramDeliveryStatus.UNCERTAIN,
                    retryAt = now() + reconnectDelay(it.attempts), error = "Delivery was interrupted. Check Telegram before resending.") }
            }
        }
    }
    companion object {
        private fun reconnectDelay(attempt: Int) = (1_000L shl attempt.coerceIn(0, 5)).coerceAtMost(30_000)
        const val HELP = "Send a normal message to continue your AndroidHarness chat.\n\n/new — new chat\n/projects — choose project\n/chats — choose chat\n/model — choose provider and model\n/mode plan or /mode act\n/status — project, model and progress\n/stop — stop this task and its queue\n/retry — retry or resume saved work\n/pause — pause Telegram\n\nTool approvals appear as buttons. Answer agent questions with a button or a normal message. Your phone must be online with Telegram enabled in AndroidHarness."
    }
}

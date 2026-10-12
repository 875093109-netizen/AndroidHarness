package com.androidharness.app.telegram

import com.androidharness.app.AppContainer
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class TelegramPairingState(val challenge: TelegramPairing? = null, val candidate: TelegramPairingCandidate? = null, val error: String? = null)

internal class TelegramManager(
    private val c: AppContainer,
    private val api: (String) -> TelegramBotApi = ::TelegramBotClient,
    internal val bridge: TelegramAgentBridge = AndroidTelegramAgentBridge(c),
    val setup: TelegramSetupRepository = TelegramSetupRepository(c.keys::telegramConnection, c.keys::putTelegramConnection),
    val store: TelegramGatewayStore = TelegramGatewayStore(File(c.appContext.filesDir, "telegram/gateway.json")),
    private val startService: () -> Unit = { TelegramService.start(c.appContext) },
    private val stopService: () -> Unit = { TelegramService.stop(c.appContext) },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val edits = Mutex()
    private var runtimeJob: Job? = null
    private var runtimeOwner: Any? = null
    private var pairingJob: Job? = null
    private var expiryJob: Job? = null
    private val pairingMutable = MutableStateFlow(TelegramPairingState())
    val pairing = pairingMutable.asStateFlow()
    private val errorMutable = MutableStateFlow<String?>(null)
    val error = errorMutable.asStateFlow()
    val gateway = TelegramGateway(store, setup::gatewayConnection, setup::selectDestination, setup::setMode, setup::advanceOffset,
        pause = { scope.launch { pause() } }, agent = bridge, api = api, redact = ::safe)
    val runtime = gateway.state

    private fun safe(text: String): String {
        var result = com.androidharness.app.tools.SecretRedactor.redact(text)
        setup.gatewayConnection()?.token?.takeIf { it.isNotEmpty() }?.let { result = result.replace(it, "[redacted]") }
        c.keys.githubToken()?.takeIf { it.isNotEmpty() }?.let { result = result.replace(it, "[redacted]") }
        return result
    }
    private fun idle() {
        check(store.state.value.requests.none { it.active || it.status == TelegramRequestStatus.QUEUED ||
            it.status == TelegramRequestStatus.RECEIVED && it.callback == null && !it.text.startsWith('/') }) {
            "Wait for Telegram tasks to finish, or stop them before changing setup."
        }
    }
    suspend fun connect(token: String) = edits.withLock {
        idle()
        val wasEnabled = setup.state.value.enabled
        if (wasEnabled) setup.setEnabled(false)
        stopRuntime()
        cancelPairing()
        try { setup.connect(token); errorMutable.value = null }
        catch (e: Exception) { if (wasEnabled) setup.setEnabled(true); throw e }
        finally { if (setup.state.value.enabled) startService() else stopService() }
    }
    suspend fun selectDestination(projectId: String, sessionId: String?) = edits.withLock {
        idle()
        val target = bridge.target(projectId, sessionId, setup.state.value.mode)
        setup.selectDestination(target.projectId, target.sessionId)
    }
    suspend fun newChat() = edits.withLock {
        idle()
        val project = setup.state.value.projectId ?: error("Choose a project first.")
        setup.selectDestination(project, bridge.newChat(project))
    }
    suspend fun selectModel(providerId: String, model: String) = edits.withLock {
        idle()
        bridge.selectModel(requireNotNull(setup.state.value.sessionId) { "Choose a chat first." }, providerId, model, setup.state.value.mode)
    }
    suspend fun setMode(mode: String) = edits.withLock { idle(); setup.setMode(mode) }
    suspend fun enable() = edits.withLock {
        check(pairing.value.challenge == null) { "Finish pairing your account first." }
        check(store.loadError == null) { store.loadError ?: "Saved Telegram requests are unavailable." }
        val connection = setup.gatewayConnection() ?: throw TelegramSetupException(TelegramSetupProblem.NOT_PAIRED)
        val project = connection.projectId ?: c.workspace.currentProjectOnce().id
        val target = bridge.target(project, connection.sessionId, connection.mode)
        setup.selectDestination(project, target.sessionId)
        store.bind(requireNotNull(setup.gatewayConnection()))
        setup.setEnabled(true)
        errorMutable.value = null
        try { startService() } catch (e: Exception) {
            setup.setEnabled(false)
            errorMutable.value = "Could not start Telegram. Keep AndroidHarness open and try enabling it again."
            throw IllegalStateException(errorMutable.value)
        }
    }
    fun restoreIfEnabled() {
        if (setup.state.value.enabled) try { startService() } catch (_: Exception) {
            errorMutable.value = "Open AndroidHarness and enable Telegram to reconnect."
        }
    }
    @Synchronized fun startRuntime(owner: Any? = null) {
        runtimeOwner = owner
        if (!setup.state.value.enabled || runtimeJob?.isActive == true) return
        runtimeJob = scope.launch {
            try { gateway.run() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                errorMutable.value = safe(e.message ?: "Telegram stopped. Saved requests are kept.")
                runCatching { setup.setEnabled(false) }
                stopService()
            }
        }
    }
    private suspend fun stopRuntime() { runtimeJob?.cancelAndJoin(); runtimeJob = null }
    suspend fun pause() {
        setup.gatewayConnection()?.let { setup.setEnabled(false) }
        stopRuntime()
        if (pairing.value.challenge == null) stopService()
    }
    @Synchronized fun serviceDestroyed(owner: Any? = null) {
        if (runtimeOwner !== owner) return
        runtimeJob?.cancel(); runtimeJob = null
    }
    suspend fun stopTasks() {
        gateway.stopCurrent()
    }
    suspend fun stopAndPause() { stopTasks(); pause(); cancelPairing() }
    suspend fun retry(id: Long) { gateway.retry(id); if (!setup.state.value.enabled) enable() }
    suspend fun resend(key: String) { gateway.resend(key); if (!setup.state.value.enabled) enable() }

    suspend fun beginPairing() = edits.withLock {
        idle()
        pause()
        cancelPairing()
        val challenge = setup.beginPairing()
        pairingMutable.value = TelegramPairingState(challenge)
        try { startService() } catch (_: Exception) {
            setup.cancelPairing(); pairingMutable.value = TelegramPairingState(error = "Could not keep pairing active. Keep the app open and retry.")
            throw IllegalStateException(pairing.value.error)
        }
        pairingJob = scope.launch {
            try { pairingMutable.value = TelegramPairingState(challenge, setup.awaitPairing(challenge)) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { pairingMutable.value = TelegramPairingState(error = safe(e.message ?: "Pairing failed. Please retry.")); stopService() }
        }
        expiryJob = scope.launch {
            delay((challenge.expiresAt - System.currentTimeMillis()).coerceAtLeast(1))
            setup.cancelPairing(); pairingJob?.cancel()
            pairingMutable.value = TelegramPairingState(error = "Pairing expired. Start again for a fresh code.")
            if (!setup.state.value.enabled) stopService()
        }
    }
    fun cancelPairing() {
        setup.cancelPairing()
        pairingJob?.cancel(); expiryJob?.cancel()
        pairingJob = null; expiryJob = null
        pairingMutable.value = TelegramPairingState()
        if (!setup.state.value.enabled) stopService()
    }
    suspend fun confirmPairing() = edits.withLock {
        setup.confirmPairing(requireNotNull(pairing.value.candidate) { "Send the pairing message from your Telegram account first." })
        cancelPairing()
        val project = setup.state.value.projectId ?: c.workspace.currentProjectOnce().id
        val target = bridge.target(project, setup.state.value.sessionId, setup.state.value.mode)
        setup.selectDestination(project, target.sessionId)
    }
    suspend fun unpair() { try { stopTasks() } finally { withContext(NonCancellable) { pause(); cancelPairing(); setup.unpair() } } }
    suspend fun disconnect() { try { stopTasks() } finally { withContext(NonCancellable) { pause(); cancelPairing(); setup.disconnect() } } }
    suspend fun sendConnectionTest() { setup.sendTestMessage() }
    suspend fun target(): TelegramTarget? = edits.withLock {
        setup.state.value.projectId?.let { project ->
            bridge.target(project, setup.state.value.sessionId, setup.state.value.mode).also { if (setup.state.value.sessionId == null) setup.selectDestination(project, it.sessionId) }
        }
    }
}

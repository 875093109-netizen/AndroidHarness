package com.androidharness.app.telegram

import com.androidharness.app.AppContainer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow

internal data class TelegramDeviceMessage(val id: Long, val text: String, val buttons: List<List<TelegramButton>>)
internal class TelegramDeviceApi : TelegramBotApi {
    val incoming = Channel<List<TelegramUpdate>>(Channel.UNLIMITED)
    val sent = CopyOnWriteArrayList<TelegramDeviceMessage>()
    val edited = CopyOnWriteArrayList<TelegramDeviceMessage>()
    var rejected = false
    private val ids = AtomicLong(100)
    override suspend fun getMe(): TelegramBotIdentity {
        if (rejected) throw TelegramSetupException(TelegramSetupProblem.TOKEN_REJECTED)
        return TelegramBotIdentity(123456, "fixture_bot")
    }
    override suspend fun getUpdates(offset: Long) = incoming.receive().filter { it.id >= offset }
    override suspend fun sendMessage(chatId: Long, text: String) { publish(chatId, text) }
    override suspend fun publish(chatId: Long, text: String, buttons: List<List<TelegramButton>>): Long {
        check(chatId == 42L)
        val id = ids.incrementAndGet()
        sent += TelegramDeviceMessage(id, text, buttons)
        return id
    }
    override suspend fun edit(chatId: Long, messageId: Long, text: String, buttons: List<List<TelegramButton>>) {
        check(chatId == 42L); edited += TelegramDeviceMessage(messageId, text, buttons)
    }
    suspend fun message(id: Long, text: String) = incoming.send(listOf(TelegramUpdate(id, TelegramAccount(42, 42, "Fixture account"), text,
        sentAtSeconds = System.currentTimeMillis() / 1_000, messageId = id + 1_000)))
    suspend fun click(id: Long, message: TelegramDeviceMessage, button: TelegramButton) = incoming.send(listOf(TelegramUpdate(id,
        TelegramAccount(42, 42, "Fixture account"), null, callback = TelegramCallback("callback-$id", message.id, button.data))))
}

internal class TelegramDeviceBridge : TelegramAgentBridge {
    val selectedModel = MutableStateFlow("kilo-auto/free")
    var created = 0
    override suspend fun projects() = listOf(TelegramChoiceItem("project-a", "Demo project"), TelegramChoiceItem("project-b", "Other project"))
    override suspend fun chats(projectId: String) = listOf(TelegramChoiceItem("chat-a", "Existing chat"))
    override suspend fun providers() = listOf(TelegramChoiceItem("fixture", "Fixture provider"))
    override suspend fun models(providerId: String) = listOf(TelegramChoiceItem("kilo-auto/free", "kilo-auto/free"), TelegramChoiceItem("inclusionai/ling-3.1-flash", "inclusionai/ling-3.1-flash"))
    override suspend fun target(projectId: String, sessionId: String?, mode: String) = TelegramTarget(projectId, "Demo project",
        sessionId ?: newChat(projectId), "Existing chat", "fixture", "Fixture provider", selectedModel.value)
    override suspend fun newChat(projectId: String, title: String) = "fixture-chat-${++created}"
    override suspend fun selectModel(sessionId: String, providerId: String, model: String, mode: String) { selectedModel.value = model }
    override suspend fun snapshot(request: TelegramRequest, binding: String) = TelegramRunSnapshot()
    override suspend fun start(request: TelegramRequest, binding: String) = Unit
    override suspend fun stop(sessionId: String) = Unit
    override suspend fun decide(sessionId: String, turnId: String, callId: String, kind: String, value: String, fingerprint: String?) = false
    override suspend fun isBusy(sessionId: String) = false
}

internal class TelegramUiFixture(container: AppContainer) {
    val api = TelegramDeviceApi()
    val bridge = TelegramDeviceBridge()
    var credentials: String? = null
    var savedRequests: String? = null
    val setup = TelegramSetupRepository({ credentials }, { credentials = it }, { api })
    val store = TelegramGatewayStore({ savedRequests }, { savedRequests = it })
    val manager: TelegramManager
    init {
        lateinit var controller: TelegramManager
        controller = TelegramManager(container, { api }, bridge, setup, store,
            startService = { controller.startRuntime() }, stopService = { controller.serviceDestroyed() })
        manager = controller
    }
}

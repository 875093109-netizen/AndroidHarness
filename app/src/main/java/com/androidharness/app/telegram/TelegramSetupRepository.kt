package com.androidharness.app.telegram

import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class TelegramConnection(
    val token: String,
    val botId: Long,
    val botUsername: String,
    val ownerId: Long? = null,
    val ownerChatId: Long? = null,
    val ownerName: String? = null,
    val projectId: String? = null,
    val sessionId: String? = null,
    val offset: Long = 0,
    val enabled: Boolean = false,
    val mode: String = "ACT",
) {
    override fun toString() = "TelegramConnection(botId=$botId)"
}

internal data class TelegramSetupState(
    val botUsername: String? = null,
    val ownerId: Long? = null,
    val ownerName: String? = null,
    val projectId: String? = null,
    val sessionId: String? = null,
    val enabled: Boolean = false,
    val mode: String = "ACT",
)
internal data class TelegramPairing(val code: String, val botId: Long, val expiresAt: Long, internal val token: String) {
    override fun toString() = "TelegramPairing(botId=$botId)"
}
internal data class TelegramPairingCandidate(val challenge: TelegramPairing, val account: TelegramAccount)

/** Real setup checks, with no agent execution or background receiver in this preview. */
internal class TelegramSetupRepository(
    read: () -> String?,
    private val write: (String?) -> Unit,
    private val api: (String) -> TelegramBotApi = { TelegramBotClient(it) },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    @Volatile private var connection = runCatching { read()?.let { json.decodeFromString<TelegramConnection>(it) } }.getOrNull()
        ?.takeIf { ((validTelegramToken(it.token) && it.botId > 0) || (it.token.isEmpty() && it.botId == 0L && it.ownerId == null)) &&
            (it.ownerId == null || (it.ownerId > 0 && it.ownerChatId == it.ownerId)) }
    private val mutableState = MutableStateFlow(snapshot())
    val state = mutableState.asStateFlow()
    @Volatile private var activePairing: TelegramPairing? = null
    private var closed = false

    private fun snapshot() = TelegramSetupState(connection?.botUsername?.takeIf { it.isNotBlank() }, connection?.ownerId, connection?.ownerName,
        connection?.projectId, connection?.sessionId, connection?.enabled == true, connection?.mode ?: "ACT")

    @Synchronized private fun save(next: TelegramConnection?) {
        try { write(next?.let { json.encodeToString(TelegramConnection.serializer(), it) }) }
        catch (_: Exception) { throw TelegramSetupException(TelegramSetupProblem.STORAGE) }
        connection = next
        mutableState.value = snapshot()
    }

    suspend fun connect(token: String) = withContext(Dispatchers.IO) {
        val clean = token.trim()
        if (!validTelegramToken(clean)) throw TelegramSetupException(TelegramSetupProblem.TOKEN_FORMAT)
        val bot = api(clean).getMe()
        synchronized(this@TelegramSetupRepository) {
            checkOpen()
            val old = connection
            val sameBot = old?.botId == bot.id
            save(TelegramConnection(clean, bot.id, bot.username,
                ownerId = old?.ownerId.takeIf { sameBot }, ownerChatId = old?.ownerChatId.takeIf { sameBot },
                ownerName = old?.ownerName.takeIf { sameBot }, projectId = old?.projectId, sessionId = old?.sessionId,
                offset = old?.offset.takeIf { sameBot } ?: 0))
            activePairing = null
        }
    }

    @Synchronized fun beginPairing(): TelegramPairing {
        checkOpen()
        val c = connection ?: throw TelegramSetupException(TelegramSetupProblem.NOT_PAIRED)
        if (c.botId <= 0 || !validTelegramToken(c.token)) throw TelegramSetupException(TelegramSetupProblem.NOT_PAIRED)
        val code = ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02X".format(it) }
        return TelegramPairing(code, c.botId, now() + TELEGRAM_PAIRING_VALIDITY_MS, c.token).also { activePairing = it }
    }

    suspend fun awaitPairing(challenge: TelegramPairing): TelegramPairingCandidate = withContext(Dispatchers.IO) {
        val transport = api(challenge.token)
        withTimeoutOrNull((challenge.expiresAt - now()).coerceAtLeast(1)) {
            while (true) {
                checkChallenge(challenge)
                val before = connection ?: throw TelegramSetupException(TelegramSetupProblem.PAIR_CHANGED)
                val updates = transport.getUpdates(before.offset)
                val matching = synchronized(this@TelegramSetupRepository) {
                    checkChallenge(challenge)
                    val current = requireNotNull(connection)
                    val nextOffset = updates.maxOfOrNull { it.id + 1 }?.coerceAtLeast(current.offset) ?: current.offset
                    if (nextOffset != current.offset) save(current.copy(offset = nextOffset))
                    updates.firstOrNull { it.id >= before.offset && it.account != null &&
                        it.text?.trim() in listOf("/pair ${challenge.code}", "/start pair_${challenge.code}") }
                }
                if (matching != null) return@withTimeoutOrNull TelegramPairingCandidate(challenge, matching.account!!)
                kotlinx.coroutines.delay(100)
            }
            @Suppress("UNREACHABLE_CODE") error("Unreachable")
        } ?: throw TelegramSetupException(TelegramSetupProblem.PAIR_EXPIRED)
    }

    @Synchronized private fun checkChallenge(challenge: TelegramPairing) {
        checkOpen()
        if (activePairing !== challenge || connection?.token != challenge.token || connection?.botId != challenge.botId)
            throw TelegramSetupException(TelegramSetupProblem.PAIR_CHANGED)
        if (now() >= challenge.expiresAt) throw TelegramSetupException(TelegramSetupProblem.PAIR_EXPIRED)
    }

    @Synchronized fun confirmPairing(candidate: TelegramPairingCandidate) {
        checkChallenge(candidate.challenge)
        val account = candidate.account
        if (account.userId <= 0 || account.chatId != account.userId) throw TelegramSetupException(TelegramSetupProblem.PAIR_CHANGED)
        save(requireNotNull(connection).copy(ownerId = account.userId, ownerChatId = account.chatId, ownerName = account.name, enabled = false))
        activePairing = null
    }

    @Synchronized fun cancelPairing() { activePairing = null }
    @Synchronized fun close() { closed = true; activePairing = null }
    private fun checkOpen() { if (closed) throw TelegramSetupException(TelegramSetupProblem.PAIR_CHANGED) }
    @Synchronized fun unpair() {
        checkOpen()
        activePairing = null
        connection?.let { save(it.copy(ownerId = null, ownerChatId = null, ownerName = null, enabled = false)) }
    }
    @Synchronized fun disconnect() { checkOpen(); save(null); activePairing = null }
    @Synchronized fun selectDestination(projectId: String, sessionId: String?) {
        checkOpen()
        save((connection ?: TelegramConnection("", 0, "")).copy(projectId = projectId, sessionId = sessionId))
    }

    @Synchronized fun gatewayConnection(): TelegramConnection? = connection?.takeIf { it.botId > 0 && it.ownerId != null && it.ownerChatId == it.ownerId }
    @Synchronized fun setEnabled(enabled: Boolean) {
        checkOpen()
        val c = connection ?: throw TelegramSetupException(TelegramSetupProblem.NOT_PAIRED)
        if (enabled && (c.ownerId == null || c.projectId == null)) throw TelegramSetupException(TelegramSetupProblem.NOT_PAIRED)
        save(c.copy(enabled = enabled))
    }
    @Synchronized fun setMode(mode: String) {
        checkOpen()
        require(mode in listOf("ACT", "PLAN")) { "Choose Plan or Act." }
        save(requireNotNull(connection).copy(mode = mode))
    }
    @Synchronized fun advanceOffset(offset: Long) {
        checkOpen()
        connection?.let { if (offset > it.offset) save(it.copy(offset = offset)) }
    }

    suspend fun sendTestMessage() = withContext(Dispatchers.IO) {
        val c = connection ?: throw TelegramSetupException(TelegramSetupProblem.NOT_PAIRED)
        val chat = c.ownerChatId ?: throw TelegramSetupException(TelegramSetupProblem.NOT_PAIRED)
        api(c.token).sendMessage(chat, "AndroidHarness: your bot and paired account are connected. Use /help to see your Telegram controls.")
    }

}

internal const val TELEGRAM_PAIRING_VALIDITY_MS = 600_000L

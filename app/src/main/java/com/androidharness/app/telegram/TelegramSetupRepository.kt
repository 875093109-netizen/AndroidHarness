package com.androidharness.app.telegram

import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
) {
    override fun toString() = "TelegramConnection(botId=$botId)"
}

internal data class TelegramSetupState(
    val botUsername: String? = null,
    val ownerId: Long? = null,
    val ownerName: String? = null,
    val projectId: String? = null,
    val sessionId: String? = null,
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
    private val replyTestLock = Mutex()

    private fun snapshot() = TelegramSetupState(connection?.botUsername?.takeIf { it.isNotBlank() }, connection?.ownerId, connection?.ownerName,
        connection?.projectId, connection?.sessionId)

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
        val code = ByteArray(4).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02X".format(it) }
        return TelegramPairing(code, c.botId, now() + 120_000, c.token).also { activePairing = it }
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
                    updates.firstOrNull { it.id >= before.offset && it.account != null && it.text?.trim() == "/pair ${challenge.code}" }
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
        save(requireNotNull(connection).copy(ownerId = account.userId, ownerChatId = account.chatId, ownerName = account.name))
        activePairing = null
    }

    @Synchronized fun cancelPairing() { activePairing = null }
    @Synchronized fun close() { closed = true; activePairing = null }
    private fun checkOpen() { if (closed) throw TelegramSetupException(TelegramSetupProblem.PAIR_CHANGED) }
    @Synchronized fun unpair() {
        checkOpen()
        activePairing = null
        connection?.let { save(it.copy(ownerId = null, ownerChatId = null, ownerName = null)) }
    }
    @Synchronized fun disconnect() { checkOpen(); save(null); activePairing = null }
    @Synchronized fun selectDestination(projectId: String, sessionId: String?) {
        checkOpen()
        save((connection ?: TelegramConnection("", 0, "")).copy(projectId = projectId, sessionId = sessionId))
    }

    suspend fun sendTestMessage() = withContext(Dispatchers.IO) {
        val c = connection ?: throw TelegramSetupException(TelegramSetupProblem.NOT_PAIRED)
        val chat = c.ownerChatId ?: throw TelegramSetupException(TelegramSetupProblem.NOT_PAIRED)
        api(c.token).sendMessage(chat, "AndroidHarness test: your bot and paired account are connected. Remote task execution is not enabled in this preview.")
    }

    @Synchronized private fun pairedConnection(): TelegramConnection {
        checkOpen()
        return connection?.takeIf { it.ownerId != null && it.ownerChatId == it.ownerId }
            ?: throw TelegramSetupException(TelegramSetupProblem.NOT_PAIRED)
    }

    @Synchronized private fun checkReplyConnection(before: TelegramConnection) {
        val current = pairedConnection()
        if (before.token != current.token || before.botId != current.botId || before.ownerId != current.ownerId)
            throw TelegramSetupException(TelegramSetupProblem.PAIR_CHANGED)
    }

    private suspend fun deliverReply(before: TelegramConnection, reply: TelegramModelReply) {
        checkReplyConnection(before)
        api(before.token).sendMessage(requireNotNull(before.ownerChatId), "Kilo reply test · ${reply.model.take(120)}\n\n${reply.text}")
    }

    suspend fun sendKiloSample(model: String, tester: TelegramKiloReplyTest): TelegramModelReply = replyTestLock.withLock {
        val before = pairedConnection()
        val reply = tester.reply(model, "What is 17 multiplied by 23? Give the answer and one short sentence explaining the calculation.")
        deliverReply(before, reply)
        reply
    }

    /** Foreground, one-question test. Commands sent before listening starts cannot trigger a reply. */
    suspend fun receiveKiloQuestion(model: String, tester: TelegramKiloReplyTest): TelegramModelReply = replyTestLock.withLock {
        val before = pairedConnection()
        if (model !in tester.models()) throw TelegramSetupException(TelegramSetupProblem.KILO_MODEL)
        checkReplyConnection(before)
        val since = now() / 1_000
        val transport = api(before.token)
        transport.sendMessage(requireNotNull(before.ownerChatId), "Kilo test ready · ${model.take(120)}\nSend /ask followed by your question within 10 minutes. This test answers one question while the AndroidHarness Telegram screen stays open; device tools are disabled.")
        val question = withTimeoutOrNull(600_000) {
            while (true) {
                checkReplyConnection(before)
                val offset = requireNotNull(connection).offset
                val updates = transport.getUpdates(offset)
                val match = synchronized(this@TelegramSetupRepository) {
                    checkReplyConnection(before)
                    val current = requireNotNull(connection)
                    val nextOffset = updates.maxOfOrNull { it.id + 1 }?.coerceAtLeast(current.offset) ?: current.offset
                    if (nextOffset != current.offset) save(current.copy(offset = nextOffset))
                    updates.firstOrNull { it.id >= offset && it.account?.userId == before.ownerId &&
                        it.account?.chatId == before.ownerChatId && (it.sentAtSeconds ?: Long.MIN_VALUE) >= since &&
                        it.text?.startsWith("/ask ") == true }
                }
                if (match != null) return@withTimeoutOrNull match.text!!.removePrefix("/ask ").trim()
                kotlinx.coroutines.delay(100)
            }
            @Suppress("UNREACHABLE_CODE") error("Unreachable")
        } ?: throw TelegramSetupException(TelegramSetupProblem.KILO_WAIT_EXPIRED)
        val reply = tester.reply(model, question)
        deliverReply(before, reply)
        reply
    }
}

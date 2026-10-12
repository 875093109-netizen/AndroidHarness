package com.androidharness.app.telegram

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class TelegramSetupTest {
    private val token = "123456:abcdefghijklmnopqrstuvwx"
    private val anotherToken = "654321:zyxwvutsrqponmlkjihgfedcba"
    private class Api : TelegramBotApi {
        var identity = TelegramBotIdentity(123456, "fixture_bot")
        var problem: TelegramSetupProblem? = null
        var updates = emptyList<TelegramUpdate>()
        val sends = mutableListOf<Pair<Long, String>>()
        override suspend fun getMe(): TelegramBotIdentity {
            problem?.let { throw TelegramSetupException(it) }
            return identity
        }
        override suspend fun getUpdates(offset: Long) = updates
        override suspend fun sendMessage(chatId: Long, text: String) { sends += chatId to text }
    }

    @Test fun pairingRequiresPhoneConfirmationAndTestMessageUsesOnlyPairedChat() = runBlocking {
        var saved: String? = null
        val api = Api()
        val repo = TelegramSetupRepository({ saved }, { saved = it }, { api })
        repo.connect(token)
        try { repo.sendTestMessage(); fail("Unpaired send accepted") }
        catch (e: TelegramSetupException) { assertEquals(TelegramSetupProblem.NOT_PAIRED, e.problem) }
        val challenge = repo.beginPairing()
        api.updates = listOf(TelegramUpdate(7, TelegramAccount(42, 42, "Test account"), "/pair ${challenge.code}"))
        val candidate = repo.awaitPairing(challenge)
        assertNull(repo.state.value.ownerId)
        repo.confirmPairing(candidate)
        repo.sendTestMessage()
        assertEquals(42L, api.sends.single().first)
        assertFalse(api.sends.single().second.contains(token))
        assertEquals(42L, TelegramSetupRepository({ saved }, {}, { api }).state.value.ownerId)
        repo.unpair()
        assertNull(repo.state.value.ownerId)
    }

    @Test fun rejectedReplacementKeepsWorkingBotAndChangingBotClearsPairing() = runBlocking {
        var saved: String? = null
        val api = Api()
        val repo = TelegramSetupRepository({ saved }, { saved = it }, { api })
        repo.connect(token)
        val challenge = repo.beginPairing()
        api.updates = listOf(TelegramUpdate(1, TelegramAccount(42, 42, "Owner"), "/pair ${challenge.code}"))
        repo.confirmPairing(repo.awaitPairing(challenge))
        val original = saved
        api.problem = TelegramSetupProblem.TOKEN_REJECTED
        try { repo.connect(anotherToken); fail("Rejected token accepted") } catch (_: TelegramSetupException) {}
        assertEquals(original, saved)
        assertEquals(42L, repo.state.value.ownerId)
        api.problem = null
        api.identity = TelegramBotIdentity(654321, "second_bot")
        repo.connect(anotherToken)
        assertNull(repo.state.value.ownerId)
        assertEquals("second_bot", repo.state.value.botUsername)
    }

    @Test fun cancelledExpiredAndChangedPairingCannotAuthorizeAnAccount() = runBlocking {
        var clock = 1_000L
        val api = Api()
        val repo = TelegramSetupRepository({ null }, {}, { api }, { clock })
        repo.connect(token)
        val challenge = repo.beginPairing()
        val candidate = TelegramPairingCandidate(challenge, TelegramAccount(42, 42, "Owner"))
        repo.cancelPairing()
        assertThrows(TelegramSetupException::class.java) { repo.confirmPairing(candidate) }
        val expiring = repo.beginPairing()
        clock = expiring.expiresAt
        val expired = assertThrows(TelegramSetupException::class.java) {
            repo.confirmPairing(TelegramPairingCandidate(expiring, candidate.account))
        }
        assertEquals(TelegramSetupProblem.PAIR_EXPIRED, expired.problem)
        clock = 2_000L
        val previous = repo.beginPairing()
        repo.connect(anotherToken)
        assertThrows(TelegramSetupException::class.java) {
            repo.confirmPairing(TelegramPairingCandidate(previous, candidate.account))
        }
        assertNull(repo.state.value.ownerId)
    }

    @Test fun destinationDraftPersistsBeforeBotSetupAndProjectChangeClearsChat() {
        var saved: String? = null
        val repo = TelegramSetupRepository({ saved }, { saved = it })
        repo.selectDestination("project-a", "chat-a")
        val restored = TelegramSetupRepository({ saved }, { saved = it })
        assertNull(restored.state.value.botUsername)
        assertEquals("project-a", restored.state.value.projectId)
        assertEquals("chat-a", restored.state.value.sessionId)
        restored.selectDestination("project-b", null)
        assertNull(restored.state.value.sessionId)
        restored.disconnect()
        assertNull(saved)
    }

    @Test fun storageFailureDoesNotPublishAnUnsavedConnection() = runBlocking {
        val repo = TelegramSetupRepository({ null }, { throw IllegalStateException("secret storage details") }, { Api() })
        try { repo.connect(token); fail("Failed write accepted") }
        catch (e: TelegramSetupException) { assertEquals(TelegramSetupProblem.STORAGE, e.problem); assertFalse(e.message!!.contains("secret")) }
        assertNull(repo.state.value.botUsername)
    }

    @Test fun incomingPairingAcceptsOnlyPrivateHumanSenderWithMatchingChatId() {
        fun update(chatType: String, chatId: Long, isBot: Boolean) = parseTelegramUpdate(Json.parseToJsonElement("""
            {"update_id":7,"message":{"from":{"id":42,"is_bot":$isBot,"first_name":"Owner"},"chat":{"id":$chatId,"type":"$chatType"},"text":"/pair CODE"}}
        """))!!
        assertNotNull(update("private", 42, false).account)
        assertNull(update("group", -42, false).account)
        assertNull(update("private", 99, false).account)
        assertNull(update("private", 42, true).account)
        assertNull(parseTelegramUpdate(Json.parseToJsonElement("""{"update_id":8,"callback_query":{}}"""))!!.account)
        assertNull(parseTelegramUpdate(Json.parseToJsonElement("""{"update_id":null}""")))
    }

    @Test fun leavingSetupWhileBotCheckIsPendingDoesNotSaveItsLateResponse() = runBlocking {
        var saved: String? = null
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val reply = kotlinx.coroutines.CompletableDeferred<TelegramBotIdentity>()
        val api = object : TelegramBotApi {
            override suspend fun getMe(): TelegramBotIdentity { started.complete(Unit); return reply.await() }
            override suspend fun getUpdates(offset: Long) = emptyList<TelegramUpdate>()
            override suspend fun sendMessage(chatId: Long, text: String) = Unit
        }
        val repo = TelegramSetupRepository({ saved }, { saved = it }, { api })
        val result = async { runCatching { repo.connect(token) } }
        started.await()
        repo.close()
        reply.complete(TelegramBotIdentity(123456, "fixture_bot"))
        assertTrue(result.await().exceptionOrNull() is TelegramSetupException)
        assertNull(saved)
        assertNull(repo.state.value.botUsername)
    }
}

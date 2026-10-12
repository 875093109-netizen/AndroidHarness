package com.androidharness.app.telegram

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.llm.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TelegramKiloReplyTestTest {
    private val model = "kilo-auto/free"
    private val token = "123456:abcdefghijklmnopqrstuvwx"
    private fun tester(events: Flow<StreamEvent>, onRequest: (ProviderConfig, String, List<ChatMessage>, List<ToolSchema>) -> Unit = { _, _, _, _ -> }) =
        TelegramKiloReplyTest({ listOf(ModelEntry(model)) }, { object : LlmProvider {
            override fun streamChat(config: ProviderConfig, apiKey: String, systemPrompt: String, messages: List<ChatMessage>, tools: List<ToolSchema>, options: RequestOptions): Flow<StreamEvent> {
                onRequest(config, apiKey, messages, tools)
                return events
            }
        } })

    @Test fun usesSelectedFreeKiloModelWithoutCredentialsOrTools() = runBlocking {
        val test = tester(flowOf(StreamEvent.Batch(listOf(StreamEvent.TextDelta("391"), StreamEvent.Done("stop"))))) { config, key, messages, tools ->
            assertEquals(HarnessProvider.KILO_BASE_URL, config.baseUrl)
            assertEquals(model, config.model)
            assertEquals("", key)
            assertTrue(tools.isEmpty())
            assertEquals("17 × 23?", messages.single().text)
        }
        assertEquals(TelegramModelReply(model, "391"), test.reply(model, "17 × 23?"))
    }

    @Test fun unavailableModelsNeverStartAnInferenceRequest() = runBlocking {
        val test = tester(flowOf(StreamEvent.TextDelta("wrong"))) { _, _, _, _ -> fail("Unavailable model requested") }
        try { test.reply("paid/model", "Hello"); fail("Unavailable model accepted") }
        catch (e: TelegramSetupException) { assertEquals(TelegramSetupProblem.KILO_MODEL, e.problem) }
    }

    @Test fun partialErroredEmptyAndTruncatedStreamsAreRejected() = runBlocking {
        val attempts = listOf(
            flowOf(StreamEvent.TextDelta("partial")),
            flowOf(StreamEvent.TextDelta("partial"), StreamEvent.Failure("secret $token", 429)),
            flowOf(StreamEvent.Done("stop")),
            flowOf(StreamEvent.TextDelta("partial"), StreamEvent.Done("length")),
            flowOf(StreamEvent.TextDelta("x".repeat(3_501)), StreamEvent.Done("stop")),
        )
        attempts.forEach { events ->
            try { tester(events).reply(model, "Hello"); fail("Invalid stream accepted") }
            catch (e: TelegramSetupException) { assertFalse(e.message!!.contains(token)) }
        }
    }

    private class Fixture : TelegramBotApi {
        var updates = emptyList<TelegramUpdate>()
        val deliveries = mutableListOf<Pair<Long, String>>()
        val offsets = mutableListOf<Long>()
        override suspend fun getMe() = TelegramBotIdentity(123456, "fixture_bot")
        override suspend fun getUpdates(offset: Long): List<TelegramUpdate> {
            offsets += offset
            return updates.also { updates = emptyList() }
        }
        override suspend fun sendMessage(chatId: Long, text: String) { deliveries += chatId to text }
    }

    private suspend fun paired(api: Fixture): TelegramSetupRepository {
        var record: String? = null
        val repository = TelegramSetupRepository({ record }, { record = it }, { api }, { 1_000_000 })
        repository.connect(token)
        val challenge = repository.beginPairing()
        api.updates = listOf(TelegramUpdate(1, TelegramAccount(42, 42, "Owner"), "/pair ${challenge.code}"))
        repository.confirmPairing(repository.awaitPairing(challenge))
        return repository
    }

    @Test fun freshQuestionsFromOnlyThePairedAccountReachKiloAndTelegram() = runBlocking {
        val api = Fixture()
        val repository = paired(api)
        api.updates = listOf(
            TelegramUpdate(2, TelegramAccount(99, 99, "Other"), "/ask Wrong owner", 1_000),
            TelegramUpdate(3, TelegramAccount(42, 42, "Owner"), "/ask Old question", 999),
            TelegramUpdate(4, null, "/ask Group", 1_000),
            TelegramUpdate(5, TelegramAccount(42, 42, "Owner"), "/start", 1_000),
            TelegramUpdate(6, TelegramAccount(42, 42, "Owner"), "/ask 17 × 23?", 1_000),
        )
        val test = tester(flowOf(StreamEvent.TextDelta("391"), StreamEvent.Done("stop"))) { _, _, messages, _ ->
            assertEquals("17 × 23?", messages.single().text)
        }
        assertEquals("391", repository.receiveKiloQuestion(model, test).text)
        assertEquals(listOf(42L, 42L), api.deliveries.map { it.first })
        assertTrue(api.deliveries.first().second.startsWith("Kilo test ready"))
        assertEquals("Kilo reply test · kilo-auto/free\n\n391", api.deliveries.last().second)
        assertEquals(2L, api.offsets.last())
    }

    @Test fun leavingSetupDuringInferencePreventsItsLateReplyFromBeingDelivered() = runBlocking {
        val api = Fixture()
        val repository = paired(api)
        val started = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val test = tester(flow { started.complete(Unit); resume.await(); emit(StreamEvent.TextDelta("391")); emit(StreamEvent.Done("stop")) })
        val request = async {
            try { repository.sendKiloSample(model, test); null }
            catch (e: TelegramSetupException) { e.problem }
        }
        started.await()
        repository.close()
        resume.complete(Unit)
        assertEquals(TelegramSetupProblem.PAIR_CHANGED, request.await())
        assertTrue(api.deliveries.isEmpty())
    }

    @Test fun failedInferenceDoesNotSendPartialText() = runBlocking {
        val api = Fixture()
        val repository = paired(api)
        try { repository.sendKiloSample(model, tester(flowOf(StreamEvent.TextDelta("partial"), StreamEvent.Failure("bad", 503)))); fail("Failed stream accepted") }
        catch (e: TelegramSetupException) { assertEquals(TelegramSetupProblem.KILO_REPLY, e.problem) }
        assertTrue(api.deliveries.isEmpty())
    }
}

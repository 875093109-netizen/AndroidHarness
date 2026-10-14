package com.androidharness.app.llm

import com.androidharness.app.agent.*
import com.androidharness.app.core.*
import com.androidharness.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody

class FallbackProviderTest {
    private val a = ProviderConfig("a", "Primary", ProviderType.OPENAI_COMPAT, "https://a.test", "model-a")
    private val b = a.copy(id = "b", name = "Backup", baseUrl = "https://b.test", model = "model-b")
    private val c = b.copy(id = "c", model = "model-c")
    private val quota = StreamEvent.Failure("quota exhausted", 429, "insufficient_quota", false)
    private val temporary = StreamEvent.Failure("overloaded", 503)
    private val history = listOf(ChatMessage(Role.USER, "Continue"),
        ChatMessage(Role.ASSISTANT, toolCalls = listOf(ToolCallData("done", "write_file", "{}"))),
        ChatMessage(Role.TOOL, "Saved", toolCallId = "done"))
    private val policy = FallbackPolicy(true, listOf(FallbackTarget(b.id, b.model), FallbackTarget(c.id, c.model)), retries = 2)

    private fun raw(block: suspend (ProviderConfig, String, RequestOptions) -> List<StreamEvent>) = object : LlmProvider {
        override fun streamChat(config: ProviderConfig, apiKey: String, systemPrompt: String, messages: List<ChatMessage>,
            tools: List<ToolSchema>, options: RequestOptions): Flow<StreamEvent> = flow {
            assertEquals(history, messages)
            block(config, apiKey, options).forEach { emit(it) }
        }
    }
    private fun router(block: suspend (ProviderConfig, String, RequestOptions) -> List<StreamEvent>,
        resolve: suspend (FallbackTarget) -> ProviderConfig? = { target -> listOf(a, b, c).firstOrNull { it.id == target.providerId }?.copy(model = target.model) },
        sleep: suspend (Long) -> Unit = {}, key: suspend (ProviderConfig) -> String = { "key-${it.id}" },
    ) = FallbackProvider({ raw(block) }, resolve, key, sleep = sleep)

    private suspend fun events(router: LlmProvider, config: ProviderConfig = a) =
        router.streamChat(config, "initial-key", "system", history, emptyList(), RequestOptions()).toList()

    @Test fun `quota switches immediately with destination credentials and intact history`() = runBlocking(FallbackRun { policy }) {
        val requests = mutableListOf<Pair<String, String>>()
        val result = events(router({ cfg, key, options ->
            requests += cfg.id to key; assertFalse(options.allowAccountSwitch)
            if (cfg.id == a.id) listOf(quota, StreamEvent.ToolCallReady(ToolCallData("bad", "shell", "{}")))
            else listOf(StreamEvent.TextDelta("continued"), StreamEvent.Done())
        }))
        assertEquals(listOf("a" to "key-a", "b" to "key-b"), requests)
        assertEquals(b, result.filterIsInstance<StreamEvent.ProviderChanged>().single().config)
        assertFalse(result.filterIsInstance<StreamEvent.ProviderChanged>().single().persistSelection)
        assertFalse(result.any { it is StreamEvent.Retrying || it is StreamEvent.ToolCallReady || it is StreamEvent.Failure })
    }

    @Test fun `transient failures retry each destination before advancing`() = runBlocking(FallbackRun { policy }) {
        val requests = mutableListOf<String>(); val sleeps = mutableListOf<Long>()
        val result = events(router({ cfg, _, _ -> requests += cfg.id; if (cfg.id == c.id) listOf(StreamEvent.Done()) else listOf(temporary) }, sleep = { sleeps += it }))
        assertEquals(listOf("a", "a", "a", "b", "b", "b", "c"), requests)
        assertEquals(listOf(1, 2, 1, 2), result.filterIsInstance<StreamEvent.Retrying>().map { it.attempt })
        assertEquals(4, sleeps.size)
    }

    @Test fun `ordinary rate limit retries but confirmed exhausted quota does not`() {
        assertEquals(FallbackFailureKind.TRANSIENT, fallbackFailureKind(StreamEvent.Failure("rate limit", 429)))
        assertEquals(FallbackFailureKind.QUOTA, fallbackFailureKind(quota))
        assertEquals(FallbackFailureKind.TERMINAL, fallbackFailureKind(StreamEvent.Failure("quota exhausted but invalid key", 401)))
        assertEquals(FallbackFailureKind.TERMINAL, fallbackFailureKind(StreamEvent.Failure("timeout", 400)))
        assertEquals(FallbackFailureKind.QUOTA, fallbackFailureKind(StreamEvent.Failure("payment required", 402)))
    }

    @Test fun `permanent auth invalid request and context errors never advance`() = runBlocking(FallbackRun { policy }) {
        for (failure in listOf(StreamEvent.Failure("Auth", 401), StreamEvent.Failure("Policy", 403),
            StreamEvent.Failure("Context too large", 400), StreamEvent.Failure("Model removed", 404))) {
            var requests = 0
            val result = events(router({ _, _, _ -> requests++; listOf(failure) }))
            assertEquals(1, requests); assertFalse(result.filterIsInstance<StreamEvent.Failure>().single().retryable!!)
        }
    }

    @Test fun `all forms of partial output prevent retry and switching`() = runBlocking(FallbackRun { policy }) {
        val outputs = listOf(StreamEvent.TextDelta("partial"), StreamEvent.ThinkingDelta("reason"),
            StreamEvent.ToolCallDelta("tool", "shell", "{"), StreamEvent.ToolCallReady(ToolCallData("tool", "shell", "{}")),
            StreamEvent.ToolCallBatch(emptyList()), StreamEvent.Usage(5, 1), StreamEvent.Done(),
            StreamEvent.Batch(listOf(StreamEvent.Batch(listOf(StreamEvent.ThinkingDelta("nested"))))))
        for (output in outputs) {
            var requests = 0
            val result = events(router({ _, _, _ -> requests++; listOf(output, quota) }))
            assertEquals(1, requests); assertFalse(result.any { it is StreamEvent.ProviderChanged || it is StreamEvent.Retrying })
            assertTrue(result.last() is StreamEvent.Failure)
        }
    }

    @Test fun `unavailable disabled and credentialless candidates are skipped without requests`() = runBlocking(FallbackRun { policy }) {
        val requests = mutableListOf<String>()
        val result = events(router({ cfg, _, _ -> requests += cfg.id; if (cfg.id == a.id) listOf(quota) else listOf(StreamEvent.Done()) },
            resolve = { target -> if (target.providerId == b.id) null else c }))
        assertEquals(listOf("a", "c"), requests); assertFalse(result.any { it is StreamEvent.Failure })
        requests.clear()
        events(router({ cfg, _, _ -> requests += cfg.id; listOf(quota) }, key = { if (it.id == b.id) "" else "key" }))
        assertEquals(listOf("a", "c"), requests)
    }

    @Test fun `same provider restriction blocks another connection and another pooled upstream`() = runBlocking {
        val same = a.copy(model = "other-model")
        for (destination in listOf<(ProviderConfig) -> String>({ it.baseUrl }, { if (it.model == a.model) "https://one.test" else "https://other.test" })) {
            val chain = policy.copy(sameProviderOnly = true, targets = listOf(FallbackTarget(b.id, b.model), FallbackTarget(a.id, same.model)))
            withContext(FallbackRun { chain }) {
                val requests = mutableListOf<String>()
                val provider = FallbackProvider({ raw { cfg, _, _ -> requests += cfg.model; if (cfg.model == a.model) listOf(quota) else listOf(StreamEvent.Done()) } },
                    { target -> if (target.providerId == b.id) b else same }, { "key" }, destination, sleep = {})
                val result = events(provider)
                if (destination(same) == destination(a)) assertEquals(listOf(a.model, same.model), requests)
                else { assertEquals(listOf(a.model), requests); assertTrue(result.last() is StreamEvent.Failure) }
            }
        }
    }

    @Test fun `disabling routing during lookup prevents transmission`() = runBlocking {
        var live: FallbackPolicy? = policy
        withContext(FallbackRun { live }) {
            val requests = mutableListOf<String>()
            val result = events(router({ cfg, _, _ -> requests += cfg.id; listOf(quota) }, resolve = { live = null; b }))
            assertEquals(listOf("a"), requests); assertTrue(result.last() is StreamEvent.Failure)
        }
    }

    @Test fun `zero retries and exhausted chain do not trigger outer retries`() = runBlocking(FallbackRun { policy.copy(retries = 0) }) {
        var requests = 0; val emitted = mutableListOf<StreamEvent>()
        val provider = router({ _, _, _ -> requests++; listOf(temporary) })
        val failure = StreamRetrier.run({ provider.streamChat(a, "key", "system", history, emptyList(), RequestOptions()) }, {}, { false },
            { emitted += it }, { it }, { fail("Unexpected outer retry") }, sleep = {})
        assertEquals(3, requests); assertNotNull(failure)
        assertEquals(2, emitted.filterIsInstance<StreamEvent.ProviderChanged>().size)
    }

    @Test fun `cancelled requests and failed collectors never advance`() = runBlocking(FallbackRun { policy }) {
        var requests = 0
        try { events(router({ _, _, _ -> requests++; throw CancellationException("Stopped") })); fail() } catch (_: CancellationException) {}
        assertEquals(1, requests)
        requests = 0
        val provider = router({ _, _, _ -> requests++; listOf(StreamEvent.TextDelta("hello")) })
        try { provider.streamChat(a, "key", "system", history, emptyList(), RequestOptions()).collect { throw IllegalStateException("Storage failed") }; fail() }
        catch (e: IllegalStateException) { assertEquals("Storage failed", e.message) }
        assertEquals(1, requests)
    }

    @Test fun `defaults custom and off survive serialization without device data in backup`() {
        val decoded = FallbackPolicy.decode(FallbackPolicy.encode(policy))
        assertEquals(policy, decoded); assertEquals(FallbackPolicy(), FallbackPolicy.decode("invalid"))
        assertFalse(policy.copy(targets = policy.targets + policy.targets).valid())
        assertFalse(policy.copy(retries = 6).valid())
        assertNull(effectiveFallback(FallbackPolicy(), FallbackMode.DEFAULT, policy))
        assertEquals(policy, effectiveFallback(policy, FallbackMode.CUSTOM, policy))
        assertFalse(effectiveFallback(policy, FallbackMode.OFF, policy)!!.enabled)
        val portable = SettingsBackupPolicy.exportSettings(AppSettings(fallbackPolicy = policy.copy(targets = policy.targets +
            FallbackTarget("chatgpt:device", "model") + FallbackTarget("local-model:device", "model"))), setOf("a", "b", "c", "chatgpt:device", "local-model:device"))
        assertEquals(policy.targets, portable.fallbackPolicy.targets)
    }

    @Test fun `explicit off disables legacy account rotation`() = runBlocking(FallbackRun { FallbackPolicy() }) {
        var allow = true
        events(router({ _, _, options -> allow = options.allowAccountSwitch; listOf(StreamEvent.Done()) }))
        assertFalse(allow)
    }

    @Test fun `real compat transport preserves quota categories and never leaks a credential across hosts`() = runBlocking(FallbackRun { policy }) {
        val requests = java.util.Collections.synchronizedList(mutableListOf<okhttp3.Request>())
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); requests += request
            val primary = request.url.host == "a.test"
            val body = if (primary) """{"error":{"code":"insufficient_quota","message":"Usage capped"}}"""
                else "data: {\"choices\":[{\"delta\":{\"content\":\"Continued\"}}]}\n\ndata: [DONE]\n\n"
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (primary) 429 else 200)
                .message("fixture").body(body.toResponseBody((if (primary) "application/json" else "text/event-stream").toMediaType())).build()
        }.build()
        val provider = FallbackProvider({ OpenAiCompatProvider(client, Json { ignoreUnknownKeys = true }) },
            { b }, { "key-${it.id}" }, sleep = {})
        val result = events(provider)
        assertEquals(listOf("a.test", "b.test"), requests.map { it.url.host })
        assertEquals(listOf("Bearer key-a", "Bearer key-b"), requests.map { it.header("Authorization") })
        assertEquals("Continued", result.filterIsInstance<StreamEvent.TextDelta>().single().text)
        assertFalse(result.any { it is StreamEvent.Retrying || it is StreamEvent.Failure })
        requests.forEach { request ->
            val buffer = okio.Buffer(); request.body!!.writeTo(buffer)
            assertTrue(buffer.readUtf8().contains("Saved"))
        }
    }

    @Test fun `privacy restriction applies to independently configured subagents`() = runBlocking(FallbackRun(a) { policy.copy(sameProviderOnly = true) }) {
        var requests = 0
        val result = events(router({ _, _, _ -> requests++; listOf(StreamEvent.Done()) }), config = b)
        assertEquals(0, requests); assertTrue(result.single() is StreamEvent.Failure)
    }

    @Test fun `stalled streams respect configured retries and cancellation`() = runBlocking(FallbackRun { policy.copy(retries = 0) }) {
        val attempted = mutableListOf<String>()
        val provider = FallbackProvider({ object : LlmProvider {
            override fun streamChat(config: ProviderConfig, apiKey: String, systemPrompt: String, messages: List<ChatMessage>,
                tools: List<ToolSchema>, options: RequestOptions): Flow<StreamEvent> = flow {
                attempted += config.id
                if (config.id == a.id) awaitCancellation() else { emit(StreamEvent.TextDelta("Recovered")); emit(StreamEvent.Done()) }
            }
        } }, { b }, { "key" }, sleep = {}, stallTimeoutMs = 20)
        assertEquals("Recovered", events(provider).filterIsInstance<StreamEvent.TextDelta>().single().text)
        assertEquals(listOf("a", "b"), attempted)
    }

    @Test fun `structured native errors preserve quota and temporary categories`() {
        val quota = Json.parseToJsonElement("""{"code":"insufficient_quota","message":"Usage capped"}""") as kotlinx.serialization.json.JsonObject
        assertEquals("insufficient_quota", providerErrorCode(quota))
        assertEquals(FallbackFailureKind.QUOTA, fallbackFailureKind(StreamEvent.Failure("Usage capped", errorCode = providerErrorCode(quota))))
        assertEquals(FallbackFailureKind.TRANSIENT, fallbackFailureKind(StreamEvent.Failure("Slow down", errorCode = "RESOURCE_EXHAUSTED")))
        assertEquals("insufficient_quota", ApiException(429, """{"error":{"code":"insufficient_quota"}}""").errorCode)
    }

    @Test fun `revoking a route during final credential lookup prevents the request`() = runBlocking {
        var live = policy
        var backupKeys = 0
        val requests = mutableListOf<String>()
        withContext(FallbackRun(a) { live }) {
            val result = events(router({ cfg, _, _ -> requests += cfg.id; listOf(quota) }, key = { cfg ->
                if (cfg.id == b.id && ++backupKeys == 2) live = live.copy(enabled = false)
                "key-${cfg.id}"
            }))
            assertEquals(listOf("a"), requests)
            assertTrue(result.filterIsInstance<StreamEvent.Failure>().single().message.contains("routing changed"))
        }
    }

    @Test fun `compaction follows the same allowed chain without changing the primary selection`() = runBlocking(FallbackRun(a) { policy.copy(retries = 0) }) {
        val requested = mutableListOf<String>()
        val changes = mutableListOf<StreamEvent.ProviderChanged>()
        val provider = FallbackProvider({ object : LlmProvider {
            override fun streamChat(config: ProviderConfig, apiKey: String, systemPrompt: String, messages: List<ChatMessage>,
                tools: List<ToolSchema>, options: RequestOptions): Flow<StreamEvent> = flow {
                requested += config.id
                assertTrue(tools.isEmpty()); assertFalse(options.allowAccountSwitch)
                if (config.id == a.id) emit(quota) else { emit(StreamEvent.TextDelta("Saved work summary")); emit(StreamEvent.Done()) }
            }
        } }, { b }, { "key-${it.id}" }, sleep = {})
        val summary = HistoryCompactor.summarize(provider, a, "primary-key", history, 100000, "compaction-fixture",
            event = { event, _ -> if (event is StreamEvent.ProviderChanged) changes += event })
        assertEquals("Saved work summary", summary)
        assertEquals(listOf("a", "b"), requested)
        assertFalse(changes.single().persistSelection)
    }

    @Test fun `a selected model already in the chain never skips earlier backup choices`() = runBlocking(FallbackRun(a) {
        policy.copy(targets = listOf(FallbackTarget(b.id, b.model), FallbackTarget(a.id, a.model), FallbackTarget(c.id, c.model)))
    }) {
        val requested = mutableListOf<String>()
        events(router({ cfg, _, _ -> requested += cfg.id; if (cfg.id == a.id) listOf(quota) else listOf(StreamEvent.Done()) }))
        assertEquals(listOf("a", "b"), requested)
    }
}

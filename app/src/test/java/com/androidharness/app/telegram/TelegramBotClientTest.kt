package com.androidharness.app.telegram

import java.net.InetAddress
import java.net.ServerSocket
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class TelegramBotClientTest {
    private val token = "123456:abcdefghijklmnopqrstuvwx"
    private fun server(status: Int, response: String, block: suspend (TelegramBotClient) -> Unit) = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val worker = Thread {
            server.accept().use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                var length = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                }
                repeat(length) { reader.read() }
                val bytes = response.toByteArray()
                socket.getOutputStream().write("HTTP/1.1 $status Test\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray() + bytes)
            }
        }.apply { isDaemon = true; start() }
        try { block(TelegramBotClient(token, baseUrl = "http://127.0.0.1:${server.localPort}")) }
        finally { server.close(); worker.join(1_000) }
    }

    @Test fun validatesRealBotIdentityShape() = server(200, """{"ok":true,"result":{"id":123456,"is_bot":true,"username":"fixture_bot"}}""") {
        assertEquals(TelegramBotIdentity(123456, "fixture_bot"), it.getMe())
    }

    @Test fun http200ApiErrorIsRejectedWithoutLeakingItsDescription() = server(200,
        """{"ok":false,"error_code":401,"description":"secret $token"}""") {
        try { it.getMe(); fail("API error accepted") }
        catch (e: TelegramSetupException) { assertEquals(TelegramSetupProblem.TOKEN_REJECTED, e.problem); assertFalse(e.message!!.contains(token)) }
    }

    @Test fun receiverConflictIsActionable() = server(409, "secret response") {
        try { it.getUpdates(0); fail("Conflict accepted") }
        catch (e: TelegramSetupException) { assertEquals(TelegramSetupProblem.RECEIVER_CONFLICT, e.problem) }
    }

    @Test fun malformedIdentityIsSafelyRejected() = server(200, """{"ok":true,"result":{"id":{},"is_bot":true,"username":"fixture_bot"}}""") {
        try { it.getMe(); fail("Malformed identity accepted") }
        catch (e: TelegramSetupException) { assertEquals(TelegramSetupProblem.RESPONSE, e.problem) }
    }

    @Test fun rateLimitIncludesServerRetryDelayWithoutExposingDescription() = server(429,
        """{"ok":false,"error_code":429,"description":"$token","parameters":{"retry_after":17}}""") {
        try { it.publish(42, "Test"); fail("Rate limit accepted") }
        catch (e: TelegramSetupException) {
            assertEquals(TelegramSetupProblem.RATE_LIMIT, e.problem)
            assertEquals(17, e.retryAfterSeconds)
            assertFalse(e.deliveryUncertain)
            assertFalse(e.message!!.contains(token))
        }
    }

    @Test fun publishedMessageReturnsIdForBoundButtonsAndProgressEdits() = server(200,
        """{"ok":true,"result":{"message_id":314,"chat":{"id":42,"type":"private"}}}""") {
        assertEquals(314L, it.publish(42, "<unsafe> & **bold**", listOf(listOf(TelegramButton("Approve once", "tg:nonce")))))
    }

    @Test fun unchangedEditIsSuccessfulForSafeRetry() = server(400,
        """{"ok":false,"error_code":400,"description":"Bad Request: message is not modified"}""") {
        it.edit(42, 314, "Already delivered")
    }

    @Test fun callbackUsesHumanSenderRatherThanBotsMessageAuthorAndRejectsForwardedChat() {
        val json = kotlinx.serialization.json.Json
        fun callback(chat: Long, bot: Boolean) = parseTelegramUpdate(json.parseToJsonElement("""{
            "update_id":8,"callback_query":{"id":"click","data":"tg:nonce",
            "from":{"id":42,"is_bot":$bot,"first_name":"Owner"},
            "message":{"message_id":314,"from":{"id":123456,"is_bot":true},"chat":{"id":$chat,"type":"private"}}}}
        """))!!
        assertEquals(42L, callback(42, false).account?.userId)
        assertEquals(TelegramCallback("click", 314, "tg:nonce"), callback(42, false).callback)
        assertNull(callback(99, false).account)
        assertNull(callback(99, false).callback)
        assertNull(callback(42, true).callback)
    }
}

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
}

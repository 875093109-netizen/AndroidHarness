package com.androidharness.app.telegram

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

internal data class TelegramBotIdentity(val id: Long, val username: String)
internal data class TelegramAccount(val userId: Long, val chatId: Long, val name: String)
internal data class TelegramUpdate(val id: Long, val account: TelegramAccount?, val text: String?, val sentAtSeconds: Long? = null)

internal enum class TelegramSetupProblem(val hint: String) {
    TOKEN_FORMAT("Check the bot token copied from BotFather."),
    TOKEN_REJECTED("Telegram rejected this token. Check BotFather and try again."),
    NETWORK("Could not reach Telegram. Check your connection and retry."),
    RATE_LIMIT("Telegram is busy. Wait a little, then retry."),
    RECEIVER_CONFLICT("Another receiver is using this bot. Stop it or use a separate bot."),
    RESPONSE("Telegram returned an unexpected response. Please retry."),
    PAIR_EXPIRED("Pairing expired. Start again for a new code."),
    PAIR_CHANGED("Bot setup changed. Pair your account again."),
    NOT_PAIRED("Pair your Telegram account first."),
    STORAGE("Could not save Telegram setup. Please retry."),
    KILO_CATALOG("Could not load Kilo's free models. Please retry."),
    KILO_MODEL("This Kilo model is no longer free or available. Refresh the model list."),
    KILO_RATE_LIMIT("Kilo's free service is busy. Wait a little or choose another free model."),
    KILO_REPLY("Kilo could not finish its reply. Please retry."),
    KILO_TIMEOUT("Kilo took too long to reply. Please retry or choose another model."),
    KILO_QUESTION("Send /ask followed by a question of up to 2,000 characters."),
    KILO_WAIT_EXPIRED("No question arrived in 10 minutes. Start listening again."),
}

internal class TelegramSetupException(val problem: TelegramSetupProblem) : Exception(problem.hint)

internal interface TelegramBotApi {
    suspend fun getMe(): TelegramBotIdentity
    suspend fun getUpdates(offset: Long): List<TelegramUpdate>
    suspend fun sendMessage(chatId: Long, text: String)
}

internal fun validTelegramToken(token: String): Boolean =
    token.matches(Regex("[0-9]{1,20}:[A-Za-z0-9_-]{20,200}"))

/** Setup transport only. Errors never include request URLs, tokens or response descriptions. */
internal class TelegramBotClient(
    private val token: String,
    private val client: OkHttpClient = defaultClient,
    private val baseUrl: String = "https://api.telegram.org",
) : TelegramBotApi {
    init { if (!validTelegramToken(token)) throw TelegramSetupException(TelegramSetupProblem.TOKEN_FORMAT) }

    override suspend fun getMe(): TelegramBotIdentity {
        val bot = call("getMe", buildJsonObject {}) as? JsonObject
            ?: throw TelegramSetupException(TelegramSetupProblem.RESPONSE)
        val id = (bot["id"] as? JsonPrimitive)?.longOrNull
        val username = (bot["username"] as? JsonPrimitive)?.contentOrNull
        if (id == null || id <= 0 || (bot["is_bot"] as? JsonPrimitive)?.booleanOrNull != true ||
            username == null || !username.matches(Regex("[A-Za-z0-9_]{5,32}"))) {
            throw TelegramSetupException(TelegramSetupProblem.RESPONSE)
        }
        return TelegramBotIdentity(id, username)
    }

    override suspend fun getUpdates(offset: Long): List<TelegramUpdate> {
        val updates = call("getUpdates", buildJsonObject {
            put("offset", offset)
            put("timeout", 20)
            put("allowed_updates", buildJsonArray { add("message") })
        }) as? JsonArray ?: throw TelegramSetupException(TelegramSetupProblem.RESPONSE)
        return updates.mapNotNull(::parseTelegramUpdate)
    }

    override suspend fun sendMessage(chatId: Long, text: String) {
        call("sendMessage", buildJsonObject { put("chat_id", chatId); put("text", text) })
    }

    private suspend fun call(method: String, payload: JsonObject): JsonElement = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url("$baseUrl/bot$token/$method")
            .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(TelegramSetupException(TelegramSetupProblem.NETWORK))
            }
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        if (!it.isSuccessful) throw telegramHttpError(it.code)
                        val source = it.body?.source() ?: throw TelegramSetupException(TelegramSetupProblem.RESPONSE)
                        source.request(MAX_RESPONSE_BYTES + 1)
                        if (source.buffer.size > MAX_RESPONSE_BYTES) throw TelegramSetupException(TelegramSetupProblem.RESPONSE)
                        val envelope = Json.parseToJsonElement(source.readUtf8()).jsonObject
                        if (envelope["ok"]?.jsonPrimitive?.booleanOrNull != true) {
                            throw telegramHttpError(envelope["error_code"]?.jsonPrimitive?.intOrNull ?: 0)
                        }
                        envelope["result"] ?: throw TelegramSetupException(TelegramSetupProblem.RESPONSE)
                    }
                }
                if (!continuation.isActive) return
                result.onSuccess { continuation.resume(it) }.onFailure {
                    continuation.resumeWithException(if (it is TelegramSetupException) it else TelegramSetupException(TelegramSetupProblem.RESPONSE))
                }
            }
        })
    }

    companion object {
        private const val MAX_RESPONSE_BYTES = 1024L * 1024
        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(35, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    }
}

private fun telegramHttpError(code: Int) = TelegramSetupException(when (code) {
    401, 404 -> TelegramSetupProblem.TOKEN_REJECTED
    409 -> TelegramSetupProblem.RECEIVER_CONFLICT
    429 -> TelegramSetupProblem.RATE_LIMIT
    else -> TelegramSetupProblem.RESPONSE
})

internal fun parseTelegramUpdate(element: JsonElement): TelegramUpdate? {
    val update = element as? JsonObject ?: return null
    val id = (update["update_id"] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 && it < Long.MAX_VALUE } ?: return null
    val message = update["message"] as? JsonObject
    val from = message?.get("from") as? JsonObject
    val chat = message?.get("chat") as? JsonObject
    val userId = (from?.get("id") as? JsonPrimitive)?.longOrNull
    val chatId = (chat?.get("id") as? JsonPrimitive)?.longOrNull
    val privateOwner = userId != null && userId > 0 && chatId == userId &&
        (chat?.get("type") as? JsonPrimitive)?.contentOrNull == "private" &&
        (from?.get("is_bot") as? JsonPrimitive)?.booleanOrNull == false
    val name = listOfNotNull((from?.get("first_name") as? JsonPrimitive)?.contentOrNull,
        (from?.get("last_name") as? JsonPrimitive)?.contentOrNull).joinToString(" ").take(120).ifBlank { "Telegram account" }
    return TelegramUpdate(id, if (privateOwner) TelegramAccount(userId!!, chatId!!, name) else null,
        (message?.get("text") as? JsonPrimitive)?.contentOrNull,
        (message?.get("date") as? JsonPrimitive)?.longOrNull)
}

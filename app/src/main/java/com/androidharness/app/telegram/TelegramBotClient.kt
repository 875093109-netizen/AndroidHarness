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
internal data class TelegramCallback(val id: String, val messageId: Long, val data: String)
internal data class TelegramButton(val text: String, val data: String)
internal data class TelegramUpdate(val id: Long, val account: TelegramAccount?, val text: String?, val sentAtSeconds: Long? = null,
    val messageId: Long? = null, val callback: TelegramCallback? = null)

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

}

internal class TelegramSetupException(val problem: TelegramSetupProblem, val retryAfterSeconds: Int? = null,
    val deliveryUncertain: Boolean = false) : Exception(problem.hint)

internal interface TelegramBotApi {
    suspend fun getMe(): TelegramBotIdentity
    suspend fun getUpdates(offset: Long): List<TelegramUpdate>
    suspend fun sendMessage(chatId: Long, text: String)
    suspend fun publish(chatId: Long, text: String, buttons: List<List<TelegramButton>> = emptyList()): Long {
        sendMessage(chatId, text)
        return 0
    }
    suspend fun edit(chatId: Long, messageId: Long, text: String, buttons: List<List<TelegramButton>> = emptyList()) { }
    suspend fun answerCallback(id: String, text: String) { }
    suspend fun webhookActive(): Boolean = false
    suspend fun registerCommands() { }
}

internal fun validTelegramToken(token: String): Boolean =
    token.matches(Regex("[0-9]{1,20}:[A-Za-z0-9_-]{20,200}"))

/** Bot transport. Errors never include request URLs, tokens or response descriptions. */
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
            put("allowed_updates", buildJsonArray { add("message"); add("callback_query") })
        }) as? JsonArray ?: throw TelegramSetupException(TelegramSetupProblem.RESPONSE)
        return updates.mapNotNull(::parseTelegramUpdate)
    }

    override suspend fun sendMessage(chatId: Long, text: String) {
        call("sendMessage", buildJsonObject { put("chat_id", chatId); put("text", text) })
    }

    override suspend fun publish(chatId: Long, text: String, buttons: List<List<TelegramButton>>): Long {
        val message = call("sendMessage", messagePayload(chatId, text, buttons)) as? JsonObject
            ?: throw TelegramSetupException(TelegramSetupProblem.RESPONSE, deliveryUncertain = true)
        return (message["message_id"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }
            ?: throw TelegramSetupException(TelegramSetupProblem.RESPONSE, deliveryUncertain = true)
    }

    override suspend fun edit(chatId: Long, messageId: Long, text: String, buttons: List<List<TelegramButton>>) {
        call("editMessageText", JsonObject(messagePayload(chatId, text, buttons) + ("message_id" to JsonPrimitive(messageId))))
    }

    override suspend fun answerCallback(id: String, text: String) {
        call("answerCallbackQuery", buildJsonObject { put("callback_query_id", id); put("text", text.take(180)) })
    }

    override suspend fun webhookActive(): Boolean = (call("getWebhookInfo", buildJsonObject {}) as? JsonObject)
        ?.get("url")?.jsonPrimitive?.contentOrNull?.isNotEmpty() == true

    override suspend fun registerCommands() {
        call("setMyCommands", buildJsonObject { put("commands", buildJsonArray {
            listOf("help" to "Show controls", "new" to "Start a new chat", "projects" to "Choose a project", "chats" to "Choose a chat",
                "model" to "Choose a model", "status" to "Show project, model and task status", "stop" to "Stop the task and its queue",
                "retry" to "Retry or resume a saved request", "mode" to "Switch between plan and act", "pause" to "Pause Telegram").forEach { (name, description) ->
                add(buildJsonObject { put("command", name); put("description", description) })
            }
        }) })
    }

    private fun messagePayload(chatId: Long, text: String, buttons: List<List<TelegramButton>>) = buildJsonObject {
        put("chat_id", chatId)
        put("text", telegramHtml(text))
        put("parse_mode", "HTML")
        put("link_preview_options", buildJsonObject { put("is_disabled", true) })
        put("reply_markup", buildJsonObject { put("inline_keyboard", buildJsonArray {
            buttons.forEach { row -> add(buildJsonArray { row.forEach { button ->
                require(button.data.toByteArray().size in 1..64)
                add(buildJsonObject { put("text", button.text.take(80)); put("callback_data", button.data) })
            } }) }
        }) })
    }

    private suspend fun call(method: String, payload: JsonObject): JsonElement = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url("$baseUrl/bot$token/$method")
            .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(TelegramSetupException(TelegramSetupProblem.NETWORK, deliveryUncertain = true))
            }
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        val source = it.body?.source() ?: throw TelegramSetupException(TelegramSetupProblem.RESPONSE)
                        source.request(MAX_RESPONSE_BYTES + 1)
                        if (source.buffer.size > MAX_RESPONSE_BYTES) throw TelegramSetupException(TelegramSetupProblem.RESPONSE)
                        val envelope = runCatching { Json.parseToJsonElement(source.readUtf8()).jsonObject }.getOrNull()
                            ?: throw if (!it.isSuccessful) telegramHttpError(it.code)
                                else TelegramSetupException(TelegramSetupProblem.RESPONSE, deliveryUncertain = true)
                        if (method == "editMessageText" && it.code == 400 &&
                            (envelope["description"] as? JsonPrimitive)?.contentOrNull?.contains("message is not modified", true) == true)
                            return@use JsonPrimitive(true)
                        val retryAfter = (envelope["parameters"] as? JsonObject)?.get("retry_after")?.jsonPrimitive?.intOrNull
                        if (!it.isSuccessful) throw telegramHttpError(it.code, retryAfter)
                        if (envelope["ok"]?.jsonPrimitive?.booleanOrNull != true) {
                            throw telegramHttpError(envelope["error_code"]?.jsonPrimitive?.intOrNull ?: 0, retryAfter)
                        }
                        envelope["result"] ?: throw TelegramSetupException(TelegramSetupProblem.RESPONSE)
                    }
                }
                if (!continuation.isActive) return
                result.onSuccess { continuation.resume(it) }.onFailure {
                    continuation.resumeWithException(if (it is TelegramSetupException) it else TelegramSetupException(TelegramSetupProblem.RESPONSE, deliveryUncertain = true))
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

private fun telegramHttpError(code: Int, retryAfter: Int? = null) = TelegramSetupException(when (code) {
    401, 404 -> TelegramSetupProblem.TOKEN_REJECTED
    409 -> TelegramSetupProblem.RECEIVER_CONFLICT
    429 -> TelegramSetupProblem.RATE_LIMIT
    else -> TelegramSetupProblem.RESPONSE
}, retryAfter?.coerceIn(1, 86_400))

internal fun parseTelegramUpdate(element: JsonElement): TelegramUpdate? {
    val update = element as? JsonObject ?: return null
    val id = (update["update_id"] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 && it < Long.MAX_VALUE } ?: return null
    val callback = update["callback_query"] as? JsonObject
    val message = (update["message"] ?: callback?.get("message")) as? JsonObject
    val from = (callback?.get("from") ?: message?.get("from")) as? JsonObject
    val chat = message?.get("chat") as? JsonObject
    val userId = (from?.get("id") as? JsonPrimitive)?.longOrNull
    val chatId = (chat?.get("id") as? JsonPrimitive)?.longOrNull
    val privateOwner = userId != null && userId > 0 && chatId == userId &&
        (chat?.get("type") as? JsonPrimitive)?.contentOrNull == "private" &&
        (from?.get("is_bot") as? JsonPrimitive)?.booleanOrNull == false
    val name = listOfNotNull((from?.get("first_name") as? JsonPrimitive)?.contentOrNull,
        (from?.get("last_name") as? JsonPrimitive)?.contentOrNull).joinToString(" ").take(120).ifBlank { "Telegram account" }
    val messageId = (message?.get("message_id") as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }
    val callbackId = (callback?.get("id") as? JsonPrimitive)?.contentOrNull
    val callbackData = (callback?.get("data") as? JsonPrimitive)?.contentOrNull
    val action = if (privateOwner && messageId != null && !callbackId.isNullOrBlank() &&
        callbackData != null && callbackData.toByteArray().size <= 64) TelegramCallback(callbackId, messageId, callbackData) else null
    return TelegramUpdate(id, if (privateOwner) TelegramAccount(userId!!, chatId!!, name) else null,
        if (callback == null) (message?.get("text") as? JsonPrimitive)?.contentOrNull else null,
        (message?.get("date") as? JsonPrimitive)?.longOrNull, messageId, action)
}

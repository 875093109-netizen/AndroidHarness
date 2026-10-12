package com.androidharness.app.telegram

import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal enum class TelegramRequestStatus { RECEIVED, QUEUED, STARTING, RUNNING, COMPLETED, FAILED, INTERRUPTED, CANCELLED }
internal enum class TelegramDeliveryStatus { PENDING, SENDING, SENT, FAILED, UNCERTAIN }

@Serializable internal data class TelegramRequest(
    val id: Long, val text: String, val receivedAt: Long,
    val callback: TelegramStoredCallback? = null,
    val status: TelegramRequestStatus = TelegramRequestStatus.RECEIVED,
    val sessionId: String? = null, val projectId: String? = null,
    val providerId: String? = null, val model: String? = null, val mode: String = "ACT",
    val turnId: String? = null, val progressMessageId: Long? = null,
    val lastProgress: String? = null, val error: String? = null, val resume: Boolean = false,
    val attempt: Int = 0,
    val decisionKey: String? = null,
) {
    fun promptId(binding: String) = "telegram-${binding.replace(':', '-') }-$id"
    val active get() = status in listOf(TelegramRequestStatus.STARTING, TelegramRequestStatus.RUNNING)
    val retryable get() = status in listOf(TelegramRequestStatus.FAILED, TelegramRequestStatus.INTERRUPTED)
}
@Serializable internal data class TelegramStoredCallback(val id: String, val messageId: Long, val data: String)
@Serializable internal data class TelegramStoredButton(val text: String, val actionId: String)
@Serializable internal data class TelegramAction(
    val id: String = UUID.randomUUID().toString().replace("-", "").take(20),
    val kind: String, val value: String = "", val extra: String = "",
    val sessionId: String? = null, val projectId: String? = null, val turnId: String? = null, val callId: String? = null,
    val messageId: Long? = null, val expiresAt: Long, val consumed: Boolean = false,
    val mode: String? = null,
)
@Serializable internal data class TelegramDelivery(
    val key: String, val text: String,
    val buttons: List<List<TelegramStoredButton>> = emptyList(),
    val messageId: Long? = null, val requestId: Long? = null, val progress: Boolean = false,
    val status: TelegramDeliveryStatus = TelegramDeliveryStatus.PENDING,
    val attempts: Int = 0, val retryAt: Long = 0, val error: String? = null,
    val priority: Int = 1,
)
@Serializable internal data class TelegramGatewayRecord(
    val binding: String = "", val botId: Long = 0, val offset: Long = 0,
    val requests: List<TelegramRequest> = emptyList(), val deliveries: List<TelegramDelivery> = emptyList(),
    val actions: List<TelegramAction> = emptyList(),
)

/** Atomic inbox + cursor. Never acknowledges an update before its authorized request is durable. */
internal class TelegramGatewayStore(private val read: () -> String?, private val write: (String) -> Unit) {
    constructor(file: File) : this({ if (file.exists()) file.readText() else null }, { text ->
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(temporary).use { out -> out.write(text.toByteArray()); out.fd.sync() }
        check(temporary.renameTo(file)) { "Could not save Telegram requests." }
    })
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val loaded = runCatching { read()?.let { json.decodeFromString(TelegramGatewayRecord.serializer(), it) } ?: TelegramGatewayRecord() }
    val loadError = loaded.exceptionOrNull()?.let { "Telegram's saved requests could not be read. App chats are still available." }
    private val mutable = MutableStateFlow(loaded.getOrDefault(TelegramGatewayRecord()).let { saved ->
        saved.copy(deliveries = saved.deliveries.map { if (it.status == TelegramDeliveryStatus.SENDING)
            it.copy(status = if (it.messageId != null) TelegramDeliveryStatus.PENDING else TelegramDeliveryStatus.UNCERTAIN,
                error = "Delivery was interrupted. Check Telegram before resending.") else it })
    })
    val state = mutable.asStateFlow()
    fun recoverSending() = change { saved -> saved.copy(deliveries = saved.deliveries.map {
        if (it.status == TelegramDeliveryStatus.SENDING) it.copy(
            status = if (it.messageId == null) TelegramDeliveryStatus.UNCERTAIN else TelegramDeliveryStatus.PENDING,
            error = "Delivery was interrupted. Check Telegram before resending.") else it
    }) }
    @Synchronized fun change(block: (TelegramGatewayRecord) -> TelegramGatewayRecord): TelegramGatewayRecord {
        check(loadError == null) { requireNotNull(loadError) }
        val next = block(mutable.value)
        require(next.offset >= 0)
        write(json.encodeToString(TelegramGatewayRecord.serializer(), next))
        mutable.value = next
        return next
    }
    @Synchronized fun bind(connection: TelegramConnection) {
        val binding = telegramBinding(connection)
        if (mutable.value.binding == binding) return
        change { previous -> TelegramGatewayRecord(binding, connection.botId,
            maxOf(connection.offset, previous.offset.takeIf { previous.botId == connection.botId } ?: 0)) }
    }
    @Synchronized fun ingest(updates: List<TelegramUpdate>, owner: Long, now: Long) = change { saved ->
        val known = saved.requests.mapTo(HashSet()) { it.id }
        var pending = saved.requests.count { it.status in listOf(TelegramRequestStatus.RECEIVED, TelegramRequestStatus.QUEUED) }
        val received = updates.filter { it.id >= saved.offset && it.account?.userId == owner && it.account?.chatId == owner &&
            (it.text != null || it.callback != null) && known.add(it.id) }.map { update ->
            val callback = update.callback?.let { TelegramStoredCallback(it.id, it.messageId, it.data) }
            val text = update.text.orEmpty()
            val command = text.trim().substringBefore(' ').substringBefore('\n').substringBefore('@').lowercase()
            val prompt = callback == null && (!command.startsWith('/') || command == "/ask")
            val rejected = text.length > 12_000 || pending >= 100 && prompt
            if (!rejected && prompt) pending++
            TelegramRequest(update.id, text.take(12_000), now, callback,
                status = if (rejected) TelegramRequestStatus.FAILED else TelegramRequestStatus.RECEIVED,
                error = if (text.length > 12_000) "Message is too long. Send up to 12,000 characters." else if (rejected) "Queue is full. Use /stop or retry later." else null)
        }
        saved.copy(offset = maxOf(saved.offset, updates.maxOfOrNull { it.id + 1 } ?: saved.offset),
            requests = pruneRequests(saved.requests + received),
            deliveries = saved.deliveries + received.filter { it.status == TelegramRequestStatus.FAILED }.map {
                TelegramDelivery("rejected-${it.id}", "Request #${it.id}: ${it.error}", priority = 2)
            })
    }
    fun request(id: Long) = state.value.requests.firstOrNull { it.id == id }
    fun updateRequest(id: Long, transform: (TelegramRequest) -> TelegramRequest) = change { saved ->
        saved.copy(requests = saved.requests.map { if (it.id == id) transform(it) else it })
    }
    fun enqueue(delivery: TelegramDelivery, actions: List<TelegramAction> = emptyList()) = change { saved ->
        if (saved.deliveries.any { it.key == delivery.key }) saved else saved.copy(
            deliveries = saved.deliveries.filter { it.status == TelegramDeliveryStatus.SENT }.takeLast(200) +
                saved.deliveries.filter { it.status != TelegramDeliveryStatus.SENT } + delivery,
            actions = saved.actions.filter { !it.consumed && it.expiresAt > System.currentTimeMillis() } + actions)
    }
    fun updateDelivery(key: String, transform: (TelegramDelivery) -> TelegramDelivery) = change { saved ->
        saved.copy(deliveries = saved.deliveries.map { if (it.key == key) transform(it) else it })
    }
    fun consumeAction(id: String, messageId: Long, now: Long): TelegramAction? {
        var found: TelegramAction? = null
        change { saved -> saved.copy(actions = saved.actions.map {
            if (it.id == id && !it.consumed && it.expiresAt > now && it.messageId == messageId) {
                found = it; it.copy(consumed = true)
            } else it
        }) }
        return found
    }
    fun delivered(key: String, messageId: Long) = change { saved ->
        val delivery = saved.deliveries.first { it.key == key }
        val ids = delivery.buttons.flatten().mapTo(HashSet()) { it.actionId }
        saved.copy(deliveries = saved.deliveries.map { if (it.key == key) it.copy(status = TelegramDeliveryStatus.SENT, error = null, messageId = messageId) else it },
            actions = saved.actions.map { if (it.id in ids) it.copy(messageId = messageId) else it },
            requests = saved.requests.map { if (delivery.progress && it.id == delivery.requestId && it.progressMessageId == null)
                it.copy(progressMessageId = messageId) else it })
    }
    private fun pruneRequests(rows: List<TelegramRequest>): List<TelegramRequest> {
        fun terminal(row: TelegramRequest) = row.status in listOf(TelegramRequestStatus.COMPLETED, TelegramRequestStatus.CANCELLED) ||
            row.status == TelegramRequestStatus.FAILED && row.sessionId == null
        val kept = rows.filter(::terminal).takeLast(200).mapTo(HashSet()) { it.id }
        return rows.filter { !terminal(it) || it.id in kept }
    }
}

internal fun telegramBinding(connection: TelegramConnection) = "${connection.botId}:${requireNotNull(connection.ownerId)}"

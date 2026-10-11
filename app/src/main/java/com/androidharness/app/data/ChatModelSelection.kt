package com.androidharness.app.data

import com.androidharness.app.agent.ThinkingLevel
import com.androidharness.app.llm.ProviderConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class ChatModelTarget { ACTIVE, PLANNING, EXECUTION }

/** Model IDs are resolved when saved so editing a provider's default cannot change a chat. */
@Serializable
data class ChatModelChoice(val providerId: String, val model: String)

@Serializable
data class ChatModelSelection(
    val active: ChatModelChoice? = null,
    val planning: ChatModelChoice? = null,
    val execution: ChatModelChoice? = null,
    val thinking: ThinkingLevel = ThinkingLevel.OFF,
) {
    fun choose(target: ChatModelTarget, choice: ChatModelChoice): ChatModelSelection = when (target) {
        ChatModelTarget.ACTIVE -> copy(active = choice)
        ChatModelTarget.PLANNING -> copy(planning = choice)
        ChatModelTarget.EXECUTION -> copy(execution = choice)
    }

    fun switchIfMatching(from: ProviderConfig, to: ProviderConfig): ChatModelSelection {
        fun ChatModelChoice?.switched() = if (this?.providerId == from.id && model == from.model)
            ChatModelChoice(to.id, to.model) else this
        return copy(active = active.switched(), planning = planning.switched(), execution = execution.switched())
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun decode(value: String?): ChatModelSelection? = value?.let {
            try { json.decodeFromString(serializer(), it) }
            catch (e: Exception) { error("This chat's saved models could not be read. Choose a model again.") }
        }
        fun encode(value: ChatModelSelection): String = json.encodeToString(serializer(), value)

        fun from(settings: AppSettings, providers: List<ProviderConfig>): ChatModelSelection {
            fun choice(providerId: String?, model: String?, fallback: ChatModelChoice?): ChatModelChoice? {
                val id = providerId ?: fallback?.providerId ?: providers.firstOrNull()?.id ?: return null
                val concrete = model?.takeIf { it.isNotBlank() }
                    ?: providers.firstOrNull { it.id == id }?.model
                    ?: fallback?.takeIf { it.providerId == id }?.model ?: return null
                return ChatModelChoice(id, concrete)
            }
            val active = choice(settings.activeProviderId, settings.activeModel, null)
            return ChatModelSelection(active,
                choice(settings.planningProviderId, settings.planningModel, active),
                choice(settings.executionProviderId, settings.executionModel, active), settings.thinkingLevel)
        }
    }
}

data class ChatModelState(val settings: AppSettings, val providers: List<ProviderConfig>,
    val selection: ChatModelSelection, val error: String? = null)

/** One shared preference chooses the scope; per-chat snapshots never overwrite the shared defaults. */
class ChatModelRepository(
    private val settings: SettingsRepository,
    private val providers: ProviderRepository,
    private val sessions: SessionRepository,
) {
    private val edits = Mutex()

    fun observe(sessionId: String?): Flow<ChatModelState> = combine(
        settings.settings, providers.providers,
        sessionId?.let(sessions::sessionFlow) ?: flowOf(null),
    ) { prefs, available, session ->
        val shared = ChatModelSelection.from(prefs, available)
        if (prefs.sameModelAcrossChats || sessionId == null) ChatModelState(prefs, available, shared)
        else try {
            check(session != null) { "This chat is no longer available." }
            val selection = ChatModelSelection.decode(session.modelSelectionJson)
                ?: edits.withLock { sessions.initializeModelSelection(sessionId, shared) }
            ChatModelState(prefs, available, selection)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            ChatModelState(prefs, available, ChatModelSelection(), e.message)
        }
    }

    suspend fun initialize(sessionId: String) = edits.withLock {
        val prefs = settings.settings.first()
        if (!prefs.sameModelAcrossChats) sessions.initializeModelSelection(sessionId,
            ChatModelSelection.from(prefs, providers.providers.first()))
    }

    suspend fun setShared(sessionId: String, shared: Boolean) = edits.withLock {
        val prefs = settings.settings.first()
        if (prefs.sameModelAcrossChats == shared) return@withLock
        val selection = sessions.initializeModelSelection(sessionId,
            ChatModelSelection.from(prefs, providers.providers.first()))
        // Returning to shared mode uses this chat's choice, without erasing any saved chat choices.
        settings.setModelSelectionScope(shared, if (shared) selection else null)
    }

    suspend fun select(sessionId: String?, target: ChatModelTarget, providerId: String, model: String?) = edits.withLock {
        val prefs = settings.settings.first()
        val provider = providers.providers.first().firstOrNull { it.id == providerId }
            ?: error("This provider is no longer available. Choose another provider.")
        val choice = ChatModelChoice(providerId, model?.takeIf { it.isNotBlank() } ?: provider.model)
        if (prefs.sameModelAcrossChats) {
            when (target) {
                ChatModelTarget.ACTIVE -> settings.setActiveSelection(choice.providerId, model)
                ChatModelTarget.PLANNING -> settings.setPlanningModel(choice.providerId, model)
                ChatModelTarget.EXECUTION -> settings.setExecutionModel(choice.providerId, model)
            }
        } else {
            val sid = requireNotNull(sessionId) { "Open a chat before choosing its model." }
            // An explicit pick also repairs unreadable selection metadata; messages remain untouched.
            val old = runCatching { ChatModelSelection.decode(sessions.session(sid)?.modelSelectionJson) }
                .getOrNull() ?: ChatModelSelection.from(prefs, providers.providers.first())
            sessions.saveModelSelection(sid, old.choose(target, choice))
        }
    }

    suspend fun setThinking(sessionId: String?, level: ThinkingLevel) = edits.withLock {
        val prefs = settings.settings.first()
        if (prefs.sameModelAcrossChats) settings.setThinkingLevel(level)
        else {
            val sid = requireNotNull(sessionId) { "Open a chat before choosing its thinking level." }
            val selection = sessions.initializeModelSelection(sid,
                ChatModelSelection.from(prefs, providers.providers.first()))
            sessions.saveModelSelection(sid, selection.copy(thinking = level))
        }
    }

    /** Account rotation may update only the selection that still matches the run, never another chat. */
    suspend fun providerChanged(sessionId: String, from: ProviderConfig, to: ProviderConfig) = edits.withLock {
        if (settings.settings.first().sameModelAcrossChats) settings.switchActiveSelection(from, to)
        else {
            val old = ChatModelSelection.decode(sessions.session(sessionId)?.modelSelectionJson) ?: return@withLock
            val next = old.switchIfMatching(from, to)
            if (next != old) sessions.saveModelSelection(sessionId, next)
        }
    }
}

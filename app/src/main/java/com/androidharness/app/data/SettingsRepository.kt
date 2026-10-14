package com.androidharness.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.androidharness.app.agent.PermissionMode
import com.androidharness.app.agent.ThinkingLevel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.settingsStore by preferencesDataStore(name = "settings")

private val WEB_SEARCH_PROVIDERS = setOf("keyless", "brave", "tavily")

enum class ThemeMode { SYSTEM, LIGHT, DARK, AMOLED }

@kotlinx.serialization.Serializable
data class AppSettings(
    val permissionMode: PermissionMode = PermissionMode.CONFIRM_RISKY,
    val subagentFullAccess: Boolean = false,
    val subagentProviderId: String? = null,
    val subagentModel: String? = null,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val workspaceLayout: WorkspaceLayoutMode = WorkspaceLayoutMode.AUTO,
    val activeProviderId: String? = null,
    val providerOrder: List<String> = emptyList(),
    val disabledProviderIds: Set<String> = emptySet(),
    val fallbackPolicy: com.androidharness.app.llm.FallbackPolicy = com.androidharness.app.llm.FallbackPolicy(),
    val thinkingLevel: ThinkingLevel = ThinkingLevel.OFF,
    val maxContextTokens: Int = DEFAULT_MAX_CONTEXT,
    val maxOutputTokens: Int = DEFAULT_MAX_OUTPUT,
    /** Tool-call iterations per run. 0 = unlimited. */
    val maxIterations: Int = DEFAULT_MAX_ITERATIONS,
    /** Hold a wakelock while a run is active so runs survive screen-off. */
    val keepAlive: Boolean = true,
    /** Drawer: pinned / archived session ids (DataStore, no DB migration). */
    val pinnedSessions: Set<String> = emptySet(),
    val archivedSessions: Set<String> = emptySet(),
    /** First-run setup was finished or skipped; stops the setup gate. */
    val onboardingDone: Boolean = false,
    /**
     * Model selected from the active provider's catalog. Null = use the
     * provider entry's saved default model.
     */
    val activeModel: String? = null,
    /** On keeps the existing shared picker; off restores each chat's saved models. */
    val sameModelAcrossChats: Boolean = true,
    /** Skill names hidden from the catalog and slash picker. */
    val disabledSkills: Set<String> = emptySet(),
    /** web_search backend: "keyless" (default) | "brave" | "tavily". */
    val webSearchProvider: String = "keyless",
    /**
     * Screenshots are allowed by default outside credential screens. Off blocks
     * them app wide; screens that show keys and tokens always block.
     */
    val allowScreenshots: Boolean = true,
    /**
     * Separate models per agent mode: plan-mode runs use the planning
     * provider/model, everything else uses the execution one. Off (default)
     * runs everything on the single active model.
     */
    val planningModelsEnabled: Boolean = false,
    val planningProviderId: String? = null,
    val planningModel: String? = null,
    val executionProviderId: String? = null,
    val executionModel: String? = null,
    /** The one-time "you can now use two models" chat dialog was dismissed. */
    val planningModelsPromoSeen: Boolean = false,
    /** The one-time introductory fork dialog was dismissed. */
    val forkPromoSeen: Boolean = false,
    /** Biometric app lock: requires fingerprint/face/PIN authentication to open. */
    val biometricLockEnabled: Boolean = false,
    /** Grace period in minutes before re-locking when app is backgrounded (0 = immediately). */
    val biometricLockTimeoutMinutes: Int = 0,
    /** Voice speech-to-text engine: "inbuilt" (native Android recognizer) or "groq" (Groq Whisper API). */
    val voiceEngine: String = "inbuilt",
    /** Groq Whisper model: "whisper-large-v3" (default) or "whisper-large-v3-turbo". */
    val groqWhisperModel: String = "whisper-large-v3",
    /** The one-time voice configuration promo dialog was dismissed. */
    val voicePromoSeen: Boolean = false,
    /** Resume the last opened chat session when app launches. */
    val resumeLastChat: Boolean = true,
    val showLiveCode: Boolean = true,
    /** ID of the last active chat session. */
    val lastActiveSessionId: String? = null,
    /** Generate and inject compact codebase symbol index (Repo map) into agent context. */
    val repoMapEnabled: Boolean = true,
    /** Local models start in plain chat until the user enables agent context. */
    val localModelAgentContext: Boolean = false,
    val cavemanInstalled: Boolean = false,
    val cavemanIntensity: com.androidharness.app.caveman.CavemanIntensity = com.androidharness.app.caveman.CavemanIntensity.OFF,
    val cavemanWenyan: Boolean = false,
) {
    companion object {
        const val DEFAULT_MAX_CONTEXT = 1_000_000
        const val DEFAULT_MAX_OUTPUT = 32_768
        const val DEFAULT_MAX_ITERATIONS = 0 // unlimited
        const val VOICE_ENGINE_INBUILT = "inbuilt"
        const val VOICE_ENGINE_GROQ = "groq"
        const val GROQ_MODEL_WHISPER_V3 = "whisper-large-v3"
        const val GROQ_MODEL_WHISPER_TURBO = "whisper-large-v3-turbo"
    }
}

class SettingsRepository(private val context: Context) {
    suspend fun setFallbackPolicy(policy: com.androidharness.app.llm.FallbackPolicy) {
        val encoded = com.androidharness.app.llm.FallbackPolicy.encode(policy)
        context.settingsStore.edit { it[Keys.FALLBACK_POLICY] = encoded }
    }

    private object Keys {
        val PERMISSION_MODE = stringPreferencesKey("permission_mode")
        val SUBAGENT_FULL_ACCESS = booleanPreferencesKey("subagent_full_access")
        val SUBAGENT_PROVIDER = stringPreferencesKey("subagent_provider_id")
        val SUBAGENT_MODEL = stringPreferencesKey("subagent_model")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val WORKSPACE_LAYOUT = stringPreferencesKey("workspace_layout")
        val ACTIVE_PROVIDER = stringPreferencesKey("active_provider_id")
        val PROVIDER_ORDER = stringPreferencesKey("provider_order")
        val DISABLED_PROVIDERS = stringSetPreferencesKey("disabled_providers")
        val FALLBACK_POLICY = stringPreferencesKey("fallback_policy")
        val THINKING_LEVEL = stringPreferencesKey("thinking_level")
        val MAX_CONTEXT = intPreferencesKey("max_context_tokens")
        val MAX_OUTPUT = intPreferencesKey("max_output_tokens")
        val MAX_ITERATIONS = intPreferencesKey("max_iterations")
        val KEEP_ALIVE = booleanPreferencesKey("keep_alive")
        val PINNED = stringSetPreferencesKey("pinned_sessions")
        val ARCHIVED = stringSetPreferencesKey("archived_sessions")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val ACTIVE_MODEL = stringPreferencesKey("active_model")
        val SAME_MODEL_ACROSS_CHATS = booleanPreferencesKey("same_model_across_chats")
        val DISABLED_SKILLS = stringSetPreferencesKey("disabled_skills")
        val WEB_SEARCH_PROVIDER = stringPreferencesKey("web_search_provider")
        val ALLOW_SCREENSHOTS = booleanPreferencesKey("allow_screenshots")
        val PLANNING_MODELS_ENABLED = booleanPreferencesKey("planning_models_enabled")
        val PLANNING_PROVIDER = stringPreferencesKey("planning_provider_id")
        val PLANNING_MODEL = stringPreferencesKey("planning_model")
        val EXECUTION_PROVIDER = stringPreferencesKey("execution_provider_id")
        val EXECUTION_MODEL = stringPreferencesKey("execution_model")
        val PLANNING_PROMO_SEEN = booleanPreferencesKey("planning_promo_seen")
        val FORK_PROMO_SEEN = booleanPreferencesKey("fork_promo_seen")
        val BIOMETRIC_LOCK_ENABLED = booleanPreferencesKey("biometric_lock_enabled")
        val BIOMETRIC_LOCK_TIMEOUT = intPreferencesKey("biometric_lock_timeout_minutes")
        val VOICE_ENGINE = stringPreferencesKey("voice_engine")
        val GROQ_WHISPER_MODEL = stringPreferencesKey("groq_whisper_model")
        val VOICE_PROMO_SEEN = booleanPreferencesKey("voice_promo_seen")
        val RESUME_LAST_CHAT = booleanPreferencesKey("resume_last_chat")
        val SHOW_LIVE_CODE = booleanPreferencesKey("show_live_code")
        val LAST_ACTIVE_SESSION = stringPreferencesKey("last_active_session_id")
        val REPO_MAP_ENABLED = booleanPreferencesKey("repo_map_enabled")
        val LOCAL_MODEL_AGENT_CONTEXT = booleanPreferencesKey("local_model_agent_context")
        val CAVEMAN_INSTALLED = booleanPreferencesKey("caveman_installed")
        val CAVEMAN_INTENSITY = stringPreferencesKey("caveman_intensity")
        val CAVEMAN_WENYAN = booleanPreferencesKey("caveman_wenyan")
    }

    val settings: Flow<AppSettings> = context.settingsStore.data.map { prefs ->
        AppSettings(
            permissionMode = prefs[Keys.PERMISSION_MODE]
                ?.let { runCatching { PermissionMode.valueOf(it) }.getOrNull() }
                ?: PermissionMode.CONFIRM_RISKY,
            subagentFullAccess = prefs[Keys.SUBAGENT_FULL_ACCESS] ?: false,
            themeMode = prefs[Keys.THEME_MODE]
                ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.SYSTEM,
            dynamicColor = prefs[Keys.DYNAMIC_COLOR] ?: true,
            workspaceLayout = WorkspaceLayoutMode.fromStored(prefs[Keys.WORKSPACE_LAYOUT])
                ?: WorkspaceLayoutMode.AUTO,
            activeProviderId = prefs[Keys.ACTIVE_PROVIDER] ?: com.androidharness.app.llm.HarnessProvider.ID,
            providerOrder = ProviderListPreferences.decodeOrder(prefs[Keys.PROVIDER_ORDER]),
            disabledProviderIds = prefs[Keys.DISABLED_PROVIDERS] ?: emptySet(),
            fallbackPolicy = com.androidharness.app.llm.FallbackPolicy.decode(prefs[Keys.FALLBACK_POLICY]),
            thinkingLevel = prefs[Keys.THINKING_LEVEL]
                ?.let { runCatching { ThinkingLevel.valueOf(it) }.getOrNull() }
                ?: ThinkingLevel.OFF,
            maxContextTokens = prefs[Keys.MAX_CONTEXT] ?: AppSettings.DEFAULT_MAX_CONTEXT,
            maxOutputTokens = prefs[Keys.MAX_OUTPUT] ?: AppSettings.DEFAULT_MAX_OUTPUT,
            maxIterations = prefs[Keys.MAX_ITERATIONS] ?: AppSettings.DEFAULT_MAX_ITERATIONS,
            keepAlive = prefs[Keys.KEEP_ALIVE] ?: true,
            pinnedSessions = prefs[Keys.PINNED] ?: emptySet(),
            archivedSessions = prefs[Keys.ARCHIVED] ?: emptySet(),
            onboardingDone = prefs[Keys.ONBOARDING_DONE] ?: false,
            activeModel = prefs[Keys.ACTIVE_MODEL],
            sameModelAcrossChats = prefs[Keys.SAME_MODEL_ACROSS_CHATS] ?: true,
            disabledSkills = prefs[Keys.DISABLED_SKILLS] ?: emptySet(),
            webSearchProvider = prefs[Keys.WEB_SEARCH_PROVIDER]
                ?.takeIf { it in WEB_SEARCH_PROVIDERS }
                ?: "keyless",
            allowScreenshots = prefs[Keys.ALLOW_SCREENSHOTS] ?: true,
            planningModelsEnabled = prefs[Keys.PLANNING_MODELS_ENABLED] ?: false,
            planningProviderId = prefs[Keys.PLANNING_PROVIDER],
            planningModel = prefs[Keys.PLANNING_MODEL],
            executionProviderId = prefs[Keys.EXECUTION_PROVIDER],
            executionModel = prefs[Keys.EXECUTION_MODEL],
            subagentProviderId = prefs[Keys.SUBAGENT_PROVIDER],
            subagentModel = prefs[Keys.SUBAGENT_MODEL],
            planningModelsPromoSeen = prefs[Keys.PLANNING_PROMO_SEEN] ?: false,
            forkPromoSeen = prefs[Keys.FORK_PROMO_SEEN] ?: false,
            biometricLockEnabled = prefs[Keys.BIOMETRIC_LOCK_ENABLED] ?: false,
            biometricLockTimeoutMinutes = prefs[Keys.BIOMETRIC_LOCK_TIMEOUT] ?: 0,
            voiceEngine = prefs[Keys.VOICE_ENGINE] ?: AppSettings.VOICE_ENGINE_INBUILT,
            groqWhisperModel = prefs[Keys.GROQ_WHISPER_MODEL] ?: AppSettings.GROQ_MODEL_WHISPER_V3,
            voicePromoSeen = prefs[Keys.VOICE_PROMO_SEEN] ?: false,
            resumeLastChat = prefs[Keys.RESUME_LAST_CHAT] ?: true,
            showLiveCode = prefs[Keys.SHOW_LIVE_CODE] ?: true,
            lastActiveSessionId = prefs[Keys.LAST_ACTIVE_SESSION],
            repoMapEnabled = prefs[Keys.REPO_MAP_ENABLED] ?: true,
            localModelAgentContext = prefs[Keys.LOCAL_MODEL_AGENT_CONTEXT] ?: false,
            cavemanInstalled = prefs[Keys.CAVEMAN_INSTALLED] ?: false,
            cavemanIntensity = prefs[Keys.CAVEMAN_INTENSITY]?.let {
                runCatching { com.androidharness.app.caveman.CavemanIntensity.valueOf(it) }.getOrNull()
            } ?: com.androidharness.app.caveman.CavemanIntensity.OFF,
            cavemanWenyan = prefs[Keys.CAVEMAN_WENYAN] ?: false,
        )
    }

    suspend fun restorePortable(s: AppSettings) {
        context.settingsStore.edit { p ->
            p[Keys.THEME_MODE] = s.themeMode.name
            p[Keys.DYNAMIC_COLOR] = s.dynamicColor
            p[Keys.WORKSPACE_LAYOUT] = s.workspaceLayout.name
            p[Keys.PROVIDER_ORDER] = ProviderListPreferences.encodeOrder(s.providerOrder)
            p[Keys.DISABLED_PROVIDERS] = s.disabledProviderIds
            p[Keys.FALLBACK_POLICY] = com.androidharness.app.llm.FallbackPolicy.encode(s.fallbackPolicy)
            p[Keys.THINKING_LEVEL] = s.thinkingLevel.name
            p[Keys.MAX_CONTEXT] = s.maxContextTokens
            p[Keys.MAX_OUTPUT] = s.maxOutputTokens
            p[Keys.MAX_ITERATIONS] = s.maxIterations
            p[Keys.KEEP_ALIVE] = s.keepAlive
            p[Keys.SUBAGENT_FULL_ACCESS] = s.subagentFullAccess
            p[Keys.DISABLED_SKILLS] = s.disabledSkills
            p[Keys.WEB_SEARCH_PROVIDER] = s.webSearchProvider
            p[Keys.VOICE_ENGINE] = s.voiceEngine
            p[Keys.GROQ_WHISPER_MODEL] = s.groqWhisperModel
            p[Keys.RESUME_LAST_CHAT] = s.resumeLastChat
            p[Keys.SHOW_LIVE_CODE] = s.showLiveCode
            p[Keys.REPO_MAP_ENABLED] = s.repoMapEnabled
            p[Keys.LOCAL_MODEL_AGENT_CONTEXT] = s.localModelAgentContext
            p[Keys.CAVEMAN_INSTALLED] = s.cavemanInstalled
            p[Keys.CAVEMAN_INTENSITY] = s.cavemanIntensity.name
            p[Keys.CAVEMAN_WENYAN] = s.cavemanWenyan
            p[Keys.PLANNING_MODELS_ENABLED] = s.planningModelsEnabled
            p[Keys.SAME_MODEL_ACROSS_CHATS] = s.sameModelAcrossChats
            listOf(Keys.ACTIVE_PROVIDER to s.activeProviderId, Keys.ACTIVE_MODEL to s.activeModel,
                Keys.PLANNING_PROVIDER to s.planningProviderId, Keys.PLANNING_MODEL to s.planningModel,
                Keys.EXECUTION_PROVIDER to s.executionProviderId, Keys.EXECUTION_MODEL to s.executionModel,
                Keys.SUBAGENT_PROVIDER to s.subagentProviderId, Keys.SUBAGENT_MODEL to s.subagentModel
            ).forEach { (key, value) -> if (value == null) p.remove(key) else p[key] = value }
        }
    }

    suspend fun setWebSearchProvider(provider: String) {
        if (provider !in WEB_SEARCH_PROVIDERS) return
        context.settingsStore.edit { it[Keys.WEB_SEARCH_PROVIDER] = provider }
    }

    suspend fun setProviderEnabled(id: String, enabled: Boolean) {
        context.settingsStore.edit { prefs ->
            val disabled = prefs[Keys.DISABLED_PROVIDERS].orEmpty()
            prefs[Keys.DISABLED_PROVIDERS] = if (enabled) disabled - id else disabled + id
        }
    }

    suspend fun moveProvider(id: String, direction: Int, availableIds: List<String>, visibleIds: List<String>) {
        context.settingsStore.edit { prefs ->
            val order = ProviderListPreferences.move(
                ProviderListPreferences.decodeOrder(prefs[Keys.PROVIDER_ORDER]), availableIds, visibleIds, id, direction,
            )
            prefs[Keys.PROVIDER_ORDER] = ProviderListPreferences.encodeOrder(order)
        }
    }

    suspend fun setPermissionMode(mode: PermissionMode) {
        context.settingsStore.edit { it[Keys.PERMISSION_MODE] = mode.name }
    }

    suspend fun setSubagentFullAccess(enabled: Boolean) {
        context.settingsStore.edit { it[Keys.SUBAGENT_FULL_ACCESS] = enabled }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.settingsStore.edit { it[Keys.DYNAMIC_COLOR] = enabled }
    }

    suspend fun setWorkspaceLayout(mode: WorkspaceLayoutMode) {
        context.settingsStore.edit { it[Keys.WORKSPACE_LAYOUT] = mode.name }
    }

    suspend fun setAllowScreenshots(enabled: Boolean) {
        context.settingsStore.edit { it[Keys.ALLOW_SCREENSHOTS] = enabled }
    }

    suspend fun setPlanningModelsEnabled(enabled: Boolean) {
        context.settingsStore.edit { it[Keys.PLANNING_MODELS_ENABLED] = enabled }
    }

    suspend fun setPlanningModelsPromoSeen(seen: Boolean) {
        context.settingsStore.edit { it[Keys.PLANNING_PROMO_SEEN] = seen }
    }

    suspend fun setForkPromoSeen(seen: Boolean) {
        context.settingsStore.edit { it[Keys.FORK_PROMO_SEEN] = seen }
    }

    suspend fun setBiometricLockEnabled(enabled: Boolean) {
        context.settingsStore.edit { it[Keys.BIOMETRIC_LOCK_ENABLED] = enabled }
    }

    suspend fun setBiometricLockTimeout(minutes: Int) {
        context.settingsStore.edit { it[Keys.BIOMETRIC_LOCK_TIMEOUT] = minutes }
    }

    suspend fun setVoiceEngine(engine: String) {
        context.settingsStore.edit { it[Keys.VOICE_ENGINE] = engine }
    }

    suspend fun setGroqWhisperModel(model: String) {
        context.settingsStore.edit { it[Keys.GROQ_WHISPER_MODEL] = model }
    }

    suspend fun setVoicePromoSeen(seen: Boolean) {
        context.settingsStore.edit { it[Keys.VOICE_PROMO_SEEN] = seen }
    }

    suspend fun setPlanningModel(providerId: String?, model: String?) {
        context.settingsStore.edit { prefs ->
            if (providerId == null) prefs.remove(Keys.PLANNING_PROVIDER)
            else prefs[Keys.PLANNING_PROVIDER] = providerId
            if (model.isNullOrBlank()) prefs.remove(Keys.PLANNING_MODEL)
            else prefs[Keys.PLANNING_MODEL] = model
        }
    }

    suspend fun setExecutionModel(providerId: String?, model: String?) {
        context.settingsStore.edit { prefs ->
            if (providerId == null) prefs.remove(Keys.EXECUTION_PROVIDER)
            else prefs[Keys.EXECUTION_PROVIDER] = providerId
            if (model.isNullOrBlank()) prefs.remove(Keys.EXECUTION_MODEL)
            else prefs[Keys.EXECUTION_MODEL] = model
        }
    }

    suspend fun setSubagentModel(providerId: String?, model: String?) {
        context.settingsStore.edit { prefs ->
            if (providerId.isNullOrBlank()) {
                prefs.remove(Keys.SUBAGENT_PROVIDER)
                prefs.remove(Keys.SUBAGENT_MODEL)
            } else {
                prefs[Keys.SUBAGENT_PROVIDER] = providerId
                if (model.isNullOrBlank()) prefs.remove(Keys.SUBAGENT_MODEL)
                else prefs[Keys.SUBAGENT_MODEL] = model
            }
        }
    }

    suspend fun setActiveProvider(id: String?) {
        context.settingsStore.edit { prefs ->
            if (id == null) prefs.remove(Keys.ACTIVE_PROVIDER)
            else prefs[Keys.ACTIVE_PROVIDER] = id
        }
    }

    suspend fun setActiveSelection(providerId: String, model: String?) {
        context.settingsStore.edit { prefs ->
            prefs[Keys.ACTIVE_PROVIDER] = providerId
            if (model.isNullOrBlank()) prefs.remove(Keys.ACTIVE_MODEL) else prefs[Keys.ACTIVE_MODEL] = model
        }
    }

    suspend fun setModelSelectionScope(shared: Boolean, selection: ChatModelSelection? = null) {
        context.settingsStore.edit { prefs ->
            if (selection != null) {
                listOf(Keys.ACTIVE_PROVIDER to selection.active?.providerId, Keys.ACTIVE_MODEL to selection.active?.model,
                    Keys.PLANNING_PROVIDER to selection.planning?.providerId, Keys.PLANNING_MODEL to selection.planning?.model,
                    Keys.EXECUTION_PROVIDER to selection.execution?.providerId, Keys.EXECUTION_MODEL to selection.execution?.model
                ).forEach { (key, value) -> if (value == null) prefs.remove(key) else prefs[key] = value }
                prefs[Keys.THINKING_LEVEL] = selection.thinking.name
            }
            prefs[Keys.SAME_MODEL_ACROSS_CHATS] = shared
        }
    }

    /** A background switch must not overwrite a newer choice made in the model picker. */
    suspend fun switchActiveSelection(from: com.androidharness.app.llm.ProviderConfig, to: com.androidharness.app.llm.ProviderConfig) {
        context.settingsStore.edit { prefs ->
            if ((prefs[Keys.SAME_MODEL_ACROSS_CHATS] ?: true) && prefs[Keys.ACTIVE_PROVIDER] == from.id && (prefs[Keys.ACTIVE_MODEL] == null || prefs[Keys.ACTIVE_MODEL] == from.model)) {
                prefs[Keys.ACTIVE_PROVIDER] = to.id
                prefs[Keys.ACTIVE_MODEL] = to.model
            }
        }
    }

    suspend fun clearMissingLocalProviderSelections(availableIds: Set<String>) {
        context.settingsStore.edit { prefs ->
            listOf(Keys.ACTIVE_PROVIDER to Keys.ACTIVE_MODEL,
                Keys.PLANNING_PROVIDER to Keys.PLANNING_MODEL,
                Keys.EXECUTION_PROVIDER to Keys.EXECUTION_MODEL,
                Keys.SUBAGENT_PROVIDER to Keys.SUBAGENT_MODEL).forEach { (providerKey, modelKey) ->
                val id = prefs[providerKey]
                if (id != null && (com.androidharness.app.local.LocalModelCatalog.isLocal(id) || com.androidharness.app.chatgpt.ChatGptProtocol.isProvider(id)) && id !in availableIds) {
                    if (providerKey == Keys.ACTIVE_PROVIDER) {
                        prefs[providerKey] = com.androidharness.app.llm.HarnessProvider.ID
                    } else prefs.remove(providerKey)
                    prefs.remove(modelKey)
                }
            }
        }
    }

    suspend fun setThinkingLevel(level: ThinkingLevel) {
        context.settingsStore.edit { it[Keys.THINKING_LEVEL] = level.name }
    }

    suspend fun setMaxContextTokens(tokens: Int) {
        context.settingsStore.edit { it[Keys.MAX_CONTEXT] = tokens }
    }

    suspend fun setMaxOutputTokens(tokens: Int) {
        context.settingsStore.edit { it[Keys.MAX_OUTPUT] = tokens }
    }

    suspend fun setMaxIterations(iterations: Int) {
        context.settingsStore.edit { it[Keys.MAX_ITERATIONS] = iterations }
    }

    suspend fun setKeepAlive(enabled: Boolean) {
        context.settingsStore.edit { it[Keys.KEEP_ALIVE] = enabled }
    }

    suspend fun setPinned(sessionId: String, pinned: Boolean) {
        context.settingsStore.edit { prefs ->
            val current = prefs[Keys.PINNED] ?: emptySet()
            prefs[Keys.PINNED] = if (pinned) current + sessionId else current - sessionId
        }
    }

    suspend fun setArchived(sessionId: String, archived: Boolean) {
        context.settingsStore.edit { prefs ->
            val current = prefs[Keys.ARCHIVED] ?: emptySet()
            prefs[Keys.ARCHIVED] = if (archived) current + sessionId else current - sessionId
        }
    }

    suspend fun setOnboardingDone(done: Boolean) {
        context.settingsStore.edit { it[Keys.ONBOARDING_DONE] = done }
    }

    suspend fun setRepoMapEnabled(enabled: Boolean) {
        context.settingsStore.edit { it[Keys.REPO_MAP_ENABLED] = enabled }
    }

    suspend fun setShowLiveCode(enabled: Boolean) {
        context.settingsStore.edit { it[Keys.SHOW_LIVE_CODE] = enabled }
    }

    suspend fun setCavemanInstalled(installed: Boolean) {
        context.settingsStore.edit {
            it[Keys.CAVEMAN_INSTALLED] = installed
            it[Keys.CAVEMAN_INTENSITY] = com.androidharness.app.caveman.CavemanIntensity.OFF.name
        }
    }

    suspend fun setLocalModelAgentContext(enabled: Boolean) {
        context.settingsStore.edit { it[Keys.LOCAL_MODEL_AGENT_CONTEXT] = enabled }
    }

    suspend fun setCavemanIntensity(intensity: com.androidharness.app.caveman.CavemanIntensity) {
        context.settingsStore.edit {
            if (it[Keys.CAVEMAN_INSTALLED] == true) it[Keys.CAVEMAN_INTENSITY] = intensity.name
        }
    }

    suspend fun setCavemanWenyan(enabled: Boolean) {
        context.settingsStore.edit { it[Keys.CAVEMAN_WENYAN] = enabled }
    }

    suspend fun setSkillEnabled(name: String, enabled: Boolean) {
        context.settingsStore.edit { prefs ->
            val current = prefs[Keys.DISABLED_SKILLS] ?: emptySet()
            prefs[Keys.DISABLED_SKILLS] = if (enabled) current - name else current + name
        }
    }

    /** Model override for the ACTIVE provider; null falls back to its saved default. */
    suspend fun setActiveModel(model: String?) {
        context.settingsStore.edit { prefs ->
            if (model.isNullOrBlank()) prefs.remove(Keys.ACTIVE_MODEL)
            else prefs[Keys.ACTIVE_MODEL] = model
        }
    }

    suspend fun setResumeLastChat(enabled: Boolean) {
        context.settingsStore.edit { it[Keys.RESUME_LAST_CHAT] = enabled }
    }

    suspend fun setLastActiveSessionId(sessionId: String?) {
        context.settingsStore.edit { prefs ->
            if (sessionId.isNullOrBlank()) prefs.remove(Keys.LAST_ACTIVE_SESSION)
            else prefs[Keys.LAST_ACTIVE_SESSION] = sessionId
        }
    }
}

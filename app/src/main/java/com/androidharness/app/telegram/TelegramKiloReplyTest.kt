package com.androidharness.app.telegram

import com.androidharness.app.core.ChatMessage
import com.androidharness.app.core.Role
import com.androidharness.app.llm.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeout

internal data class TelegramModelReply(val model: String, val text: String)

/** A single text reply using an explicitly free Kilo model, with no agent tools. */
internal class TelegramKiloReplyTest(
    private val catalog: suspend () -> List<ModelEntry> = {
        when (val result = ModelCatalog.listModels(HarnessProvider.config, "")) {
            is ModelCatalog.Result.Models -> result.models.filter { it.note == HarnessProvider.KILO_NOTE }
            is ModelCatalog.Result.Failed -> throw TelegramSetupException(TelegramSetupProblem.KILO_CATALOG)
        }
    },
    private val provider: (ProviderConfig) -> LlmProvider = ProviderFactory::create,
) {
    suspend fun models(): List<String> = try {
        withTimeout(30_000) {
            catalog().map { it.id }.distinct().takeIf { it.isNotEmpty() }
                ?: throw TelegramSetupException(TelegramSetupProblem.KILO_CATALOG)
        }
    } catch (e: TimeoutCancellationException) {
        throw TelegramSetupException(TelegramSetupProblem.KILO_CATALOG)
    } catch (e: CancellationException) { throw e
    } catch (e: TelegramSetupException) { throw e
    } catch (_: Exception) { throw TelegramSetupException(TelegramSetupProblem.KILO_CATALOG) }

    suspend fun reply(model: String, question: String): TelegramModelReply {
        if (question.isBlank() || question.length > 2_000) throw TelegramSetupException(TelegramSetupProblem.KILO_QUESTION)
        if (model !in models()) throw TelegramSetupException(TelegramSetupProblem.KILO_MODEL)
        try {
            return withTimeout(90_000) {
                val config = ProviderConfig("telegram-kilo-test", "Kilo", ProviderType.OPENAI_COMPAT, HarnessProvider.KILO_BASE_URL, model)
                val text = StringBuilder()
                var complete = false
                fun consume(event: StreamEvent) {
                    when (event) {
                        is StreamEvent.TextDelta -> {
                            if (text.length + event.text.length > 3_500) throw TelegramSetupException(TelegramSetupProblem.KILO_REPLY)
                            text.append(event.text)
                        }
                        is StreamEvent.Batch -> event.events.forEach(::consume)
                        is StreamEvent.Failure -> throw TelegramSetupException(
                            if (event.code == 429) TelegramSetupProblem.KILO_RATE_LIMIT else TelegramSetupProblem.KILO_REPLY)
                        is StreamEvent.ToolCallReady, is StreamEvent.ToolCallBatch -> throw TelegramSetupException(TelegramSetupProblem.KILO_REPLY)
                        is StreamEvent.Done -> {
                            if (event.finishReason !in listOf(null, "stop", "end_turn")) throw TelegramSetupException(TelegramSetupProblem.KILO_REPLY)
                            complete = true
                        }
                        else -> Unit
                    }
                }
                provider(config).streamChat(config, "", "Reply briefly and clearly to this Telegram test question. You have no tools or access to files, the device or other chats. Do not claim to have performed actions. Keep the reply under 500 characters.",
                    listOf(ChatMessage(role = Role.USER, text = question)), emptyList(), RequestOptions(maxOutputTokens = 1_024))
                    .collect(::consume)
                if (!complete || text.isBlank()) throw TelegramSetupException(TelegramSetupProblem.KILO_REPLY)
                TelegramModelReply(model, text.toString().trim())
            }
        } catch (e: TimeoutCancellationException) {
            throw TelegramSetupException(TelegramSetupProblem.KILO_TIMEOUT)
        } catch (e: CancellationException) { throw e
        } catch (e: TelegramSetupException) { throw e
        } catch (e: ApiException) {
            throw TelegramSetupException(if (e.code == 429) TelegramSetupProblem.KILO_RATE_LIMIT else TelegramSetupProblem.KILO_REPLY)
        } catch (_: Exception) { throw TelegramSetupException(TelegramSetupProblem.KILO_REPLY) }
    }
}

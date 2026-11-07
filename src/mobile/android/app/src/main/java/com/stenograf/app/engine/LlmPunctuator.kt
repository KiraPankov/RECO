package com.stenograf.app.engine

import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig

/**
 * Локальная LLM (Qwen3-0.6B, движок LiteRT-LM) расставляет пунктуацию и заглавные буквы.
 * Ответ проходит [PunctuationGuard]: если LLM изменила хоть одно слово, ответ отбрасывается.
 *
 * Каждый фрагмент обрабатывается в отдельном разговоре, без истории, жадным декодированием
 * (topK = 1, temperature = 0) — результат воспроизводим.
 */
class LlmPunctuator(
    modelPath: String,
    cacheDir: String,
    threads: Int,
    private val systemPrompt: String,
    private val chatTemplate: String,
) : AutoCloseable {

    private val engine = Engine(
        EngineConfig(modelPath = modelPath, backend = Backend.CPU(threadCount = threads), cacheDir = cacheDir)
    )

    init {
        engine.initialize()
    }

    sealed interface Outcome {
        val text: String

        /** LLM вернула тот же набор слов — её пунктуация принята. [changed]: текст отличается от исходного. */
        data class Accepted(override val text: String, val changed: Boolean) : Outcome

        /** LLM изменила слова — оставлен исходный текст. */
        data class Rejected(override val text: String, val llmText: String) : Outcome

        /** Фрагмент не отправлялся в LLM (нет букв или слишком длинный). */
        data class Skipped(override val text: String) : Outcome
    }

    fun punctuate(text: String): Outcome {
        val src = text.trim()
        if (src.none { it.isLetter() } || src.length > MAX_CHARS) return Outcome.Skipped(src)
        val raw = engine.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(systemPrompt),
                samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
                maxOutputToken = MAX_OUTPUT_TOKENS,
                chatTemplate = chatTemplate,
            )
        ).use { conversation -> conversation.sendMessage(src).toString() }

        val decision = PunctuationGuard.choose(src, raw)
        return if (decision.accepted) {
            Outcome.Accepted(decision.text, changed = decision.text != src)
        } else {
            Log.i(TAG, "Ответ LLM отклонён (изменены слова): «$src» → «${PunctuationGuard.clean(raw)}»")
            Outcome.Rejected(src, raw)
        }
    }

    override fun close() = engine.close()

    companion object {
        private const val TAG = "LlmPunctuator"

        /**
         * Модель собрана с KV-кэшем на 1280 токенов: промпт (~150) + фрагмент + ответ должны уместиться.
         * Фрагменты распознавателя не длиннее 28 с, это обычно 200–400 символов.
         */
        const val MAX_CHARS = 600
        const val MAX_OUTPUT_TOKENS = 448
    }
}

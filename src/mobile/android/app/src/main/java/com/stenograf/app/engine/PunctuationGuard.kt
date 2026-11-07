package com.stenograf.app.engine

import java.util.Locale

/**
 * Проверка ответа LLM: ей разрешено менять только знаки препинания и регистр букв.
 *
 * Ответ принимается, только если последовательность слов в нём в точности совпадает с исходной
 * (без учёта регистра и знаков по краям слов). Любая замена, вставка, удаление или перестановка
 * слова, замена «е»/«ё», цифр, дефисов внутри слов — и ответ отбрасывается, остаётся текст
 * распознавателя. Так LLM не может увеличить долю ошибок в словах (WER).
 *
 * Та же логика повторена в tools/eval/llm_punctuation.py; обе реализации проверяются
 * на общих примерах из app/src/test/resources/punctuation_guard_cases.json.
 */
object PunctuationGuard {

    /** Знаки, которые LLM может ставить и убирать по краям слов. */
    private const val EDGE_PUNCT = ".,!?;:…«»\"“”„‘’'()[]{}—–‒-*"

    private val THINK_BLOCK = Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL)
    private val WHITESPACE = Regex("\\s+")
    private val QUOTE_PAIRS = listOf("«" to "»", "\"" to "\"", "“" to "”", "„" to "“")

    /** Убирает служебные части ответа: блок размышлений, кавычки вокруг всего ответа, лишние пробелы. */
    fun clean(llmOutput: String): String {
        var t = THINK_BLOCK.replace(llmOutput, " ").replace("<think>", " ").replace("</think>", " ").trim()
        for ((open, close) in QUOTE_PAIRS) {
            if (t.length >= 2 && t.startsWith(open) && t.endsWith(close)) {
                t = t.substring(open.length, t.length - close.length).trim()
                break
            }
        }
        return WHITESPACE.replace(t, " ").trim()
    }

    /** Слова текста: в нижнем регистре, без знаков по краям; отдельно стоящие знаки отбрасываются. */
    fun words(text: String): List<String> =
        text.split(WHITESPACE)
            .map { it.trim { c -> c in EDGE_PUNCT }.lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }

    fun sameWords(original: String, candidate: String): Boolean {
        val a = words(original)
        return a.isNotEmpty() && a == words(candidate)
    }

    /** Итоговый текст: ответ LLM, если слова не изменились, иначе исходный текст. */
    fun choose(original: String, llmOutput: String): Decision {
        val cleaned = clean(llmOutput)
        return if (cleaned.isNotEmpty() && sameWords(original, cleaned)) {
            Decision(cleaned, accepted = true)
        } else {
            Decision(original, accepted = false)
        }
    }

    data class Decision(val text: String, val accepted: Boolean)
}

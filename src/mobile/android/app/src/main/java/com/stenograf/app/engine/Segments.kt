package com.stenograf.app.engine

import kotlin.math.max
import kotlin.math.min

/** Участок записи, отнесённый к одному говорящему. Время — в секундах. */
data class Seg(val start: Double, val end: Double, val speaker: Int) {
    val duration get() = end - start
}

/** Распознанная реплика до финальной нумерации спикеров. [byLlm] — LLM изменила пунктуацию или регистр. */
data class RawUtterance(
    val start: Double,
    val end: Double,
    val speaker: Int,
    val text: String,
    val byLlm: Boolean = false,
)

/** Постобработка сегментов диаризации и распознанных реплик. Без зависимостей от Android. */
object Segments {

    /** Максимальная длина куска, который подаётся в распознаватель за один раз. */
    const val MAX_PIECE_SEC = 28.0

    /** Длинный кусок режется не раньше этой отметки — в самой тихой точке. */
    const val MIN_SPLIT_SEC = 18.0

    /** Соседние сегменты одного спикера с паузой меньше этой склеиваются перед распознаванием. */
    const val MERGE_GAP_SEC = 0.8

    /** Реплики одного спикера с паузой меньше этой объединяются в одну в итоговой расшифровке. */
    const val JOIN_GAP_SEC = 1.5

    /** Короче этого сегменты отбрасываются как шум. */
    const val MIN_SEG_SEC = 0.3

    /** Немного захватываем тишину по краям, чтобы не обрезать первый и последний звук. */
    const val PAD_SEC = 0.15

    /** Убирает перекрытия (одновременная речь), сортирует по времени. */
    fun removeOverlaps(segs: List<Seg>): List<Seg> {
        val out = ArrayList<Seg>()
        for (s in segs.sortedBy { it.start }) {
            var start = s.start
            val prev = out.lastOrNull()
            if (prev != null && start < prev.end) start = prev.end
            if (s.end - start >= MIN_SEG_SEC) out += Seg(start, s.end, s.speaker)
        }
        return out
    }

    /** Склеивает подряд идущие сегменты одного спикера, если между ними короткая пауза. */
    fun mergeSameSpeaker(segs: List<Seg>): List<Seg> {
        val out = ArrayList<Seg>()
        for (s in segs) {
            val prev = out.lastOrNull()
            if (prev != null && prev.speaker == s.speaker &&
                s.start - prev.end < MERGE_GAP_SEC && s.end - prev.start <= MAX_PIECE_SEC
            ) {
                out[out.size - 1] = prev.copy(end = s.end)
            } else {
                out += s
            }
        }
        return out
    }

    /**
     * Делит слишком длинный сегмент на куски не длиннее [MAX_PIECE_SEC],
     * выбирая для разреза самое тихое место (чтобы не резать слово).
     */
    fun splitLong(seg: Seg, audio: FloatArray, sampleRate: Int): List<Seg> {
        val out = ArrayList<Seg>()
        var start = seg.start
        val win = (0.2 * sampleRate).toInt()
        while (seg.end - start > MAX_PIECE_SEC) {
            val a = ((start + MIN_SPLIT_SEC) * sampleRate).toInt()
            val b = min(((start + MAX_PIECE_SEC) * sampleRate).toInt(), audio.size)
            var bestPos = b
            var bestEnergy = Double.MAX_VALUE
            var p = a
            while (p + win <= b) {
                var e = 0.0
                for (i in p until p + win) e += audio[i] * audio[i]
                if (e < bestEnergy) {
                    bestEnergy = e
                    bestPos = p + win / 2
                }
                p += win / 2
            }
            val cut = bestPos.toDouble() / sampleRate
            if (cut <= start) break
            out += Seg(start, cut, seg.speaker)
            start = cut
        }
        out += Seg(start, seg.end, seg.speaker)
        return out
    }

    /** Вырезает кусок аудио с небольшим запасом по краям. */
    fun slice(audio: FloatArray, seg: Seg, sampleRate: Int, pad: Double = PAD_SEC): FloatArray {
        val from = max(0, ((seg.start - pad) * sampleRate).toInt())
        val to = min(audio.size, ((seg.end + pad) * sampleRate).toInt())
        return if (to > from) audio.copyOfRange(from, to) else FloatArray(0)
    }

    /**
     * Финальная сборка: спикеры нумеруются по порядку появления (0, 1, 2…),
     * подряд идущие реплики одного спикера с короткой паузой объединяются.
     */
    fun finalize(raw: List<RawUtterance>): List<RawUtterance> {
        val order = LinkedHashMap<Int, Int>()
        val out = ArrayList<RawUtterance>()
        for (u in raw.sortedBy { it.start }) {
            val text = u.text.trim()
            if (text.isEmpty()) continue
            val spk = order.getOrPut(u.speaker) { order.size }
            val prev = out.lastOrNull()
            if (prev != null && prev.speaker == spk && u.start - prev.end < JOIN_GAP_SEC) {
                out[out.size - 1] = prev.copy(end = u.end, text = prev.text + " " + text, byLlm = prev.byLlm || u.byLlm)
            } else {
                out += RawUtterance(u.start, u.end, spk, text, u.byLlm)
            }
        }
        return out
    }

    /** У CTC-моделей без пунктуации текст в нижнем регистре — делаем первую букву заглавной. */
    fun capitalizeFirst(text: String): String =
        if (text.isNotEmpty() && text[0].isLowerCase()) text[0].uppercase() + text.substring(1) else text
}

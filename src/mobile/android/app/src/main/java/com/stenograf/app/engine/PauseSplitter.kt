package com.stenograf.app.engine

/**
 * Разрезание сегментов диаризации по паузам.
 *
 * На шумной записи pyannote склеивает соседние реплики разных людей в один сегмент: например,
 * конец фразы первого спикера, ответ второго и реплику первого. Тогда конец реплики
 * достаётся следующему говорящему. Детектор речи (Silero VAD) находит паузы между фразами
 * точнее. Каждый сегмент режется посередине каждой паузы, которая целиком лежит внутри него,
 * а затем [SpeakerRefiner] заново определяет говорящего для каждого куска по отпечатку голоса.
 *
 * Границы диаризации, не совпадающие с паузой (смена говорящего без паузы, перебивание),
 * сохраняются. Короткий кусок (< [SHORT_SEC]), примыкающий к соседу без паузы, получает метку
 * более длинного соседа: по такому обрывку голос надёжно не определить.
 */
object PauseSplitter {

    /** Куски короче этого после разреза отбрасываются (обрывок паузы). */
    const val MIN_PIECE_SEC = 0.2

    /** Куски короче этого, примыкающие к соседу без паузы, получают метку соседа. */
    const val SHORT_SEC = 1.0

    private const val GLUE_SEC = 0.05

    private class Piece(val start: Double, var end: Double, var speaker: Int, val cutStart: Boolean, var cutEnd: Boolean) {
        val duration get() = end - start
    }

    /**
     * @param segs сегменты диаризации, по времени начала.
     * @param speech участки речи от VAD (начало, конец), по времени.
     */
    fun split(segs: List<Seg>, speech: List<Pair<Double, Double>>): List<Seg> {
        val pauses = (0 until speech.size - 1).map { speech[it].second to speech[it + 1].first }
        val out = ArrayList<Piece>()
        for (s in segs) {
            var cur = s.start
            var startCut = false
            for ((gs, ge) in pauses) {
                if (gs <= s.start || ge >= s.end || ge <= cur) continue
                val mid = (gs + ge) / 2
                if (mid - cur >= MIN_PIECE_SEC) {
                    out += Piece(cur, mid, s.speaker, startCut, cutEnd = true)
                    cur = mid
                    startCut = true
                }
            }
            if (s.end - cur >= MIN_PIECE_SEC) out += Piece(cur, s.end, s.speaker, startCut, cutEnd = false)
        }

        // Короткие куски, примыкающие к соседу без паузы, — к метке более длинного соседа
        var changed = true
        while (changed) {
            changed = false
            for (i in out.indices) {
                val p = out[i]
                if (p.duration >= SHORT_SEC) continue
                val neighbours = buildList {
                    if (i > 0 && !p.cutStart && kotlin.math.abs(out[i - 1].end - p.start) < GLUE_SEC) add(out[i - 1])
                    if (i + 1 < out.size && !p.cutEnd && kotlin.math.abs(out[i + 1].start - p.end) < GLUE_SEC) add(out[i + 1])
                }
                val best = neighbours.maxByOrNull { it.duration } ?: continue
                if (best.speaker != p.speaker) {
                    p.speaker = best.speaker
                    changed = true
                }
            }
        }

        // Склейка примыкающих кусков одного спикера (кроме разрезов по паузам)
        val merged = ArrayList<Piece>()
        for (p in out) {
            val last = merged.lastOrNull()
            if (last != null && last.speaker == p.speaker && p.start - last.end < GLUE_SEC && !p.cutStart) {
                last.end = p.end
                last.cutEnd = p.cutEnd
            } else {
                merged += Piece(p.start, p.end, p.speaker, p.cutStart, p.cutEnd)
            }
        }
        return merged.map { Seg(it.start, it.end, it.speaker) }
    }
}

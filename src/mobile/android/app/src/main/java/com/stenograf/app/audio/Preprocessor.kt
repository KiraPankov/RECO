package com.stenograf.app.audio

import kotlin.math.abs
import kotlin.math.min

/**
 * Предварительная обработка сигнала перед диаризацией и распознаванием:
 * 1. удаление постоянной составляющей и инфранизких частот (вычитание скользящего среднего ~0,1 с);
 * 2. нормализация громкости: 99,9-й перцентиль амплитуды приводится к 0,9 (усиление не больше ×30).
 *
 * На тихой записи со смещением это снизило WER с 4,3% до 2,6% и ошибку границ реплик с ~0,9 с до 25 мс
 * (см. docs/TEST_RESULTS.md). Шумоподавление сознательно не применяется — в тестах оно ухудшало результат.
 */
object Preprocessor {

    private const val WINDOW = 1601
    private const val TARGET_PEAK = 0.9f
    private const val MAX_GAIN = 30f
    private const val PERCENTILE = 0.999

    fun process(x: FloatArray): FloatArray {
        if (x.isEmpty()) return x
        val y = removeDc(x)
        val p = percentileAbs(y, PERCENTILE)
        val gain = min(TARGET_PEAK / maxOf(p, 1e-6f), MAX_GAIN)
        for (i in y.indices) y[i] = (y[i] * gain).coerceIn(-1f, 1f)
        return y
    }

    /** y[i] = x[i] − среднее по окну WINDOW с центром в i (на краях окно обрезается). */
    fun removeDc(x: FloatArray): FloatArray {
        val n = x.size
        val half = WINDOW / 2
        val prefix = DoubleArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + x[i]
        val y = FloatArray(n)
        for (i in 0 until n) {
            val a = maxOf(0, i - half)
            val b = min(n, i + half + 1)
            // Как numpy.convolve(..., 'same') с окном 1/WINDOW: делим на полный размер окна
            y[i] = (x[i] - (prefix[b] - prefix[a]) / WINDOW).toFloat()
        }
        return y
    }

    /** Перцентиль |x| через гистограмму — без сортировки многомиллионного массива. */
    fun percentileAbs(x: FloatArray, q: Double): Float {
        var max = 0f
        for (v in x) max = maxOf(max, abs(v))
        if (max == 0f) return 0f
        val bins = 8192
        val hist = IntArray(bins)
        for (v in x) hist[min(bins - 1, (abs(v) / max * (bins - 1)).toInt())]++
        val target = (x.size * q).toLong()
        var acc = 0L
        for (b in 0 until bins) {
            acc += hist[b]
            if (acc >= target) return (b + 1).toFloat() / (bins - 1) * max
        }
        return max
    }
}

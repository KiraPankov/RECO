package com.stenograf.app.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Потоковый ресемплер в 16 кГц.
 * При понижении частоты сигнал предварительно фильтруется (windowed-sinc, окно Блэкмана),
 * чтобы не было наложения частот; затем значения берутся линейной интерполяцией.
 * Фильтр считается только в тех точках, которые реально нужны для выходных отсчётов.
 */
class Resampler(private val srcRate: Int, private val dstRate: Int = 16000) {

    private val step = srcRate.toDouble() / dstRate
    private val taps: FloatArray = if (srcRate > dstRate) lowPass(TAPS, 0.45 * dstRate / srcRate) else floatArrayOf(1f)
    private val ringSize = taps.size + 4
    private val ring = FloatArray(ringSize)

    /** Индекс последнего принятого входного отсчёта. */
    private var rawIndex = -1L

    /** Позиция следующего выходного отсчёта во входной шкале. */
    private var pos = 0.0

    val passthrough = srcRate == dstRate

    fun process(input: FloatArray, length: Int, out: FloatArrayBuilder) {
        if (passthrough) {
            out.add(input, length)
            return
        }
        for (k in 0 until length) {
            rawIndex++
            ring[(rawIndex % ringSize).toInt()] = input[k]
            while (true) {
                val i0 = floor(pos).toLong()
                if (i0 + 1 > rawIndex) break
                val frac = (pos - i0).toFloat()
                val a = filtered(i0)
                val b = filtered(i0 + 1)
                out.add(a + (b - a) * frac)
                pos += step
            }
        }
    }

    private fun filtered(index: Long): Float {
        if (taps.size == 1) return ring[(index % ringSize).toInt()]
        var acc = 0f
        for (j in taps.indices) {
            val i = index - j
            if (i < 0 || index - i >= ringSize) break
            acc += taps[j] * ring[(i % ringSize).toInt()]
        }
        return acc
    }

    companion object {
        private const val TAPS = 64

        /** Фильтр нижних частот; [cutoff] — доля частоты дискретизации (0..0.5). */
        fun lowPass(n: Int, cutoff: Double): FloatArray {
            val h = DoubleArray(n)
            val m = (n - 1) / 2.0
            for (i in 0 until n) {
                val x = i - m
                val sinc = if (x == 0.0) 2 * cutoff else sin(2 * PI * cutoff * x) / (PI * x)
                val w = 0.42 - 0.5 * cos(2 * PI * i / (n - 1)) + 0.08 * cos(4 * PI * i / (n - 1))
                h[i] = sinc * w
            }
            val sum = h.sum()
            return FloatArray(n) { (h[it] / sum).toFloat() }
        }
    }
}

/** Растущий массив float без упаковки в объекты. */
class FloatArrayBuilder(initialCapacity: Int = 16000 * 60) {
    private var data = FloatArray(initialCapacity.coerceAtLeast(1024))
    var size = 0
        private set

    fun add(v: Float) {
        if (size == data.size) grow(size + 1)
        data[size++] = v
    }

    fun add(src: FloatArray, length: Int) {
        if (size + length > data.size) grow(size + length)
        System.arraycopy(src, 0, data, size, length)
        size += length
    }

    private fun grow(min: Int) {
        var cap = data.size
        while (cap < min) cap = if (cap < 64_000_000) cap * 2 else cap + cap / 2
        data = data.copyOf(cap)
    }

    fun toArray(): FloatArray = if (size == data.size) data else data.copyOf(size)
}

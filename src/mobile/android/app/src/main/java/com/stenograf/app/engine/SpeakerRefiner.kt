package com.stenograf.app.engine

import kotlin.math.sqrt

/**
 * Второй проход диаризации.
 *
 * Сегментатор pyannote работает окнами по 10 секунд и иногда путает метки соседних реплик.
 * Здесь для каждого сегмента считается «отпечаток голоса», по сегментам ≥ [MIN_SEG_SEC] строятся
 * центроиды спикеров, очень похожие центроиды объединяются, и каждый сегмент
 * переназначается ближайшему спикеру. Повторяется до сходимости.
 *
 * Короткий сегмент ([MIN_EMBED_SEC]…[MIN_SEG_SEC]) сохраняет свою метку, если у неё есть центроид:
 * по короткому обрывку голос определяется ненадёжно. Если же его спикер встречается только
 * в коротких сегментах (например, «Согласен» записан в отдельного спикера), сегмент
 * отдаётся ближайшему настоящему спикеру.
 */
object SpeakerRefiner {

    const val MIN_SEG_SEC = 1.0

    /** Минимальная длина сегмента, для которого считается отпечаток голоса. */
    const val MIN_EMBED_SEC = 0.5
    const val MERGE_SIMILARITY = 0.75f
    private const val MAX_ITERATIONS = 5

    /**
     * @param embeddings нормированные эмбеддинги для каждого сегмента или null для слишком коротких.
     * @param allowMerge объединять ли похожих спикеров (выключается, если число спикеров задано вручную).
     */
    fun refine(segs: List<Seg>, embeddings: List<FloatArray?>, allowMerge: Boolean): List<Seg> {
        require(segs.size == embeddings.size)
        if (embeddings.all { it == null }) return segs
        var labels = segs.map { it.speaker }.toIntArray()

        repeat(MAX_ITERATIONS) {
            val centroids = centroids(segs, embeddings, labels)
            if (centroids.isEmpty()) return segs

            if (allowMerge) {
                val keys = centroids.keys.sorted()
                val remap = keys.associateWith { it }.toMutableMap()
                for (i in keys.indices) for (j in i + 1 until keys.size) {
                    val k1 = keys[i]
                    val k2 = keys[j]
                    if (remap[k2] == k2 && remap[k1] == k1 &&
                        dot(centroids.getValue(k1), centroids.getValue(k2)) > MERGE_SIMILARITY
                    ) remap[k2] = k1
                }
                labels = labels.map { remap[it] ?: it }.toIntArray()
            }
            val merged = centroids(segs, embeddings, labels)

            val next = IntArray(labels.size) { i ->
                val e = embeddings[i]
                when {
                    e == null -> labels[i]
                    segs[i].duration < MIN_SEG_SEC && labels[i] in merged -> labels[i]
                    else -> merged.maxBy { (_, c) -> dot(c, e) }.key
                }
            }
            val changed = !next.contentEquals(labels)
            labels = next
            if (!changed) return segs.mapIndexed { i, s -> s.copy(speaker = labels[i]) }
        }
        return segs.mapIndexed { i, s -> s.copy(speaker = labels[i]) }
    }

    private fun centroids(segs: List<Seg>, emb: List<FloatArray?>, labels: IntArray): Map<Int, FloatArray> {
        val sums = HashMap<Int, FloatArray>()
        for (i in segs.indices) {
            val e = emb[i] ?: continue
            if (segs[i].duration < MIN_SEG_SEC) continue
            val acc = sums.getOrPut(labels[i]) { FloatArray(e.size) }
            val w = segs[i].duration.toFloat()
            for (d in e.indices) acc[d] += e[d] * w
        }
        sums.values.forEach { normalizeInPlace(it) }
        return sums
    }

    fun normalizeInPlace(v: FloatArray): FloatArray {
        var n = 0.0
        for (x in v) n += x * x
        val norm = sqrt(n).toFloat() + 1e-9f
        for (i in v.indices) v[i] /= norm
        return v
    }

    fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }
}

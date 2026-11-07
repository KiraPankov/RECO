package com.stenograf.app

import com.stenograf.app.audio.FloatArrayBuilder
import com.stenograf.app.audio.Preprocessor
import com.stenograf.app.audio.Resampler
import com.stenograf.app.engine.DiarizationProgress
import com.stenograf.app.engine.PauseSplitter
import com.stenograf.app.engine.PunctuationGuard
import com.stenograf.app.engine.RawUtterance
import com.stenograf.app.engine.Seg
import com.stenograf.app.engine.Segments
import com.stenograf.app.engine.SpeakerRefiner
import com.stenograf.app.util.Format
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class PipelineLogicTest {

    private fun rms(x: FloatArray, from: Int = 200): Double {
        var s = 0.0
        for (i in from until x.size) s += x[i] * x[i]
        return sqrt(s / (x.size - from))
    }

    private fun resample(src: Int, freq: Double, chunk: Int = 1000): FloatArray {
        val input = FloatArray(src) { (0.5 * sin(2 * PI * freq * it / src)).toFloat() }
        val r = Resampler(src)
        val out = FloatArrayBuilder()
        var i = 0
        while (i < input.size) {
            val len = minOf(chunk, input.size - i)
            r.process(input.copyOfRange(i, i + len), len, out)
            i += len
        }
        return out.toArray()
    }

    @Test
    fun resamplerKeepsSpeechBandForCommonRates() {
        for (src in listOf(8000, 16000, 22050, 44100, 48000)) {
            val y = resample(src, 1000.0)
            assertTrue("length for $src: ${y.size}", abs(y.size - 16000) <= 2)
            assertTrue("amplitude for $src: ${rms(y)}", rms(y) in 0.33..0.37)
        }
    }

    @Test
    fun resamplerSuppressesAliasing() {
        assertTrue(rms(resample(48000, 13000.0)) < 0.01)
    }

    @Test
    fun resamplerIsIndependentOfChunkSize() {
        assertArrayEquals(resample(44100, 1234.0, chunk = 1), resample(44100, 1234.0, chunk = 4096), 0f)
    }

    @Test
    fun overlapsAreRemoved() {
        val out = Segments.removeOverlaps(listOf(Seg(0.0, 5.0, 0), Seg(4.0, 9.0, 1), Seg(4.5, 4.9, 2)))
        assertEquals(listOf(Seg(0.0, 5.0, 0), Seg(5.0, 9.0, 1)), out)
    }

    @Test
    fun sameSpeakerNeighboursAreMerged() {
        val out = Segments.mergeSameSpeaker(listOf(Seg(0.0, 2.0, 0), Seg(2.5, 4.0, 0), Seg(6.0, 7.0, 0), Seg(7.1, 8.0, 1)))
        assertEquals(listOf(Seg(0.0, 4.0, 0), Seg(6.0, 7.0, 0), Seg(7.1, 8.0, 1)), out)
    }

    @Test
    fun longSegmentIsSplitInSilence() {
        // 70 секунд тона с тишиной в начале каждой 7-й секунды
        val audio = FloatArray(16000 * 70) { i ->
            val t = i / 16000.0
            if (t % 7.0 < 0.3) 0f else (0.3 * sin(2 * PI * 200 * t)).toFloat()
        }
        val parts = Segments.splitLong(Seg(0.0, 70.0, 0), audio, 16000)
        assertTrue(parts.size >= 3)
        assertTrue(parts.all { it.duration <= Segments.MAX_PIECE_SEC })
        assertTrue(parts.dropLast(1).all { it.end % 7.0 < 0.3 })
        assertEquals(0.0, parts.first().start, 0.0)
        assertEquals(70.0, parts.last().end, 0.0)
        assertTrue(parts.zipWithNext().all { (a, b) -> a.end == b.start })
    }

    @Test
    fun speakersAreNumberedByAppearanceAndJoined() {
        val out = Segments.finalize(
            listOf(
                RawUtterance(0.0, 2.0, 5, "Привет."),
                RawUtterance(2.5, 3.0, 5, "Как дела?"),
                RawUtterance(4.0, 6.0, 2, "Хорошо."),
                RawUtterance(6.2, 7.0, 7, "  "),
            )
        )
        assertEquals(2, out.size)
        assertEquals(RawUtterance(0.0, 3.0, 0, "Привет. Как дела?"), out[0])
        assertEquals(1, out[1].speaker)
    }

    @Test
    fun refinerFixesMislabelledSegmentAndMergesDuplicateSpeaker() {
        fun v(vararg x: Float) = SpeakerRefiner.normalizeInPlace(x)
        val a = v(1f, 0f, 0f)
        val b = v(0f, 1f, 0f)
        val segs = listOf(
            Seg(0.0, 5.0, 0), // A
            Seg(5.0, 9.0, 1), // B
            Seg(9.0, 12.0, 1), // A, но помечен как B
            Seg(12.0, 16.0, 2), // B, но отдельным кластером
            Seg(16.0, 16.5, 1), // короткий — без эмбеддинга
        )
        val emb = listOf(a, b, v(0.95f, 0.1f, 0f), v(0.05f, 1f, 0.02f), null)
        val labels = SpeakerRefiner.refine(segs, emb, allowMerge = true).map { it.speaker }
        assertEquals(labels[0], labels[2])
        assertEquals(labels[1], labels[3])
        assertTrue(labels[0] != labels[1])
        assertEquals(segs[4].speaker, labels[4])
    }

    @Test
    fun refinerGivesShortOrphanSegmentToNearestSpeaker() {
        fun v(vararg x: Float) = SpeakerRefiner.normalizeInPlace(x)
        val segs = listOf(
            Seg(0.0, 5.0, 0),
            Seg(5.0, 9.0, 1),
            Seg(9.5, 10.3, 1), // короткий, у спикера есть центроид — метка сохраняется
            Seg(11.0, 11.8, 4), // короткий, спикер 4 встречается только здесь
        )
        val emb = listOf(v(1f, 0f, 0f), v(0f, 1f, 0f), v(1f, 0.1f, 0f), v(0.9f, 0.1f, 0.1f))
        val labels = SpeakerRefiner.refine(segs, emb, allowMerge = true).map { it.speaker }
        assertEquals(listOf(0, 1, 1, 0), labels)
    }

    @Test
    fun pauseSplitterCutsMergedTurnsAtPauses() {
        // Как на шумной записи: pyannote склеил конец реплики A, реплику B, реплику A и реплику B
        val segs = listOf(Seg(0.0, 4.0, 0), Seg(4.0, 16.3, 1))
        val speech = listOf(0.2 to 2.9, 3.2 to 6.1, 6.8 to 11.5, 12.1 to 14.5, 14.9 to 16.7)
        val out = PauseSplitter.split(segs, speech)
        // Разрезы — посередине пауз внутри сегментов. Кусок 3.05–4.0 (0,95 с) примыкает к 4.0–6.45
        // без паузы и получает его метку; дальше говорящего каждого куска уточняет SpeakerRefiner.
        val expected = listOf(Seg(0.0, 3.05, 0), Seg(3.05, 6.45, 1), Seg(6.45, 11.8, 1), Seg(11.8, 14.7, 1), Seg(14.7, 16.3, 1))
        assertEquals(expected.size, out.size)
        expected.zip(out).forEach { (e, o) ->
            assertEquals(e.start, o.start, 1e-9)
            assertEquals(e.end, o.end, 1e-9)
            assertEquals(e.speaker, o.speaker)
        }
    }

    @Test
    fun pauseSplitterKeepsSegmentsWithoutInnerPauses() {
        val segs = listOf(Seg(0.0, 3.0, 0), Seg(3.0, 6.0, 1))
        val speech = listOf(0.0 to 6.0)
        assertEquals(segs, PauseSplitter.split(segs, speech))
        // Пауза на границе сегментов (не внутри) ничего не режет
        assertEquals(segs, PauseSplitter.split(segs, listOf(0.0 to 2.8, 3.2 to 6.0)))
    }

    @Test
    fun formatting() {
        assertEquals("1:02:05", Format.clock(3725.0))
        assertEquals("0:42", Format.clock(42.9))
        assertEquals("00:01:15,300", Format.srt(75.3))
        assertEquals("1 участник", Format.participants(1))
        assertEquals("3 участника", Format.participants(3))
        assertEquals("11 участников", Format.participants(11))
        assertEquals("22 участника", Format.participants(22))
    }

    @Test
    fun preprocessorRemovesDcAndNormalizesLoudness() {
        // Тихий тон 0,03 со смещением +0,02 (как в тестовой «тихой» записи)
        val x = FloatArray(16000 * 3) { (0.03 * sin(2 * PI * 300 * it / 16000) + 0.02).toFloat() }
        val y = Preprocessor.process(x)
        val mean = y.drop(2000).dropLast(2000).average()
        assertTrue("смещение убрано: $mean", abs(mean) < 0.01)
        val peak = y.drop(2000).dropLast(2000).maxOf { abs(it) }
        assertTrue("громкость поднята примерно в 20 раз (пик $peak)", peak in 0.6f..0.95f)
    }

    @Test
    fun preprocessorDoesNotAmplifySilenceToNoiseBeyondLimit() {
        val x = FloatArray(16000) { 1e-5f * sin(it.toFloat()) }
        val y = Preprocessor.process(x)
        assertTrue(y.maxOf { abs(it) } <= 1e-5f * 30 + 1e-6f)
    }

    @Test
    fun llmFlagSurvivesJoin() {
        val out = Segments.finalize(
            listOf(
                RawUtterance(0.0, 1.0, 0, "Да."),
                RawUtterance(1.2, 2.0, 0, "Конечно.", byLlm = true),
            )
        )
        assertEquals(1, out.size)
        assertTrue(out[0].byLlm)
    }

    /**
     * sherpa-onnx вызывает колбэк через JNI по сигнатуре invoke(IIJ)Ljava/lang/Integer;.
     * Если метода нет, приложение аварийно завершается при диаризации.
     */
    @Test
    fun diarizationCallbackHasJniSignature() {
        var last = -1f
        val cb = DiarizationProgress { last = it }
        val m = cb.javaClass.getMethod("invoke", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Long::class.javaPrimitiveType)
        assertEquals(Integer::class.java, m.returnType)
        assertEquals(0, m.invoke(cb, 5, 10, 0L))
        assertEquals(0.4f, last, 1e-6f)
    }

    /** Общие с tools/eval/llm_punctuation.py примеры: что LLM можно и чего нельзя. */
    @Test
    fun punctuationGuardSharedCases() {
        val json = javaClass.getResource("/punctuation_guard_cases.json")!!.readText()
        val cases = Regex("\\{[^{}]*\\}").findAll(json).map { m -> parseFlatJson(m.value) }.toList()
        assertTrue("загружено ${cases.size} примеров", cases.size >= 15)
        for (c in cases) {
            val d = PunctuationGuard.choose(c.getValue("original") as String, c.getValue("llm") as String)
            assertEquals("${c["name"]}: принят ли ответ", c["accept"], d.accepted)
            assertEquals("${c["name"]}: итоговый текст", c["result"], d.text)
        }
    }

    /** Разбор одного плоского JSON-объекта со строками и true/false (без зависимостей от org.json). */
    private fun parseFlatJson(obj: String): Map<String, Any> {
        val out = HashMap<String, Any>()
        val re = Regex("\"(\\w+)\"\\s*:\\s*(\"((?:\\\\.|[^\"\\\\])*)\"|true|false)")
        for (m in re.findAll(obj)) {
            val key = m.groupValues[1]
            out[key] = when (m.groupValues[2]) {
                "true" -> true
                "false" -> false
                else -> m.groupValues[3].replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\")
            }
        }
        return out
    }
}

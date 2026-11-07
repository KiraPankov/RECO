package com.stenograf.app.engine

import android.util.Log
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.stenograf.app.audio.AudioDecoder.SAMPLE_RATE
import com.stenograf.app.audio.Preprocessor
import com.stenograf.app.models.ModelStore
import com.stenograf.app.models.Models
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class Stage { DECODING, DIARIZATION, RECOGNITION, PUNCTUATION }

/** Итог работы LLM: сколько фрагментов она исправила, сколько ответов отброшено проверкой слов. */
data class LlmStats(
    val changed: Int = 0,
    val unchanged: Int = 0,
    val rejected: Int = 0,
    val skipped: Int = 0,
    /** LLM не запустилась (например, неподдерживаемый процессор) — пунктуация осталась от распознавателя. */
    val unavailable: Boolean = false,
)

data class EngineResult(val utterances: List<RawUtterance>, val numSpeakers: Int, val llm: LlmStats)

/** Результат повторной обработки фрагмента. */
data class FragmentResult(val text: String, val llmChanged: Boolean)

/**
 * Конвейер обработки (всё выполняется на устройстве):
 * 1. предобработка сигнала ([Preprocessor]);
 * 2. диаризация: pyannote segmentation 3.0 находит смены говорящих, CAM++ строит «отпечаток» голоса,
 *    затем кластеризация;
 * 3. разрезание сегментов по паузам, найденным детектором речи Silero VAD ([PauseSplitter]),
 *    и уточнение спикера каждого куска по отпечатку голоса ([SpeakerRefiner]);
 * 4. чистка и нарезка сегментов на куски ≤ 28 с по паузам ([Segments]);
 * 5. распознавание каждого куска — NVIDIA Parakeet TDT 0.6B v3;
 * 6. локальная LLM (Qwen3-0.6B) правит пунктуацию и заглавные буквы; если она изменила
 *    хоть одно слово, её ответ отбрасывается ([PunctuationGuard]);
 * 7. сборка реплик с временными метками.
 * Модели загружаются по очереди и сразу освобождаются, чтобы не держать в памяти всё одновременно.
 */
class TranscriptionEngine(private val models: ModelStore) {

    private val threads = Runtime.getRuntime().availableProcessors().let { (it - 1).coerceIn(1, 4) }

    /** Одновременно работает только одна задача: модели занимают много памяти. */
    private val mutex = Mutex()

    suspend fun run(audioRaw: FloatArray, onProgress: (Stage, Float) -> Unit): EngineResult = mutex.withLock {
        withContext(Dispatchers.Default) {
            val ctx = currentCoroutineContext()
            val audio = Preprocessor.process(audioRaw)

            // Кто и когда говорит
            onProgress(Stage.DIARIZATION, 0f)
            val segments = diarize(audio) { p -> onProgress(Stage.DIARIZATION, p) }
            ctx.ensureActive()
            Log.i(TAG, "Диаризация: ${segments.size} сегментов, ${segments.map { it.speaker }.distinct().size} спикеров")

            var pieces = Segments.mergeSameSpeaker(Segments.removeOverlaps(segments))
            if (pieces.isEmpty()) return@withContext EngineResult(emptyList(), 0, LlmStats())
            pieces = pieces.flatMap { Segments.splitLong(it, audio, SAMPLE_RATE) }

            // Что сказано
            onProgress(Stage.RECOGNITION, 0f)
            val raw = ArrayList<RawUtterance>(pieces.size)
            val asr = createAsr()
            try {
                val total = pieces.sumOf { it.duration }.coerceAtLeast(1e-3)
                var done = 0.0
                for (piece in pieces) {
                    ctx.ensureActive()
                    val text = decode(asr, Segments.slice(audio, piece, SAMPLE_RATE))
                    if (text.isNotEmpty()) raw += RawUtterance(piece.start, piece.end, piece.speaker, text)
                    done += piece.duration
                    onProgress(Stage.RECOGNITION, (done / total).toFloat())
                }
            } finally {
                asr.release()
            }

            // Пунктуация и заглавные буквы — локальная LLM, по каждому куску отдельно
            onProgress(Stage.PUNCTUATION, 0f)
            val (punctuated, stats) = punctuateAll(raw) { p -> onProgress(Stage.PUNCTUATION, p) }

            val result = Segments.finalize(punctuated)
            Log.i(TAG, "Готово: ${result.size} реплик; LLM: $stats")
            EngineResult(result, result.map { it.speaker }.distinct().size, stats)
        }
    }

    /** Повторная обработка фрагмента (по запросу пользователя): Parakeet + пунктуация LLM. */
    suspend fun recognizeFragment(samplesRaw: FloatArray): FragmentResult = mutex.withLock {
        withContext(Dispatchers.Default) {
            val audio = Preprocessor.process(samplesRaw)
            val whole = Seg(0.0, audio.size.toDouble() / SAMPLE_RATE, 0)
            val asr = createAsr()
            val pieces = try {
                Segments.splitLong(whole, audio, SAMPLE_RATE).map { piece ->
                    RawUtterance(piece.start, piece.end, 0, decode(asr, Segments.slice(audio, piece, SAMPLE_RATE, pad = 0.0)))
                }.filter { it.text.isNotEmpty() }
            } finally {
                asr.release()
            }
            val (punctuated, _) = punctuateAll(pieces) {}
            FragmentResult(punctuated.joinToString(" ") { it.text }.trim(), punctuated.any { it.byLlm })
        }
    }

    private suspend fun punctuateAll(
        raw: List<RawUtterance>,
        onProgress: (Float) -> Unit,
    ): Pair<List<RawUtterance>, LlmStats> {
        if (raw.isEmpty()) return raw to LlmStats()
        val ctx = currentCoroutineContext()
        val llm = try {
            LlmPunctuator(
                modelPath = requireModel(models.file(Models.LLM, Models.LLM_FILE)),
                cacheDir = models.cacheDir.absolutePath,
                threads = threads,
                systemPrompt = models.assetText(Models.LLM_PROMPT_ASSET).trim(),
                chatTemplate = models.assetText(Models.LLM_TEMPLATE_ASSET),
            )
        } catch (e: Throwable) {
            // UnsatisfiedLinkError и т.п. — это Error, поэтому ловим Throwable
            Log.e(TAG, "LLM не запустилась, пунктуация остаётся от распознавателя", e)
            return raw to LlmStats(unavailable = true)
        }
        var changed = 0
        var unchanged = 0
        var rejected = 0
        var skipped = 0
        val out = ArrayList<RawUtterance>(raw.size)
        try {
            raw.forEachIndexed { i, u ->
                ctx.ensureActive()
                val outcome = try {
                    llm.punctuate(u.text)
                } catch (e: Exception) {
                    Log.w(TAG, "LLM: ошибка на фрагменте, оставлен исходный текст", e)
                    LlmPunctuator.Outcome.Skipped(u.text)
                }
                when (outcome) {
                    is LlmPunctuator.Outcome.Accepted -> if (outcome.changed) changed++ else unchanged++
                    is LlmPunctuator.Outcome.Rejected -> rejected++
                    is LlmPunctuator.Outcome.Skipped -> skipped++
                }
                val llmChanged = outcome is LlmPunctuator.Outcome.Accepted && outcome.changed
                out += u.copy(text = outcome.text, byLlm = llmChanged)
                onProgress((i + 1f) / raw.size)
            }
        } finally {
            llm.close()
        }
        return out to LlmStats(changed, unchanged, rejected, skipped)
    }

    private fun decode(recognizer: OfflineRecognizer, samples: FloatArray): String {
        if (samples.isEmpty()) return ""
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer.decode(stream)
            recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    private suspend fun diarize(audio: FloatArray, onProgress: (Float) -> Unit): List<Seg> {
        val ctx = currentCoroutineContext()
        val segModel = requireModel(models.file(Models.SEGMENTATION, Models.SEGMENTATION_FILE))
        val embModel = requireModel(models.file(Models.EMBEDDING, Models.EMBEDDING_FILE))

        val sd = OfflineSpeakerDiarization(
            config = OfflineSpeakerDiarizationConfig(
                segmentation = OfflineSpeakerSegmentationModelConfig(
                    pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(model = segModel),
                    numThreads = threads,
                ),
                embedding = SpeakerEmbeddingExtractorConfig(model = embModel, numThreads = threads),
                // Число спикеров определяется автоматически по порогу
                clustering = FastClusteringConfig(numClusters = -1, threshold = CLUSTER_THRESHOLD),
                minDurationOn = 0.2f,
                minDurationOff = 0.3f,
            )
        )
        val diarized = try {
            sd.processWithCallback(audio, callback = DiarizationProgress(onProgress))
                .map { Seg(it.start.toDouble(), it.end.toDouble(), it.speaker) }.sortedBy { it.start }
        } finally {
            sd.release()
        }
        ctx.ensureActive()
        if (diarized.isEmpty()) return diarized

        // Разрез по паузам: на шумной записи pyannote склеивает реплики разных людей
        val segs = PauseSplitter.split(diarized, detectSpeech(audio))
        ctx.ensureActive()

        // Второй проход: отпечаток голоса каждого сегмента и переназначение спикеров
        val extractor = SpeakerEmbeddingExtractor(
            config = SpeakerEmbeddingExtractorConfig(model = embModel, numThreads = threads)
        )
        try {
            val embeddings = segs.mapIndexed { i, s ->
                ctx.ensureActive()
                onProgress(0.8f + 0.2f * i / segs.size)
                if (s.duration < SpeakerRefiner.MIN_EMBED_SEC) return@mapIndexed null
                val capped = if (s.duration > 30.0) s.copy(end = s.start + 30.0) else s
                val stream = extractor.createStream()
                try {
                    stream.acceptWaveform(Segments.slice(audio, capped, SAMPLE_RATE, pad = 0.0), SAMPLE_RATE)
                    stream.inputFinished()
                    if (extractor.isReady(stream)) SpeakerRefiner.normalizeInPlace(extractor.compute(stream)) else null
                } finally {
                    stream.release()
                }
            }
            onProgress(1f)
            return SpeakerRefiner.refine(segs, embeddings, allowMerge = true)
        } finally {
            extractor.release()
        }
    }

    /** Участки речи (начало, конец в секундах) по Silero VAD. */
    private suspend fun detectSpeech(audio: FloatArray): List<Pair<Double, Double>> {
        val ctx = currentCoroutineContext()
        val vad = Vad(
            assetManager = null,
            config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = requireModel(models.file(Models.VAD, Models.VAD_FILE)),
                    threshold = VAD_THRESHOLD,
                    minSilenceDuration = VAD_MIN_SILENCE,
                    minSpeechDuration = VAD_MIN_SPEECH,
                    windowSize = VAD_WINDOW,
                    maxSpeechDuration = VAD_MAX_SPEECH,
                ),
                sampleRate = SAMPLE_RATE,
                numThreads = 1,
            ),
        )
        val out = ArrayList<Pair<Double, Double>>()
        fun drain() {
            while (!vad.empty()) {
                val s = vad.front()
                out += s.start.toDouble() / SAMPLE_RATE to (s.start + s.samples.size).toDouble() / SAMPLE_RATE
                vad.pop()
            }
        }
        try {
            var i = 0
            while (i < audio.size) {
                if (i % (SAMPLE_RATE * 60) == 0) ctx.ensureActive()
                vad.acceptWaveform(audio.copyOfRange(i, minOf(i + VAD_WINDOW, audio.size)))
                drain()
                i += VAD_WINDOW
            }
            vad.flush()
            drain()
        } finally {
            vad.release()
        }
        return out
    }

    private fun createAsr(): OfflineRecognizer {
        fun path(name: String) = requireModel(models.file(Models.ASR, name))
        return OfflineRecognizer(
            config = OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = path("encoder.int8.onnx"),
                        decoder = path("decoder.int8.onnx"),
                        joiner = path("joiner.int8.onnx"),
                    ),
                    tokens = path("tokens.txt"),
                    modelType = "nemo_transducer",
                    numThreads = threads,
                )
            )
        )
    }

    /**
     * sherpa-onnx при отсутствующем или повреждённом файле модели завершает процесс из нативного кода
     * (exit или необработанное исключение C++), и приложение просто закрывается. Проверяем файлы заранее,
     * чтобы показать понятную ошибку в карточке задачи.
     */
    private fun requireModel(file: java.io.File): String {
        if (!file.isFile || file.length() == 0L) {
            throw IllegalStateException("Нет файла модели ${file.parentFile?.name}/${file.name}. Переустановите приложение.")
        }
        return file.absolutePath
    }

    companion object {
        private const val TAG = "TranscriptionEngine"

        /** Порог кластеризации голосов для CAM++ (подобран на тестовых записях, стабилен в 0,6–0,7). */
        const val CLUSTER_THRESHOLD = 0.7f

        /**
         * Параметры Silero VAD для поиска пауз. Порог 0,7 выше обычного 0,5: при 0,5 на шумной
         * записи (SNR 5 дБ) шум между репликами принимается за речь и паузы не находятся.
         */
        const val VAD_THRESHOLD = 0.7f
        const val VAD_MIN_SILENCE = 0.2f
        const val VAD_MIN_SPEECH = 0.1f
        const val VAD_MAX_SPEECH = 20f
        const val VAD_WINDOW = 512
    }
}

/**
 * Колбэк прогресса диаризации для sherpa-onnx.
 *
 * Нативный код (jni/offline-speaker-diarization.cc) ищет у колбэка метод с JVM-сигнатурой
 * `invoke(IIJ)Ljava/lang/Integer;`. У обычной лямбды Kotlin 2.x (компилируется через invokedynamic)
 * такого метода нет: JNI получает NoSuchMethodError и Android аварийно завершает приложение
 * на первом же отчёте о прогрессе. У явного класса нужный метод есть — это проверяет юнит-тест.
 */
class DiarizationProgress(private val onProgress: (Float) -> Unit) : (Int, Int, Long) -> Int {
    override fun invoke(numProcessedChunks: Int, numTotalChunks: Int, arg: Long): Int {
        if (numTotalChunks > 0) onProgress(0.8f * numProcessedChunks / numTotalChunks)
        return 0
    }
}

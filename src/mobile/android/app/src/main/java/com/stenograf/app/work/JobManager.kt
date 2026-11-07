package com.stenograf.app.work

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.stenograf.app.audio.AudioDecoder
import com.stenograf.app.audio.AudioImporter
import com.stenograf.app.data.Speaker
import com.stenograf.app.data.Transcript
import com.stenograf.app.data.TranscriptRepository
import com.stenograf.app.data.Utterance
import com.stenograf.app.engine.Stage
import com.stenograf.app.engine.TranscriptionEngine
import com.stenograf.app.models.ModelStore
import com.stenograf.app.models.ModelsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/** Состояние задачи для отображения в интерфейсе. */
data class JobUi(
    val id: String,
    val title: String,
    /** Что сейчас происходит: «В очереди», «Распознавание речи»… */
    val label: String,
    /** 0..1 или null, если прогресс неизвестен. */
    val progress: Float?,
    val waiting: Boolean,
    val error: String? = null,
) {
    val active get() = error == null
}

/**
 * Последовательная очередь расшифровок: задачи выполняются по одной, чтобы не перегружать память.
 * Пока очередь не пуста, [WorkService] держит процесс живым и показывает прогресс в уведомлении.
 */
class JobManager(
    private val context: Context,
    private val repository: TranscriptRepository,
    private val models: ModelStore,
    val engine: TranscriptionEngine,
) {
    private data class Task(val id: String, val title: String, val audioFile: File)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val queue = Channel<Task>(Channel.UNLIMITED)

    private val _jobs = MutableStateFlow<List<JobUi>>(emptyList())
    val jobs: StateFlow<List<JobUi>> = _jobs.asStateFlow()

    private val cancelled = HashSet<String>()
    private var running: Pair<String, Job>? = null

    init {
        scope.launch {
            for (task in queue) {
                val skip = synchronized(cancelled) { cancelled.remove(task.id) }
                if (skip) {
                    task.audioFile.delete()
                    continue
                }
                val job = scope.launch { execute(task) }
                synchronized(cancelled) { running = task.id to job }
                job.join()
                synchronized(cancelled) { running = null }
            }
        }
    }

    /** Расшифровать файл, выбранный пользователем в памяти устройства. */
    fun enqueueUri(uri: Uri) {
        val id = newId()
        val info = AudioImporter.describe(context, uri)
        put(JobUi(id, info.displayName, label = "Подготовка файла", progress = null, waiting = true))
        startService()
        scope.launch(Dispatchers.IO) {
            try {
                val file = File(repository.audioDir, "$id.${info.extension}")
                AudioImporter.copy(context, uri, file)
                submit(Task(id, info.displayName, file))
            } catch (e: Exception) {
                Log.e(TAG, "Импорт не удался", e)
                fail(id, e.message ?: "Не удалось прочитать файл")
            }
        }
    }

    /** Расшифровать запись, сделанную в приложении (файл уже во внутренней памяти). */
    fun enqueueFile(file: File, title: String) {
        val id = file.nameWithoutExtension
        put(JobUi(id, title, label = "В очереди", progress = null, waiting = true))
        startService()
        submit(Task(id, title, file))
    }

    fun cancel(id: String) {
        synchronized(cancelled) {
            val r = running
            if (r != null && r.first == id) r.second.cancel() else cancelled += id
        }
        remove(id)
    }

    fun dismiss(id: String) = remove(id)

    fun newRecordingFile(): File = File(repository.audioDir, "${newId()}.m4a")

    private fun submit(task: Task) {
        update(task.id) { it.copy(label = "В очереди", waiting = true) }
        queue.trySend(task)
    }

    private suspend fun execute(task: Task) {
        try {
            run(task)
        } catch (e: CancellationException) {
            task.audioFile.delete()
            remove(task.id)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM", e)
            fail(task.id, "Недостаточно памяти. Попробуйте запись покороче.")
            task.audioFile.delete()
        } catch (e: Throwable) {
            Log.e(TAG, "Задача ${task.id} завершилась ошибкой", e)
            fail(task.id, e.message ?: e.javaClass.simpleName)
            task.audioFile.delete()
        }
    }

    private suspend fun run(task: Task) {
        // Модели копируются из APK при первом запуске — ждём готовности
        if (models.state.value !is ModelsState.Ready) {
            update(task.id) { it.copy(label = "Подготовка моделей", waiting = true) }
            val s = models.state.first { it is ModelsState.Ready || it is ModelsState.Failed }
            if (s is ModelsState.Failed) throw IllegalStateException(s.message)
        }

        // Общий прогресс: чтение 5%, диаризация 35%, распознавание 45%, пунктуация LLM 15%
        fun report(stage: Stage, p: Float) {
            val (label, overall) = when (stage) {
                Stage.DECODING -> "Чтение аудио" to 0.05f * p
                Stage.DIARIZATION -> "Определение говорящих" to 0.05f + 0.35f * p
                Stage.RECOGNITION -> "Распознавание речи" to 0.40f + 0.45f * p
                Stage.PUNCTUATION -> "LLM: пунктуация" to 0.85f + 0.15f * p
            }
            update(task.id) { it.copy(label = label, progress = overall, waiting = false) }
        }

        val started = SystemClock.elapsedRealtime()
        report(Stage.DECODING, 0f)
        val audio = AudioDecoder.decode(task.audioFile, onProgress = { report(Stage.DECODING, it) })
        val result = engine.run(audio, ::report)
        val elapsedSec = (SystemClock.elapsedRealtime() - started) / 1000.0
        val durationSec = audio.size.toDouble() / AudioDecoder.SAMPLE_RATE

        val speakerIds = result.utterances.map { it.speaker }.distinct().sorted()
        val transcript = Transcript(
            id = task.id,
            title = task.title,
            createdAt = System.currentTimeMillis(),
            durationSec = durationSec,
            audioFileName = task.audioFile.name,
            modelId = "parakeet-tdt-0.6b-v3",
            speakers = speakerIds.map { Speaker(it, Speaker.label(it), it) },
            utterances = result.utterances.map { Utterance(it.speaker, it.start, it.end, it.text, it.byLlm) },
            processingSec = elapsedSec,
            llmChanged = result.llm.changed,
            llmRejected = result.llm.rejected,
            llmUnavailable = result.llm.unavailable,
        )
        Log.i(TAG, "RTF = ${"%.3f".format(elapsedSec / durationSec)} (${"%.1f".format(elapsedSec)} c на ${"%.1f".format(durationSec)} c аудио)")
        repository.save(transcript)
        remove(task.id)
    }

    private fun fail(id: String, message: String) =
        update(id) { it.copy(label = "Ошибка", progress = null, waiting = false, error = message) }

    private fun put(job: JobUi) = _jobs.update { it.filterNot { j -> j.id == job.id } + job }
    private fun remove(id: String) = _jobs.update { list -> list.filterNot { it.id == id } }
    private fun update(id: String, f: (JobUi) -> JobUi) =
        _jobs.update { list -> list.map { if (it.id == id) f(it) else it } }

    private fun startService() {
        try {
            context.startForegroundService(Intent(context, WorkService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось запустить сервис", e)
        }
    }

    private fun newId() = UUID.randomUUID().toString().replace("-", "").take(16)

    private companion object {
        const val TAG = "JobManager"
    }
}

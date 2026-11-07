package com.stenograf.app.ui.transcript

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stenograf.app.AppContainer
import com.stenograf.app.audio.AudioDecoder
import com.stenograf.app.data.Speaker
import com.stenograf.app.data.Transcript
import com.stenograf.app.export.Exporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Состояние повторной обработки фрагмента: распознавание Parakeet + пунктуация LLM. */
sealed interface ReprocessState {
    data object Idle : ReprocessState
    data object Running : ReprocessState
    data class Done(val text: String, val llmChanged: Boolean) : ReprocessState
    data class Error(val message: String) : ReprocessState
}

class TranscriptViewModel(private val c: AppContainer, private val id: String) : ViewModel() {

    val transcript: StateFlow<Transcript?> =
        c.repository.observe(id).stateIn(viewModelScope, SharingStarted.Eagerly, c.repository.get(id))

    val player = AudioPlayer(viewModelScope)

    /** Пока идёт расшифровка в очереди, повторную обработку не запускаем — модели не поместятся в память дважды. */
    val jobs = c.jobManager.jobs

    private val _reprocess = MutableStateFlow<ReprocessState>(ReprocessState.Idle)
    val reprocess: StateFlow<ReprocessState> = _reprocess.asStateFlow()
    private var reprocessJob: Job? = null

    init {
        viewModelScope.launch {
            val t = transcript.filterNotNull().first()
            c.repository.audioFile(t)?.let { player.load(it) }
        }
    }

    val hasAudio: Boolean get() = transcript.value?.let { c.repository.audioFile(it) } != null

    /** Исправление реплики: текст и/или спикер. [speakerId] = null — новый спикер. */
    fun editUtterance(index: Int, text: String, speakerId: Int?, byLlm: Boolean) = edit { t ->
        if (index !in t.utterances.indices) return@edit t
        var speakers = t.speakers
        val sid = speakerId ?: run {
            val newId = (t.speakers.maxOfOrNull { it.id } ?: -1) + 1
            speakers = speakers + Speaker(newId, Speaker.label(newId), newId)
            newId
        }
        val utterances = t.utterances.toMutableList()
        utterances[index] = utterances[index].copy(text = text, speakerId = sid, byLlm = byLlm)
        t.copy(speakers = speakers, utterances = utterances)
    }

    fun deleteUtterance(index: Int) = edit { t ->
        if (index !in t.utterances.indices) t else t.copy(utterances = t.utterances.filterIndexed { i, _ -> i != index })
    }

    private fun edit(transform: (Transcript) -> Transcript) {
        viewModelScope.launch { c.repository.update(id, transform) }
    }

    /** Повторная обработка фрагмента реплики: заново распознать Parakeet и расставить пунктуацию LLM. */
    fun reprocessFragment(index: Int) {
        val t = transcript.value ?: return
        val u = t.utterances.getOrNull(index) ?: return
        val file = c.repository.audioFile(t) ?: run {
            _reprocess.value = ReprocessState.Error("Аудиофайл не найден")
            return
        }
        reprocessJob?.cancel()
        _reprocess.value = ReprocessState.Running
        reprocessJob = viewModelScope.launch {
            _reprocess.value = try {
                val samples = AudioDecoder.decodeRange(file, u.start, u.end)
                val r = c.engine.recognizeFragment(samples)
                if (r.text.isBlank()) ReprocessState.Error("Речь в этом фрагменте не распознана")
                else ReprocessState.Done(r.text, r.llmChanged)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e("TranscriptViewModel", "Повторная обработка", e)
                ReprocessState.Error(e.message ?: "Ошибка обработки")
            }
        }
    }

    fun resetReprocess() {
        reprocessJob?.cancel()
        _reprocess.value = ReprocessState.Idle
    }

    /** Сохраняет JSON в файл, выбранный пользователем. */
    fun exportJson(context: Context, uri: Uri, onDone: (Boolean) -> Unit) {
        val t = transcript.value ?: return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                        out.write(Exporter.json(t).toByteArray(Charsets.UTF_8))
                    } != null
                } catch (e: Exception) {
                    Log.e("TranscriptViewModel", "Экспорт", e)
                    false
                }
            }
            onDone(ok)
        }
    }

    override fun onCleared() {
        player.release()
    }
}

package com.stenograf.app.ui.home

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stenograf.app.AppContainer
import com.stenograf.app.util.Format
import kotlinx.coroutines.launch

class HomeViewModel(private val c: AppContainer) : ViewModel() {

    val transcripts = c.repository.items
    val loaded = c.repository.loaded
    val jobs = c.jobManager.jobs
    val recording = c.recorder.state
    val models = c.models.state

    /** Импорт аудиофайла из памяти устройства — обработка начинается сразу. */
    fun import(uri: Uri) = c.jobManager.enqueueUri(uri)

    fun startRecording(): Boolean = c.recorder.start(c.jobManager.newRecordingFile())
    fun pauseRecording() = c.recorder.pause()
    fun resumeRecording() = c.recorder.resume()
    fun discardRecording() = c.recorder.discard()

    /** Завершает запись и сразу ставит её в обработку. false — запись пустая. */
    fun finishRecording(): Boolean {
        val file = c.recorder.stop() ?: return false
        c.jobManager.enqueueFile(file, "Запись ${Format.date(System.currentTimeMillis())}")
        return true
    }

    fun cancelJob(id: String) = c.jobManager.cancel(id)
    fun dismissJob(id: String) = c.jobManager.dismiss(id)

    fun delete(id: String) = viewModelScope.launch { c.repository.delete(id) }
}

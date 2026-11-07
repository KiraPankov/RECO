package com.stenograf.app.record

import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.sqrt

data class RecordingState(
    val active: Boolean = false,
    val paused: Boolean = false,
    val elapsedMs: Long = 0,
    /** Последние уровни громкости 0..1 для визуализации (новые в конце). */
    val levels: List<Float> = emptyList(),
)

/** Запись с микрофона в m4a (AAC). Живёт на уровне приложения, чтобы пережить поворот экрана. */
class RecordingController(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var ticker: Job? = null

    private var accumulatedMs = 0L
    private var resumedAt = 0L

    private val _state = MutableStateFlow(RecordingState())
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    fun start(target: File): Boolean {
        if (_state.value.active) return true
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(44100)
            r.setAudioEncodingBitRate(96_000)
            r.setOutputFile(target.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            file = target
            accumulatedMs = 0
            resumedAt = SystemClock.elapsedRealtime()
            _state.value = RecordingState(active = true)
            startTicker()
            try {
                context.startForegroundService(Intent(context, RecordingService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Сервис записи не запущен", e)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось начать запись", e)
            r.release()
            target.delete()
            false
        }
    }

    fun pause() {
        val r = recorder ?: return
        if (_state.value.paused) return
        r.pause()
        accumulatedMs += SystemClock.elapsedRealtime() - resumedAt
        _state.value = _state.value.copy(paused = true, elapsedMs = accumulatedMs)
    }

    fun resume() {
        val r = recorder ?: return
        if (!_state.value.paused) return
        r.resume()
        resumedAt = SystemClock.elapsedRealtime()
        _state.value = _state.value.copy(paused = false)
    }

    /** Останавливает запись и возвращает файл (или null, если запись не удалась). */
    fun stop(): File? {
        val r = recorder ?: return null
        val f = file
        val ok = try {
            r.stop()
            true
        } catch (e: RuntimeException) {
            // stop() бросает исключение, если данных не было записано
            false
        }
        cleanup()
        return if (ok && f != null && f.length() > 0) f else {
            f?.delete()
            null
        }
    }

    fun discard() {
        val f = file
        try {
            recorder?.stop()
        } catch (_: RuntimeException) {
        }
        cleanup()
        f?.delete()
    }

    private fun cleanup() {
        ticker?.cancel()
        recorder?.release()
        recorder = null
        file = null
        _state.value = RecordingState()
        context.stopService(Intent(context, RecordingService::class.java))
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                val s = _state.value
                val r = recorder ?: break
                if (!s.paused) {
                    val amp = try {
                        r.maxAmplitude
                    } catch (_: Exception) {
                        0
                    }
                    val level = sqrt((amp / 32767f).coerceIn(0f, 1f))
                    val levels = (s.levels + level).takeLast(MAX_LEVELS)
                    val elapsed = accumulatedMs + (SystemClock.elapsedRealtime() - resumedAt)
                    _state.value = s.copy(elapsedMs = elapsed, levels = levels)
                }
                delay(70)
            }
        }
    }

    private companion object {
        const val TAG = "RecordingController"
        const val MAX_LEVELS = 120
    }
}

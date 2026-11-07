package com.stenograf.app.ui.transcript

import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

data class PlayerState(
    val ready: Boolean = false,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
)

/** Простой плеер поверх MediaPlayer с периодическим обновлением позиции. */
class AudioPlayer(private val scope: CoroutineScope) {

    private var mp: MediaPlayer? = null
    private var ticker: Job? = null
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    fun load(file: File) {
        release()
        try {
            mp = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnPreparedListener { p ->
                    _state.update { it.copy(ready = true, durationMs = p.duration.toLong()) }
                }
                setOnCompletionListener { p ->
                    stopTicker()
                    _state.update { it.copy(playing = false, positionMs = p.duration.toLong()) }
                }
                setOnErrorListener { _, what, extra ->
                    Log.w(TAG, "MediaPlayer error $what/$extra")
                    stopTicker()
                    _state.update { it.copy(playing = false) }
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось открыть аудио", e)
        }
    }

    fun toggle() = if (_state.value.playing) pause() else play()

    fun play() {
        val p = mp ?: return
        if (!_state.value.ready) return
        val s = _state.value
        if (s.positionMs >= s.durationMs - 50) p.seekTo(0)
        p.start()
        _state.update { it.copy(playing = true) }
        startTicker()
    }

    fun pause() {
        val p = mp ?: return
        if (p.isPlaying) p.pause()
        stopTicker()
        _state.update { it.copy(playing = false, positionMs = p.currentPosition.toLong()) }
    }

    fun seekTo(ms: Long, andPlay: Boolean = false) {
        val p = mp ?: return
        if (!_state.value.ready) return
        val target = ms.coerceIn(0, _state.value.durationMs)
        p.seekTo(target.toInt())
        _state.update { it.copy(positionMs = target) }
        if (andPlay && !_state.value.playing) play()
    }

    private fun startTicker() {
        stopTicker()
        ticker = scope.launch {
            while (isActive) {
                val p = mp ?: break
                _state.update { it.copy(positionMs = p.currentPosition.toLong()) }
                delay(100)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    fun release() {
        stopTicker()
        mp?.release()
        mp = null
        _state.value = PlayerState()
    }

    private companion object {
        const val TAG = "AudioPlayer"
    }
}

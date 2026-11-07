package com.stenograf.app.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.nio.ByteOrder

class UnsupportedAudioException(message: String) : IOException(message)

/**
 * Декодирует любой аудиофайл, который понимает Android (mp3, m4a/aac, ogg/opus, wav, flac, amr,
 * звуковая дорожка mp4 и т.д.), в моно 16 кГц float — формат, который ожидают модели.
 */
object AudioDecoder {

    const val SAMPLE_RATE = 16000

    /** Ограничение длины, чтобы не исчерпать память телефона. */
    const val MAX_DURATION_SEC = 3 * 60 * 60

    /** Декодирует фрагмент [fromSec, toSec] — для повторной обработки отдельной реплики. */
    suspend fun decodeRange(file: File, fromSec: Double, toSec: Double): FloatArray {
        val all = decode(file, fromSec = fromSec, toSec = toSec, allowShort = true)
        return all
    }

    suspend fun decode(
        file: File,
        onProgress: (Float) -> Unit = {},
        fromSec: Double = 0.0,
        toSec: Double? = null,
        allowShort: Boolean = false,
    ): FloatArray {
        val ctx = currentCoroutineContext()
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw UnsupportedAudioException("В файле нет звуковой дорожки")
            extractor.selectTrack(track)
            val inFormat = extractor.getTrackFormat(track)
            val mime = inFormat.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (inFormat.containsKey(MediaFormat.KEY_DURATION)) inFormat.getLong(MediaFormat.KEY_DURATION) else -1L
            if (durationUs > MAX_DURATION_SEC * 1_000_000L) {
                throw UnsupportedAudioException("Запись длиннее 3 часов — разделите её на части")
            }

            codec = try {
                MediaCodec.createDecoderByType(mime)
            } catch (e: Exception) {
                throw UnsupportedAudioException("Формат $mime не поддерживается этим устройством")
            }
            codec.configure(inFormat, null, null, 0)
            codec.start()

            val fromUs = (fromSec * 1_000_000).toLong()
            val toUs = toSec?.let { (it * 1_000_000).toLong() }
            if (fromUs > 0) extractor.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            /** Время (мкс) первого декодированного буфера — относительно него режем фрагмент. */
            var firstPtsUs = -1L

            val estimated = if (durationUs > 0) (durationUs / 1_000_000.0 * SAMPLE_RATE).toInt() + SAMPLE_RATE else SAMPLE_RATE * 60
            val out = FloatArrayBuilder(estimated)
            var resampler: Resampler? = null
            var channels = inFormat.optInt(MediaFormat.KEY_CHANNEL_COUNT, 1)
            var sampleRate = inFormat.optInt(MediaFormat.KEY_SAMPLE_RATE, SAMPLE_RATE)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var mono = FloatArray(0)

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var lastReport = 0L

            while (!outputDone) {
                ctx.ensureActive()
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        channels = f.optInt(MediaFormat.KEY_CHANNEL_COUNT, channels)
                        sampleRate = f.optInt(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        encoding = f.optInt(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                        resampler = null
                    }

                    outIndex >= 0 -> {
                        if (toUs != null && info.size > 0 && info.presentationTimeUs > toUs + 500_000) {
                            // Нужный фрагмент уже декодирован
                            codec.releaseOutputBuffer(outIndex, false)
                            outputDone = true
                            continue
                        }
                        if (info.size > 0 && firstPtsUs < 0) firstPtsUs = info.presentationTimeUs
                        if (info.size > 0) {
                            val buffer = codec.getOutputBuffer(outIndex)!!
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            buffer.order(ByteOrder.LITTLE_ENDIAN)
                            val ch = channels.coerceAtLeast(1)
                            val frames = when (encoding) {
                                AudioFormat.ENCODING_PCM_FLOAT -> info.size / 4 / ch
                                AudioFormat.ENCODING_PCM_8BIT -> info.size / ch
                                AudioFormat.ENCODING_PCM_16BIT -> info.size / 2 / ch
                                else -> throw UnsupportedAudioException("Неподдерживаемый формат PCM: $encoding")
                            }
                            if (mono.size < frames) mono = FloatArray(frames)
                            when (encoding) {
                                AudioFormat.ENCODING_PCM_FLOAT -> {
                                    val fb = buffer.asFloatBuffer()
                                    for (i in 0 until frames) {
                                        var s = 0f
                                        for (c in 0 until ch) s += fb.get()
                                        mono[i] = s / ch
                                    }
                                }

                                AudioFormat.ENCODING_PCM_8BIT -> {
                                    for (i in 0 until frames) {
                                        var s = 0f
                                        for (c in 0 until ch) s += ((buffer.get().toInt() and 0xFF) - 128) / 128f
                                        mono[i] = s / ch
                                    }
                                }

                                else -> {
                                    val sb = buffer.asShortBuffer()
                                    for (i in 0 until frames) {
                                        var s = 0f
                                        for (c in 0 until ch) s += sb.get() / 32768f
                                        mono[i] = s / ch
                                    }
                                }
                            }
                            val r = resampler ?: Resampler(sampleRate, SAMPLE_RATE).also { resampler = it }
                            r.process(mono, frames, out)

                            if (out.size > MAX_DURATION_SEC * SAMPLE_RATE) {
                                throw UnsupportedAudioException("Запись длиннее 3 часов — разделите её на части")
                            }
                            val now = System.currentTimeMillis()
                            if (durationUs > 0 && now - lastReport > 250) {
                                lastReport = now
                                onProgress((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            onProgress(1f)
            var samples = out.toArray()
            if (fromUs > 0 || toUs != null) {
                val base = maxOf(firstPtsUs, 0L)
                val a = (((fromUs - base) / 1_000_000.0) * SAMPLE_RATE).toInt().coerceIn(0, samples.size)
                val b = toUs?.let { (((it - base) / 1_000_000.0) * SAMPLE_RATE).toInt().coerceIn(a, samples.size) } ?: samples.size
                samples = samples.copyOfRange(a, b)
            }
            if (!allowShort && samples.size < SAMPLE_RATE / 2) throw UnsupportedAudioException("Запись слишком короткая")
            return samples
        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            codec?.release()
            extractor.release()
        }
    }

    private fun MediaFormat.optInt(key: String, default: Int): Int =
        if (containsKey(key)) getInteger(key) else default
}

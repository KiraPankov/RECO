package com.stenograf.app.models

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/** Модели, которые входят в APK (каталоги в assets/models; файлы — см. app/models.gradle.kts). */
object Models {
    /** Распознавание речи: NVIDIA Parakeet TDT 0.6B v3. */
    const val ASR = "asr-parakeet"

    /** Локальная LLM для пунктуации и заглавных букв: Qwen3-0.6B (int4, LiteRT-LM, без режима размышлений). */
    const val LLM = "llm-qwen3"
    const val LLM_FILE = "qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm"

    /** Сегментация речи для диаризации: pyannote segmentation 3.0. */
    const val SEGMENTATION = "segmentation"
    const val SEGMENTATION_FILE = "model.int8.onnx"

    /** «Отпечаток голоса» для диаризации: 3D-Speaker CAM++ (zh/en, common advanced). */
    const val EMBEDDING = "embedding"
    const val EMBEDDING_FILE = "3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx"

    /** Детектор речи Silero VAD: паузы для разрезания склеенных реплик разных говорящих. */
    const val VAD = "vad"
    const val VAD_FILE = "silero_vad.onnx"

    /** Промпт и шаблон чата LLM (assets/llm) — общие с tools/eval. */
    const val LLM_PROMPT_ASSET = "llm/system_prompt.txt"
    const val LLM_TEMPLATE_ASSET = "llm/qwen3_chat_template.jinja"
}

sealed interface ModelsState {
    data object Checking : ModelsState
    data class Preparing(val progress: Float) : ModelsState
    data object Ready : ModelsState
    data class Failed(val message: String) : ModelsState
}

/**
 * Модели лежат в APK как assets. Нейросетевым библиотекам нужен путь к файлу,
 * поэтому при первом запуске (и после обновления APK) они копируются во внутреннюю
 * память приложения. Интернет не используется.
 */
class ModelStore(private val context: Context) {

    private val root = File(context.filesDir, "models")
    private val _state = MutableStateFlow<ModelsState>(ModelsState.Checking)
    val state: StateFlow<ModelsState> = _state.asStateFlow()

    fun file(model: String, name: String) = File(root, "$model/$name")
    fun dir(model: String) = File(root, model)

    /** Текстовый файл из assets APK (промпт и шаблон LLM). */
    fun assetText(path: String): String = context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }

    /** Каталог для служебного кэша LiteRT-LM. */
    val cacheDir: File get() = context.cacheDir

    suspend fun prepare() = withContext(Dispatchers.IO) {
        try {
            val stamp = File(root, ".apk-version")
            val apkVersion = apkStamp()
            val manifest = context.assets.open("models/manifest.txt").bufferedReader().readLines()
                .filter { it.isNotBlank() }
                .map { line -> line.split('\t').let { it[0] to it[1].toLong() } }
            if (manifest.isEmpty()) throw IllegalStateException("В APK нет моделей")

            // Та же версия APK — докопируем только недостающие или повреждённые файлы.
            // После установки нового APK копируем всё заново: модели могли смениться
            // (например, дообученная), а размеры файлов у моделей одной архитектуры совпадают.
            val sameApk = stamp.exists() && stamp.readText() == apkVersion
            val missing = if (sameApk) manifest.filter { (path, size) -> File(root, path).length() != size } else manifest
            if (missing.isEmpty()) {
                _state.value = ModelsState.Ready
                return@withContext
            }
            if (!sameApk) {
                stamp.delete()
                // Удаляем файлы, которых больше нет в APK
                val keep = manifest.map { File(root, it.first).canonicalPath }.toSet()
                root.walkBottomUp().filter { it.isFile && it.name != ".apk-version" && it.canonicalPath !in keep }
                    .forEach { it.delete() }
            }

            val total = missing.sumOf { it.second }.coerceAtLeast(1)
            val free = root.apply { mkdirs() }.usableSpace
            if (free < total + 100L * 1024 * 1024) {
                throw IllegalStateException(
                    "Недостаточно места: нужно ${total / 1_000_000 + 100} МБ, свободно ${free / 1_000_000} МБ"
                )
            }

            _state.value = ModelsState.Preparing(0f)
            var copied = 0L
            var lastReport = 0L
            val buf = ByteArray(1 shl 20)
            for ((path, size) in missing) {
                val target = File(root, path)
                target.parentFile?.mkdirs()
                val tmp = File(target.path + ".tmp")
                context.assets.open("models/$path").use { input ->
                    FileOutputStream(tmp).use { out ->
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            copied += n
                            val now = System.currentTimeMillis()
                            if (now - lastReport > 150) {
                                lastReport = now
                                _state.value = ModelsState.Preparing(copied.toFloat() / total)
                            }
                        }
                    }
                }
                if (tmp.length() != size) throw IllegalStateException("Файл модели повреждён: $path")
                target.delete()
                if (!tmp.renameTo(target)) throw IllegalStateException("Не удалось сохранить $path")
            }
            stamp.writeText(apkVersion)
            _state.value = ModelsState.Ready
        } catch (e: Exception) {
            Log.e(TAG, "Подготовка моделей не удалась", e)
            _state.value = ModelsState.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun apkStamp(): String {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        return info.lastUpdateTime.toString()
    }

    private companion object {
        const val TAG = "ModelStore"
    }
}

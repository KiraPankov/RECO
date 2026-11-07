// Загрузка моделей для сборки APK.
//
// Модели (~1 ГБ) не хранятся в git. При первой сборке эта задача скачивает их
// с GitHub-релизов sherpa-onnx и раскладывает в app/models-assets/models/<id>/.
// Каталог подключается к APK как assets, так что готовое приложение работает
// полностью офлайн. Повторные сборки ничего не скачивают.

import java.net.HttpURLConnection
import java.net.URI
import javax.inject.Inject

data class ModelSource(
    val id: String,
    val url: String,
    /** Файлы, которые нужно взять из архива (путь внутри каталога модели). */
    val include: List<String>,
)

val sherpaReleases = "https://github.com/k2-fsa/sherpa-onnx/releases/download"

val modelSources = listOf(
    ModelSource(
        "asr-parakeet",
        "$sherpaReleases/asr-models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2",
        listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"),
    ),
    // Локальная LLM для пунктуации: Qwen3-0.6B int4 под LiteRT-LM, вариант без режима размышлений (Apache 2.0)
    ModelSource(
        "llm-qwen3",
        "https://huggingface.co/litert-community/Qwen3-0.6B-int4/resolve/main/qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm",
        listOf("qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm"),
    ),
    ModelSource(
        "segmentation",
        "$sherpaReleases/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2",
        listOf("model.int8.onnx"),
    ),
    // «Отпечаток голоса» для диаризации: 3D-Speaker CAM++
    ModelSource(
        "embedding",
        "$sherpaReleases/speaker-recongition-models/3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx",
        listOf("3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx"),
    ),
    // Детектор речи Silero VAD: находит паузы, по которым разрезаются склеенные реплики
    ModelSource(
        "vad",
        "$sherpaReleases/asr-models/silero_vad.onnx",
        listOf("silero_vad.onnx"),
    ),
)

abstract class FetchModelsTask : DefaultTask() {
    @get:Inject abstract val archives: ArchiveOperations
    @get:Inject abstract val fs: FileSystemOperations

    @get:Internal abstract val assetsDir: DirectoryProperty
    @get:Internal abstract val cacheDir: DirectoryProperty
    @get:Internal abstract val sources: ListProperty<ModelSource>

    @TaskAction
    fun fetch() {
        val modelsRoot = assetsDir.get().asFile.resolve("models").apply { mkdirs() }
        // Убираем модели, которых больше нет в списке, иначе они попадут в APK
        val ids = sources.get().map { it.id }.toSet()
        modelsRoot.listFiles()?.filter { it.isDirectory && it.name !in ids }?.forEach {
            logger.lifecycle("Удаляется модель, которой нет в списке: ${it.name}")
            it.deleteRecursively()
        }
        for (m in sources.get()) {
            val target = modelsRoot.resolve(m.id)
            val marker = target.resolve(".ok")

            // Маркер хранит адрес модели: если он сменился (другая модель), скачиваем заново
            if (marker.isFile && marker.readText() == m.url) continue
            val fileName = m.url.substringAfterLast('/')
            val download = cacheDir.get().asFile.apply { mkdirs() }.resolve(fileName)
            logger.lifecycle("Модель ${m.id}: загрузка $fileName")
            downloadWithResume(m.url, download)

            target.deleteRecursively()
            target.mkdirs()
            if (fileName.endsWith(".tar.bz2")) {
                logger.lifecycle("Модель ${m.id}: распаковка")
                fs.copy {
                    from(archives.tarTree(archives.bzip2(download)))
                    // Убираем верхний каталог архива
                    eachFile {
                        val segs = relativePath.segments
                        relativePath = RelativePath(!isDirectory, *segs.drop(1).toTypedArray())
                    }
                    include(m.include.map { "*/$it" })
                    includeEmptyDirs = false
                    into(target)
                }
            } else {
                download.copyTo(target.resolve(fileName), overwrite = true)
            }
            val missing = m.include.filterNot { it.endsWith("**") }.filterNot { target.resolve(it).isFile }
            if (missing.isNotEmpty()) throw GradleException("Модель ${m.id}: нет файлов $missing")
            target.resolve(".ok").writeText(m.url)
            download.delete()
        }
        // Манифест: список файлов и размеров — по нему приложение копирует модели и проверяет целостность.
        val manifest = modelsRoot.walkTopDown()
            .filter { it.isFile && it.name != ".ok" && it.name != "manifest.txt" }
            .map { "${it.relativeTo(modelsRoot).invariantSeparatorsPath}\t${it.length()}" }
            .sorted()
            .joinToString("\n")
        modelsRoot.resolve("manifest.txt").writeText(manifest + "\n")
    }

    private fun downloadWithResume(url: String, file: java.io.File) {
        repeat(5) { attempt ->
            try {
                val have = if (file.exists()) file.length() else 0L
                val conn = URI(url).toURL().openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 30_000
                conn.readTimeout = 60_000
                if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
                val code = conn.responseCode
                if (code == 416) return // уже скачан полностью
                if (code != 200 && code != 206) throw GradleException("HTTP $code для $url")
                val append = code == 206
                val total = (if (append) have else 0L) + conn.contentLengthLong
                conn.inputStream.use { input ->
                    java.io.FileOutputStream(file, append).use { out -> input.copyTo(out, 1 shl 20) }
                }
                if (conn.contentLengthLong > 0 && file.length() < total) throw java.io.IOException("обрыв")
                return
            } catch (e: java.io.IOException) {
                logger.warn("Повтор загрузки (${attempt + 1}/5): ${e.message}")
                Thread.sleep(3000)
            }
        }
        throw GradleException("Не удалось скачать $url")
    }
}

val fetchModels = tasks.register<FetchModelsTask>("fetchModels") {
    group = "build setup"
    description = "Скачивает модели распознавания, диаризации и LLM в app/models-assets"
    assetsDir.set(layout.projectDirectory.dir("models-assets"))
    cacheDir.set(layout.projectDirectory.dir(".model-cache"))
    sources.set(modelSources)
    doNotTrackState("Модели кэшируются по маркерам .ok, хэшировать 1 ГБ на каждой сборке не нужно")
}

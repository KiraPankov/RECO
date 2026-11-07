package com.stenograf.app.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Хранит расшифровки как JSON-файлы во внутренней памяти приложения.
 * Все расшифровки держатся в памяти — для черновика этого достаточно.
 */
class TranscriptRepository(context: Context) {

    private val dir = File(context.filesDir, "transcripts").apply { mkdirs() }
    val audioDir = File(context.filesDir, "audio").apply { mkdirs() }

    private val mutex = Mutex()
    private val _items = MutableStateFlow<List<Transcript>>(emptyList())
    private val _loaded = MutableStateFlow(false)

    /** Все расшифровки, новые сверху. */
    val items: StateFlow<List<Transcript>> = _items.asStateFlow()
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val list = dir.listFiles { f -> f.extension == "json" }.orEmpty().mapNotNull { f ->
                try {
                    Transcript.fromJson(JSONObject(f.readText()))
                } catch (e: Exception) {
                    Log.w(TAG, "Не удалось прочитать ${f.name}", e)
                    null
                }
            }
            _items.value = list.sortedByDescending { it.createdAt }
            _loaded.value = true
        }
    }

    fun observe(id: String): Flow<Transcript?> = items.map { list -> list.firstOrNull { it.id == id } }

    fun get(id: String): Transcript? = _items.value.firstOrNull { it.id == id }

    fun audioFile(t: Transcript): File? =
        t.audioFileName?.let { File(audioDir, it) }?.takeIf { it.exists() }

    suspend fun save(t: Transcript) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val tmp = File(dir, "${t.id}.json.tmp")
            tmp.writeText(t.toJson().toString())
            val target = File(dir, "${t.id}.json")
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
            _items.update { list ->
                (list.filterNot { it.id == t.id } + t).sortedByDescending { it.createdAt }
            }
        }
    }

    suspend fun update(id: String, transform: (Transcript) -> Transcript) {
        val current = get(id) ?: return
        save(transform(current))
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val t = _items.value.firstOrNull { it.id == id }
            File(dir, "$id.json").delete()
            t?.audioFileName?.let { File(audioDir, it).delete() }
            _items.update { list -> list.filterNot { it.id == id } }
        }
    }

    private companion object {
        const val TAG = "TranscriptRepository"
    }
}

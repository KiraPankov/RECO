package com.stenograf.app.data

import org.json.JSONArray
import org.json.JSONObject

data class Speaker(
    val id: Int,
    val name: String,
    /** Индекс в палитре цветов спикеров. */
    val color: Int,
) {
    companion object {
        /** Подпись в интерфейсе: «Спикер 1», «Спикер 2»… */
        fun label(id: Int) = "Спикер ${id + 1}"

        /** Идентификатор в JSON-выводе: speaker_1, speaker_2… */
        fun jsonId(id: Int) = "speaker_${id + 1}"
    }
}

data class Utterance(
    val speakerId: Int,
    /** Начало реплики, секунды от начала записи. */
    val start: Double,
    /** Конец реплики, секунды от начала записи. */
    val end: Double,
    val text: String,
    /** Локальная LLM изменила пунктуацию или регистр в этой реплике (слова не менялись). */
    val byLlm: Boolean = false,
)

data class Transcript(
    val id: String,
    val title: String,
    val createdAt: Long,
    val durationSec: Double,
    /** Имя файла аудио в каталоге приложения (для воспроизведения). */
    val audioFileName: String?,
    val modelId: String,
    val speakers: List<Speaker>,
    val utterances: List<Utterance>,
    /** Сколько секунд заняла обработка на устройстве (для расчёта RTF). */
    val processingSec: Double = 0.0,
    /** Фрагментов, где LLM исправила пунктуацию; ответов LLM, отброшенных из-за изменённых слов. */
    val llmChanged: Int = 0,
    val llmRejected: Int = 0,
    /** LLM не запустилась на устройстве. */
    val llmUnavailable: Boolean = false,
) {
    /** Real-time factor: время обработки / длительность записи. */
    val rtf: Double get() = if (durationSec > 0) processingSec / durationSec else 0.0

    fun speaker(id: Int): Speaker =
        speakers.firstOrNull { it.id == id } ?: Speaker(id, Speaker.label(id), id)

    /** Только спикеры, у которых есть хотя бы одна реплика. */
    val activeSpeakers: List<Speaker>
        get() {
            val used = utterances.mapTo(HashSet()) { it.speakerId }
            return speakers.filter { it.id in used }
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("version", 1)
        put("id", id)
        put("title", title)
        put("createdAt", createdAt)
        put("durationSec", durationSec)
        put("audioFileName", audioFileName ?: JSONObject.NULL)
        put("modelId", modelId)
        put("processingSec", processingSec)
        put("llmChanged", llmChanged)
        put("llmRejected", llmRejected)
        put("llmUnavailable", llmUnavailable)
        put("speakers", JSONArray().apply {
            speakers.forEach {
                put(JSONObject().put("id", it.id).put("name", it.name).put("color", it.color))
            }
        })
        put("utterances", JSONArray().apply {
            utterances.forEach {
                put(
                    JSONObject()
                        .put("speakerId", it.speakerId)
                        .put("start", it.start)
                        .put("end", it.end)
                        .put("text", it.text)
                        .put("byLlm", it.byLlm)
                )
            }
        })
    }

    companion object {
        fun fromJson(o: JSONObject): Transcript {
            val sp = o.getJSONArray("speakers")
            val ut = o.getJSONArray("utterances")
            return Transcript(
                id = o.getString("id"),
                title = o.getString("title"),
                createdAt = o.getLong("createdAt"),
                durationSec = o.getDouble("durationSec"),
                audioFileName = if (o.isNull("audioFileName")) null else o.getString("audioFileName"),
                modelId = o.optString("modelId", ""),
                processingSec = o.optDouble("processingSec", 0.0),
                llmChanged = o.optInt("llmChanged", 0),
                llmRejected = o.optInt("llmRejected", 0),
                llmUnavailable = o.optBoolean("llmUnavailable", false),
                speakers = (0 until sp.length()).map {
                    val s = sp.getJSONObject(it)
                    Speaker(s.getInt("id"), s.getString("name"), s.optInt("color", s.getInt("id")))
                },
                utterances = (0 until ut.length()).map {
                    val u = ut.getJSONObject(it)
                    Utterance(
                        speakerId = u.getInt("speakerId"),
                        start = u.getDouble("start"),
                        end = u.getDouble("end"),
                        text = u.getString("text"),
                        byLlm = u.optBoolean("byLlm", false),
                    )
                },
            )
        }
    }
}

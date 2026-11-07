package com.stenograf.app.export

import com.stenograf.app.data.Speaker
import com.stenograf.app.data.Transcript
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToLong

/**
 * Экспорт в формате, заданном организаторами:
 * {"transcript": [{"speaker_id": "speaker_1", "start_ms": 1250, "end_ms": 4380, "text": "Добрый день!"}]}
 */
object Exporter {

    const val MIME = "application/json"

    fun json(t: Transcript): String {
        val items = JSONArray()
        t.utterances.sortedBy { it.start }.forEach { u ->
            items.put(
                JSONObject()
                    .put("speaker_id", Speaker.jsonId(u.speakerId))
                    .put("start_ms", toMs(u.start))
                    .put("end_ms", toMs(u.end))
                    .put("text", u.text)
            )
        }
        return JSONObject().put("transcript", items).toString(2)
    }

    fun toMs(seconds: Double): Long = (seconds * 1000).roundToLong().coerceAtLeast(0)

    fun fileName(t: Transcript): String =
        t.title.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").trim().take(60).ifBlank { "transcript" } + ".json"
}

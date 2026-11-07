package com.stenograf.app.audio

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Копирует выбранный пользователем файл во внутреннее хранилище приложения. */
object AudioImporter {

    data class SourceInfo(val displayName: String, val extension: String)

    fun describe(context: Context, uri: Uri): SourceInfo {
        var name: String? = null
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0)
            }
        } catch (_: Exception) {
        }
        if (name == null) name = uri.lastPathSegment?.substringAfterLast('/')
        val raw = name ?: "Аудио"
        val extFromName = raw.substringAfterLast('.', "").lowercase().takeIf { it.length in 1..5 }
        val extFromMime = context.contentResolver.getType(uri)
            ?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        val ext = extFromName ?: extFromMime ?: "audio"
        val title = if (extFromName != null) raw.substringBeforeLast('.') else raw
        return SourceInfo(title.ifBlank { "Аудио" }, ext)
    }

    fun copy(context: Context, uri: Uri, target: File) {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Не удалось открыть файл")
        input.use { i -> FileOutputStream(target).use { o -> i.copyTo(o, 256 * 1024) } }
        if (target.length() == 0L) {
            target.delete()
            throw IOException("Файл пустой")
        }
    }
}

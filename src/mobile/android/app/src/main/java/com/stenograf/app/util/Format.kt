package com.stenograf.app.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong

object Format {
    private val ru = Locale("ru")

    /** 75.3 → «1:15», 3725 → «1:02:05». */
    fun clock(seconds: Double): String {
        val total = seconds.coerceAtLeast(0.0).toLong()
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** Всегда с часами: «00:01:15» — для текстового экспорта. */
    fun clockFull(seconds: Double): String {
        val total = seconds.coerceAtLeast(0.0).toLong()
        return "%02d:%02d:%02d".format(total / 3600, (total % 3600) / 60, total % 60)
    }

    /** Формат времени SRT: «00:01:15,300». */
    fun srt(seconds: Double): String {
        val ms = (seconds.coerceAtLeast(0.0) * 1000).roundToLong()
        return "%02d:%02d:%02d,%03d".format(ms / 3_600_000, (ms % 3_600_000) / 60_000, (ms % 60_000) / 1000, ms % 1000)
    }

    /** «1 ч 5 мин», «12 мин», «45 с». */
    fun duration(seconds: Double): String {
        val total = seconds.roundToLong()
        val h = total / 3600
        val m = (total % 3600) / 60
        return when {
            h > 0 -> if (m > 0) "$h ч $m мин" else "$h ч"
            m > 0 -> "$m мин"
            else -> "$total с"
        }
    }

    fun date(millis: Long): String = SimpleDateFormat("d MMM, HH:mm", ru).format(Date(millis))

    fun dateLong(millis: Long): String = SimpleDateFormat("d MMMM yyyy, HH:mm", ru).format(Date(millis))

    fun megabytes(bytes: Long): String = "${(bytes / 1_000_000.0).roundToLong()} МБ"

    /** «1 участник», «2 участника», «5 участников». */
    fun participants(n: Int): String {
        val mod10 = n % 10
        val mod100 = n % 100
        val word = when {
            mod10 == 1 && mod100 != 11 -> "участник"
            mod10 in 2..4 && mod100 !in 12..14 -> "участника"
            else -> "участников"
        }
        return "$n $word"
    }
}

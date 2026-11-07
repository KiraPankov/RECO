package com.stenograf.app.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.stenograf.app.MainActivity
import com.stenograf.app.R

object Notifications {
    const val CHANNEL_PROGRESS = "progress"
    const val CHANNEL_RECORDING = "recording"

    const val ID_WORK = 1001
    const val ID_RECORDING = 1002


    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "Обработка", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RECORDING, "Запись", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
    }

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun progress(context: Context, title: String, text: String, progress: Float?): Notification =
        Notification.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent(context))
            .apply {
                if (progress == null) setProgress(0, 0, true)
                else setProgress(1000, (progress * 1000).toInt().coerceIn(0, 1000), false)
            }
            .build()

    fun recording(context: Context): Notification =
        Notification.Builder(context, CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Идёт запись")
            .setContentText("Нажмите, чтобы вернуться в приложение")
            .setOngoing(true)
            .setUsesChronometer(true)
            .setContentIntent(openAppIntent(context))
            .build()
}

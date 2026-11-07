package com.stenograf.app.record

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.stenograf.app.work.Notifications

/** Держит запись активной, когда приложение свёрнуто (Android требует для этого foreground-сервис). */
class RecordingService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val notification = Notifications.recording(this)
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(Notifications.ID_RECORDING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(Notifications.ID_RECORDING, notification)
            }
        } catch (e: Exception) {
            Log.w("RecordingService", "startForeground не удался", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }
}

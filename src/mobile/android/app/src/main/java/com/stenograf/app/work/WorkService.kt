package com.stenograf.app.work

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.stenograf.app.RecoApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground-сервис: пока в очереди есть задачи, процесс не будет убит системой,
 * процессор не уснёт, а в шторке показывается прогресс.
 */
class WorkService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private var observing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = Notifications.progress(this, "RECO", "Подготовка…", null)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(Notifications.ID_WORK, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(Notifications.ID_WORK, notification)
        }
        acquireWakeLock()
        if (!observing) {
            observing = true
            val jobs = (application as RecoApp).container.jobManager.jobs
            scope.launch {
                jobs.collectLatest { list ->
                    val active = list.filter { it.active }
                    if (active.isEmpty()) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        return@collectLatest
                    }
                    val current = active.firstOrNull { !it.waiting } ?: active.first()
                    val more = active.size - 1
                    val text = buildString {
                        append(current.label)
                        current.progress?.let { append(" · ${(it * 100).toInt()}%") }
                        if (more > 0) append(" · ещё $more в очереди")
                    }
                    getSystemService(NotificationManager::class.java).notify(
                        Notifications.ID_WORK,
                        Notifications.progress(this@WorkService, current.title, text, current.progress),
                    )
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RECO:work").apply {
            setReferenceCounted(false)
            acquire(4 * 60 * 60 * 1000L)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}

package com.stenograf.app

import android.app.Application
import com.stenograf.app.data.TranscriptRepository
import com.stenograf.app.engine.TranscriptionEngine
import com.stenograf.app.models.ModelStore
import com.stenograf.app.record.RecordingController
import com.stenograf.app.work.JobManager
import com.stenograf.app.work.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppContainer(app: Application) {
    val repository = TranscriptRepository(app)
    val models = ModelStore(app)
    val engine = TranscriptionEngine(models)
    val jobManager = JobManager(app, repository, models, engine)
    val recorder = RecordingController(app)
}

class RecoApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.createChannels(this)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch { container.repository.load() }
        // Модели входят в APK; при первом запуске копируются во внутреннюю память
        scope.launch { container.models.prepare() }
    }
}

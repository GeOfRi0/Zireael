package com.indirici.app

import android.app.Application
import com.indirici.app.data.local.AppDatabase
import com.indirici.app.data.settings.SettingsRepository
import com.indirici.app.data.storage.DownloadsFileSaver
import com.indirici.app.domain.security.EphemeralSessionStore
import com.indirici.app.engine.DirectEngine
import com.indirici.app.engine.EngineRouter
import com.indirici.app.engine.FfmpegHlsEngine
import com.indirici.app.engine.MediaEngine
import com.indirici.app.service.DownloadQueueCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class IndiriciApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var database: AppDatabase
        private set

    lateinit var settingsRepository: SettingsRepository
        private set

    lateinit var ephemeralSessionStore: EphemeralSessionStore
        private set

    lateinit var engineRouter: EngineRouter
        private set

    lateinit var queueCoordinator: DownloadQueueCoordinator
        private set

    override fun onCreate() {
        super.onCreate()

        database = AppDatabase.getInstance(this)
        settingsRepository = SettingsRepository(this)
        ephemeralSessionStore = EphemeralSessionStore()

        val fileSaver = DownloadsFileSaver(this)
        val directEngine = DirectEngine(
            context = this,
            downloadDao = database.downloadDao(),
            fileSaver = fileSaver,
            ephemeralSessionStore = ephemeralSessionStore
        )
        val mediaEngine = MediaEngine(
            context = this,
            downloadDao = database.downloadDao(),
            fileSaver = fileSaver,
            ephemeralSessionStore = ephemeralSessionStore
        )
        val ffmpegHlsEngine = FfmpegHlsEngine(
            context = this,
            downloadDao = database.downloadDao(),
            fileSaver = fileSaver,
            ephemeralSessionStore = ephemeralSessionStore
        )

        appScope.launch {
            runCatching { mediaEngine.ensureInitialized() }
        }

        engineRouter = EngineRouter(
            directEngine = directEngine,
            mediaEngine = mediaEngine,
            ffmpegHlsEngine = ffmpegHlsEngine
        )

        queueCoordinator = DownloadQueueCoordinator(
            appContext = this,
            downloadDao = database.downloadDao(),
            settingsRepository = settingsRepository,
            engineRouter = engineRouter,
            ephemeralSessionStore = ephemeralSessionStore
        )
    }
}

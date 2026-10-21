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
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class IndiriciApplication : Application() {

    val sharedOkHttpClient: OkHttpClient by lazy {
        val dispatcher = Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 16
        }
        OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    val database: AppDatabase by lazy {
        AppDatabase.getInstance(this)
    }

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(this)
    }

    val ephemeralSessionStore: EphemeralSessionStore by lazy {
        EphemeralSessionStore()
    }

    val engineRouter: EngineRouter by lazy {
        val fileSaver = DownloadsFileSaver(this)
        val directEngine = DirectEngine(
            context = this,
            downloadDao = database.downloadDao(),
            fileSaver = fileSaver,
            ephemeralSessionStore = ephemeralSessionStore,
            okHttpClient = sharedOkHttpClient
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
        EngineRouter(
            directEngine = directEngine,
            mediaEngine = mediaEngine,
            ffmpegHlsEngine = ffmpegHlsEngine
        )
    }

    val queueCoordinator: DownloadQueueCoordinator by lazy {
        DownloadQueueCoordinator(
            appContext = this,
            downloadDao = database.downloadDao(),
            settingsRepository = settingsRepository,
            engineRouter = engineRouter,
            ephemeralSessionStore = ephemeralSessionStore
        )
    }
}

package com.example.data.engine

import android.content.Context
import com.example.data.local.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * TurboDownloadManager:
 * Singleton coordinator providing application-scoped instances of TurboDownloadEngine
 * and DownloadQueueManager. Guarantees that background downloads continue running
 * without interruption even if the UI/ViewModel lifecycle ends.
 */
object TurboDownloadManager {

    // Persistent supervisor scope alive for the lifetime of the application process
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var engineInstance: TurboDownloadEngine? = null

    @Volatile
    private var queueManagerInstance: DownloadQueueManager? = null

    @Volatile
    private var networkMonitorInstance: NetworkConnectivityMonitor? = null

    fun getScope(): CoroutineScope = appScope

    fun getNetworkMonitor(context: Context): NetworkConnectivityMonitor {
        return networkMonitorInstance ?: synchronized(this) {
            networkMonitorInstance ?: NetworkConnectivityMonitor(context.applicationContext).also {
                networkMonitorInstance = it
            }
        }
    }

    fun getEngine(context: Context): TurboDownloadEngine {
        return engineInstance ?: synchronized(this) {
            engineInstance ?: run {
                val appContext = context.applicationContext
                val db = AppDatabase.getInstance(appContext)
                TurboDownloadEngine(
                    context = appContext,
                    downloadDao = db.downloadDao(),
                    scope = appScope
                ).also { engineInstance = it }
            }
        }
    }

    fun getQueueManager(context: Context): DownloadQueueManager {
        return queueManagerInstance ?: synchronized(this) {
            queueManagerInstance ?: run {
                val appContext = context.applicationContext
                val db = AppDatabase.getInstance(appContext)
                val engine = getEngine(appContext)
                val networkMonitor = getNetworkMonitor(appContext)
                DownloadQueueManager(
                    downloadDao = db.downloadDao(),
                    downloadEngine = engine,
                    scope = appScope,
                    networkMonitor = networkMonitor
                ).also { queueManagerInstance = it }
            }
        }
    }

    @Volatile
    private var torrentEngineInstance: TorrentDownloadEngine? = null

    fun getTorrentEngine(context: Context): TorrentDownloadEngine {
        return torrentEngineInstance ?: synchronized(this) {
            torrentEngineInstance ?: run {
                val appContext = context.applicationContext
                val db = AppDatabase.getInstance(appContext)
                TorrentDownloadEngine(
                    context = appContext,
                    downloadDao = db.downloadDao(),
                    scope = appScope
                ).also { torrentEngineInstance = it }
            }
        }
    }

    @Volatile
    private var vaultManagerInstance: VaultManager? = null

    fun getVaultManager(context: Context): VaultManager {
        return vaultManagerInstance ?: synchronized(this) {
            vaultManagerInstance ?: VaultManager(context.applicationContext).also {
                vaultManagerInstance = it
            }
        }
    }
}

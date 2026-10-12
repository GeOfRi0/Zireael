package com.example.data.engine

import com.example.data.local.DownloadDao
import com.example.data.local.DownloadEntity
import com.example.data.model.ChromeHeaderConfig
import com.example.data.model.DownloadStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DownloadQueueManager(
    private val downloadDao: DownloadDao,
    private val downloadEngine: TurboDownloadEngine,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val networkMonitor: NetworkConnectivityMonitor? = null
) {
    private val mutex = Mutex()

    private val _maxConcurrentDownloads = MutableStateFlow(2)
    val maxConcurrentDownloads: StateFlow<Int> = _maxConcurrentDownloads.asStateFlow()

    private val _isQueuePaused = MutableStateFlow(false)
    val isQueuePaused: StateFlow<Boolean> = _isQueuePaused.asStateFlow()

    // Sadece Wi-Fi üzerinden indir ayarı
    private val _onlyWifiMode = MutableStateFlow(false)
    val onlyWifiMode: StateFlow<Boolean> = _onlyWifiMode.asStateFlow()

    fun setOnlyWifiMode(enabled: Boolean) {
        _onlyWifiMode.value = enabled
        scope.launch(Dispatchers.IO) {
            if (enabled && networkMonitor?.isWifi?.value == false) {
                // Sadece wifi seçildi ama şu an wifi değil -> duraklat ve kaydet
                pauseAllActiveDownloadsForNetwork("Hücresel veri engellendi (Sadece Wi-Fi modu aktif)")
            } else if (!enabled || networkMonitor?.isWifi?.value == true) {
                processQueue()
            }
        }
    }

    init {
        // Register completion callback from engine to process next queue items
        downloadEngine.onDownloadStateChanged = { id, status ->
            if (status == DownloadStatus.COMPLETED || status == DownloadStatus.FAILED || status == DownloadStatus.PAUSED || status == DownloadStatus.CANCELLED) {
                scope.launch(Dispatchers.IO) {
                    processQueue()
                }
            }
        }

        // Android ConnectivityManager callbacks
        networkMonitor?.onNetworkLostCallback = {
            scope.launch(Dispatchers.IO) {
                pauseAllActiveDownloadsForNetwork("Ağ bağlantısı koptu")
            }
        }

        networkMonitor?.onNetworkAvailableCallback = {
            scope.launch(Dispatchers.IO) {
                // İnternet geri geldiğinde indirmeleri otomatik olarak kaldığı yerden başlat
                if (!_isQueuePaused.value) {
                    if (!_onlyWifiMode.value || networkMonitor.isWifi.value) {
                        resumeAllPausedByNetwork()
                    }
                }
            }
        }
    }

    private suspend fun pauseAllActiveDownloadsForNetwork(reason: String) = mutex.withLock {
        val activeIds = downloadEngine.getActiveDownloadIds()
        activeIds.forEach { id ->
            downloadEngine.pauseDownload(id)
        }
        downloadEngine.flushAllActiveDownloadsToDatabase()
    }

    private suspend fun resumeAllPausedByNetwork() = mutex.withLock {
        val pausedItems = downloadDao.getDownloadsByStatusList(DownloadStatus.PAUSED.name)
        pausedItems.forEach { item ->
            downloadDao.updateStatus(item.id, DownloadStatus.QUEUED.name)
        }
        processQueueInternal()
    }

    fun setMaxConcurrentDownloads(limit: Int) {
        _maxConcurrentDownloads.value = limit.coerceIn(1, 5)
        scope.launch(Dispatchers.IO) {
            processQueue()
        }
    }

    fun pauseQueue() {
        _isQueuePaused.value = true
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                val active = downloadEngine.getActiveDownloadIds()
                active.forEach { id ->
                    downloadEngine.pauseDownload(id)
                }
            }
        }
    }

    fun resumeQueue() {
        _isQueuePaused.value = false
        scope.launch(Dispatchers.IO) {
            processQueue()
        }
    }

    suspend fun enqueueItem(
        url: String,
        customFileName: String? = null,
        threadCount: Int = 4,
        headerConfig: ChromeHeaderConfig? = null
    ): String = mutex.withLock {
        // Enqueue as QUEUED in Room database
        val id = downloadEngine.createDownloadEntity(url, customFileName, threadCount, headerConfig)
        if (!_isQueuePaused.value) {
            processQueueInternal()
        }
        return id
    }

    suspend fun enqueueBatch(
        items: List<Pair<String, String?>>,
        threadCount: Int = 4,
        headerConfig: ChromeHeaderConfig? = null
    ) = mutex.withLock {
        items.forEach { (url, title) ->
            downloadEngine.createDownloadEntity(url, title, threadCount, headerConfig)
        }
        if (!_isQueuePaused.value) {
            processQueueInternal()
        }
    }

    suspend fun startAllQueuedAndPaused() = mutex.withLock {
        _isQueuePaused.value = false
        // Update all PAUSED items to QUEUED in Room
        val paused = downloadDao.getDownloadsByStatusList(DownloadStatus.PAUSED.name)
        paused.forEach { item ->
            downloadDao.updateStatus(item.id, DownloadStatus.QUEUED.name)
        }
        processQueueInternal()
    }

    suspend fun processQueue() = mutex.withLock {
        if (_isQueuePaused.value) return@withLock
        processQueueInternal()
    }

    private suspend fun processQueueInternal() {
        val activeIds = downloadEngine.getActiveDownloadIds()
        val currentActiveCount = activeIds.size
        val limit = _maxConcurrentDownloads.value
        val slotsAvailable = (limit - currentActiveCount).coerceAtLeast(0)

        if (slotsAvailable > 0) {
            // Find oldest QUEUED items from Room database
            val queuedItems = downloadDao.getDownloadsByStatusList(DownloadStatus.QUEUED.name)
            val itemsToStart = queuedItems.take(slotsAvailable)

            itemsToStart.forEach { item ->
                downloadEngine.startDownload(item.id)
            }
        }
    }
}

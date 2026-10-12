package com.example.data.repository

import com.example.data.engine.DownloadQueueManager
import com.example.data.engine.MediaSniffer
import com.example.data.engine.TorrentDownloadEngine
import com.example.data.engine.TorrentDownloadProgress
import com.example.data.engine.TurboDownloadEngine
import com.example.data.local.DownloadDao
import com.example.data.local.DownloadEntity
import com.example.data.model.ChromeHeaderConfig
import com.example.data.model.DownloadStatus
import com.example.data.model.ExtractedMedia
import com.example.data.model.SegmentProgress
import com.example.data.engine.VaultAuthMode
import com.example.data.engine.VaultFileItem
import com.example.data.engine.VaultManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

class DownloadRepository(
    private val downloadDao: DownloadDao,
    private val downloadEngine: TurboDownloadEngine,
    private val mediaSniffer: MediaSniffer,
    private val queueManager: DownloadQueueManager,
    private val torrentEngine: TorrentDownloadEngine? = null,
    private val vaultManager: VaultManager? = null
) {
    val allDownloads: Flow<List<DownloadEntity>> = downloadDao.getAllDownloads()
    val activeDownloads: Flow<List<DownloadEntity>> = downloadDao.getActiveDownloads()
    val segmentProgressMap: StateFlow<Map<String, List<SegmentProgress>>> = downloadEngine.segmentProgressMap
    val speedMap: StateFlow<Map<String, Long>> = downloadEngine.speedMap
    val maxConcurrentDownloads: StateFlow<Int> = queueManager.maxConcurrentDownloads
    val isQueuePaused: StateFlow<Boolean> = queueManager.isQueuePaused
    val onlyWifiMode: StateFlow<Boolean> = queueManager.onlyWifiMode
    val maxSpeedLimitBytesPerSec: StateFlow<Long> = downloadEngine.maxSpeedLimitBytesPerSec

    fun setOnlyWifiMode(enabled: Boolean) {
        queueManager.setOnlyWifiMode(enabled)
    }

    fun setMaxSpeedLimit(bytesPerSec: Long) {
        downloadEngine.setMaxSpeedLimit(bytesPerSec)
    }

    suspend fun enqueueDownload(
        url: String,
        customFileName: String? = null,
        threadCount: Int = 4,
        headerConfig: ChromeHeaderConfig? = null
    ): String {
        return queueManager.enqueueItem(url, customFileName, threadCount, headerConfig)
    }

    suspend fun enqueueBatch(
        items: List<Pair<String, String?>>,
        threadCount: Int = 4,
        headerConfig: ChromeHeaderConfig? = null
    ) {
        queueManager.enqueueBatch(items, threadCount, headerConfig)
    }

    suspend fun startAllQueuedAndPaused() {
        queueManager.startAllQueuedAndPaused()
    }

    fun pauseQueue() {
        queueManager.pauseQueue()
    }

    fun resumeQueue() {
        queueManager.resumeQueue()
    }

    fun setMaxConcurrentDownloads(limit: Int) {
        queueManager.setMaxConcurrentDownloads(limit)
    }

    suspend fun sniffMedia(url: String, headerConfig: ChromeHeaderConfig? = null): List<ExtractedMedia> {
        return mediaSniffer.sniff(url, headerConfig)
    }

    suspend fun analyzeVideoResolutions(
        media: ExtractedMedia,
        currentWebUrl: String? = null,
        headerConfig: ChromeHeaderConfig? = null
    ): List<ExtractedMedia> {
        return mediaSniffer.analyzeVideoResolutions(media, currentWebUrl, headerConfig)
    }

    val activeTorrents: StateFlow<Map<String, TorrentDownloadProgress>>? = torrentEngine?.activeTorrents

    suspend fun startTorrentDownload(url: String, customTitle: String? = null): String {
        val engine = torrentEngine ?: throw IllegalStateException("TorrentEngine is not initialized")
        return engine.downloadTorrentOrMagnet(url, customTitle)
    }

    fun isTorrentOrMagnet(url: String?): Boolean {
        return TorrentDownloadEngine.isTorrentOrMagnet(url)
    }

    fun pauseDownload(id: String) {
        if (id.startsWith("torrent_")) {
            torrentEngine?.pauseTorrent(id)
        } else {
            downloadEngine.pauseDownload(id)
        }
    }

    fun resumeDownload(id: String) {
        if (id.startsWith("torrent_")) {
            torrentEngine?.resumeTorrent(id)
        } else {
            downloadEngine.resumeDownload(id)
        }
    }

    fun prioritizeDownload(id: String) {
        if (id.startsWith("torrent_")) {
            torrentEngine?.resumeTorrent(id)
        } else {
            downloadEngine.startDownload(id)
        }
    }

    fun cancelDownload(id: String) {
        if (id.startsWith("torrent_")) {
            torrentEngine?.cancelTorrent(id)
        } else {
            downloadEngine.cancelDownload(id)
        }
    }

    suspend fun deleteDownload(id: String, deleteFileFromStorage: Boolean) {
        val entity = downloadDao.getDownloadById(id)
        if (entity != null) {
            if (id.startsWith("torrent_")) {
                torrentEngine?.cancelTorrent(id, deleteFiles = deleteFileFromStorage)
            } else {
                downloadEngine.cancelDownload(id)
                if (deleteFileFromStorage && entity.filePath.isNotBlank()) {
                    val file = File(entity.filePath)
                    if (file.exists()) file.delete()
                }
                downloadDao.deleteById(id)
            }
        }
    }

    suspend fun retryDownload(id: String) {
        val entity = downloadDao.getDownloadById(id) ?: return
        val updated = entity.copy(
            status = DownloadStatus.QUEUED.name,
            errorMessage = null,
            downloadedBytes = 0L,
            speedBytesPerSec = 0L
        )
        downloadDao.update(updated)
        queueManager.processQueue()
    }

    suspend fun flushAllActiveDownloadsToDatabase() {
        downloadEngine.flushAllActiveDownloadsToDatabase()
    }

    suspend fun clearHistory() {
        downloadDao.clearAll()
    }

    // Vault operations
    val isVaultUnlocked: StateFlow<Boolean>? = vaultManager?.isUnlocked

    suspend fun encryptFileToVault(file: File, deleteSource: Boolean = true): Result<File> {
        val vm = vaultManager ?: return Result.failure(IllegalStateException("VaultManager is not initialized"))
        return vm.encryptFileToVault(file, deleteSource)
    }

    suspend fun decryptFileFromVault(vaultFile: File, targetDir: File): Result<File> {
        val vm = vaultManager ?: return Result.failure(IllegalStateException("VaultManager is not initialized"))
        return vm.decryptFileFromVault(vaultFile, targetDir)
    }

    fun listVaultFiles(): List<VaultFileItem> = vaultManager?.listVaultFiles() ?: emptyList()

    fun deleteVaultFile(vaultFile: File): Boolean = vaultManager?.deleteVaultFile(vaultFile) ?: false

    fun isVaultConfigured(): Boolean = vaultManager?.isVaultConfigured() ?: false

    fun getVaultAuthMode(): VaultAuthMode = vaultManager?.getAuthMode() ?: VaultAuthMode.PIN_ONLY

    fun setVaultAuthMode(mode: VaultAuthMode) {
        vaultManager?.setAuthMode(mode)
    }

    fun setVaultPin(pin: String) {
        vaultManager?.setPin(pin)
    }

    fun verifyVaultPin(pin: String): Boolean = vaultManager?.verifyPin(pin) ?: false

    fun unlockVault() {
        vaultManager?.unlock()
    }

    fun lockVault() {
        vaultManager?.lock()
    }
}

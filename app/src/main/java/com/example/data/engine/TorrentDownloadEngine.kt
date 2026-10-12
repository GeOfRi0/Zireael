package com.example.data.engine

import android.content.Context
import android.util.Log
import com.example.data.local.DownloadDao
import com.example.data.local.DownloadEntity
import com.example.data.model.DownloadStatus
import com.example.data.service.DownloadForegroundService
import com.frostwire.jlibtorrent.AlertListener
import com.frostwire.jlibtorrent.SessionManager
import com.frostwire.jlibtorrent.TorrentHandle
import com.frostwire.jlibtorrent.TorrentInfo
import com.frostwire.jlibtorrent.alerts.AddTorrentAlert
import com.frostwire.jlibtorrent.alerts.Alert
import com.frostwire.jlibtorrent.alerts.AlertType
import com.frostwire.jlibtorrent.alerts.MetadataReceivedAlert
import com.frostwire.jlibtorrent.alerts.TorrentAlert
import com.frostwire.jlibtorrent.alerts.TorrentErrorAlert
import com.frostwire.jlibtorrent.alerts.TorrentFinishedAlert
import com.frostwire.jlibtorrent.alerts.TorrentPausedAlert
import com.frostwire.jlibtorrent.alerts.TorrentResumedAlert
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Real-time progress model for active Torrent downloads.
 */
data class TorrentDownloadProgress(
    val id: String,
    val infoHash: String,
    val fileName: String,
    val status: DownloadStatus,
    val progress: Float,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val downloadSpeedBytesPerSec: Long,
    val uploadSpeedBytesPerSec: Long,
    val seeds: Int,
    val peers: Int
)

/**
 * TorrentDownloadEngine:
 * High-performance P2P / BitTorrent and Magnet download engine powered by FrostWire jlibtorrent.
 *
 * Runs concurrently with [TurboDownloadEngine], processing '.torrent' files and 'magnet://' links.
 * Coordinates with Room database [DownloadDao], [DownloadForegroundService], and
 * [DownloadStorageManager] to save downloaded content directly to public shared storage.
 */
class TorrentDownloadEngine(
    private val context: Context,
    private val downloadDao: DownloadDao,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "TorrentDownloadEngine"

        /**
         * Checks whether a given URL or file path points to a torrent or magnet resource.
         */
        fun isTorrentOrMagnet(url: String?): Boolean {
            if (url.isNullOrBlank()) return false
            val trimmed = url.trim()
            val lower = trimmed.lowercase()
            return lower.startsWith("magnet:") ||
                lower.startsWith("magnet://") ||
                lower.endsWith(".torrent") ||
                lower.contains(".torrent?") ||
                lower.contains("xt=urn:btih:") ||
                lower.contains("xt=urn:btmh:")
        }
    }

    private var isLibTorrentAvailable: Boolean = false
    private var sessionManager: SessionManager? = null

    // Map of downloadId -> TorrentHandle
    private val activeHandles = ConcurrentHashMap<String, TorrentHandle>()

    // Map of infoHash (lowercase) -> downloadId
    private val infoHashToIdMap = ConcurrentHashMap<String, String>()

    // Set of paused torrent IDs
    private val pausedTorrentIds = ConcurrentHashMap.newKeySet<String>()

    // Real-time progress state
    private val _activeTorrents = MutableStateFlow<Map<String, TorrentDownloadProgress>>(emptyMap())
    val activeTorrents: StateFlow<Map<String, TorrentDownloadProgress>> = _activeTorrents.asStateFlow()

    // Callbacks for queue and UI integration
    var onDownloadStateChanged: ((id: String, status: DownloadStatus) -> Unit)? = null
    var onDownloadProgressChanged: ((id: String, progress: Float, speed: Long) -> Unit)? = null

    // Background polling monitor job
    private var progressMonitorJob: Job? = null

    init {
        initSessionManager()
    }

    private fun initSessionManager() {
        try {
            val sm = SessionManager()
            setupAlertListener(sm)
            sm.start()
            sessionManager = sm
            isLibTorrentAvailable = true
            Log.i(TAG, "FrostWire jlibtorrent SessionManager initialized successfully")
        } catch (t: Throwable) {
            Log.w(TAG, "jlibtorrent native library initialization unavailable: ${t.message}")
            isLibTorrentAvailable = false
        }
    }

    private fun setupAlertListener(sm: SessionManager) {
        sm.addListener(object : AlertListener {
            override fun types(): IntArray? = null // Listen to all alerts

            override fun alert(alert: Alert<*>) {
                try {
                    handleAlert(alert)
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling jlibtorrent alert: ${alert.what()}", e)
                }
            }
        })
    }

    private fun handleAlert(alert: Alert<*>) {
        when (alert) {
            is AddTorrentAlert -> {
                val handle = alert.handle()
                val hash = handle.infoHash().toString().lowercase()
                val id = infoHashToIdMap[hash] ?: "torrent_$hash"
                infoHashToIdMap[hash] = id
                activeHandles[id] = handle
                Log.d(TAG, "Torrent added: id=$id, hash=$hash")
                startProgressMonitorIfNeeded()
            }

            is MetadataReceivedAlert -> {
                val handle = alert.handle()
                val hash = handle.infoHash().toString().lowercase()
                val id = infoHashToIdMap[hash] ?: "torrent_$hash"
                val tf = handle.torrentFile()
                val name = tf?.name()?.ifBlank { handle.status().name() } ?: handle.status().name()
                val totalBytes = tf?.totalSize() ?: handle.status().total()

                scope.launch(Dispatchers.IO) {
                    downloadDao.getDownloadById(id)?.let { entity ->
                        val updated = entity.copy(
                            fileName = if (name.isNotBlank()) name else entity.fileName,
                            totalBytes = if (totalBytes > 0) totalBytes else entity.totalBytes,
                            status = DownloadStatus.DOWNLOADING.name
                        )
                        downloadDao.update(updated)
                    }
                }
                Log.d(TAG, "Torrent metadata received: id=$id, name=$name, size=$totalBytes")
            }

            is TorrentFinishedAlert -> {
                val handle = alert.handle()
                val hash = handle.infoHash().toString().lowercase()
                val id = infoHashToIdMap[hash] ?: "torrent_$hash"
                val status = handle.status()
                val totalBytes = status.total()

                scope.launch(Dispatchers.IO) {
                    downloadDao.updateProgress(
                        id = id,
                        downloaded = totalBytes,
                        total = totalBytes,
                        speed = 0L,
                        status = DownloadStatus.COMPLETED.name
                    )

                    // Register with MediaStore
                    val saveDir = DownloadStorageManager.getDownloadDirectory(context)
                    val completedFile = File(saveDir, status.name())
                    if (completedFile.exists()) {
                        DownloadStorageManager.registerWithMediaStore(
                            context = context,
                            file = completedFile,
                            mimeType = "application/x-bittorrent",
                            totalBytes = totalBytes
                        )
                    }

                    onDownloadStateChanged?.invoke(id, DownloadStatus.COMPLETED)
                }
                Log.i(TAG, "Torrent finished: id=$id, name=${status.name()}")
            }

            is TorrentErrorAlert -> {
                val handle = alert.handle()
                val hash = handle.infoHash().toString().lowercase()
                val id = infoHashToIdMap[hash] ?: "torrent_$hash"
                val errMessage = alert.error().message()

                scope.launch(Dispatchers.IO) {
                    downloadDao.getDownloadById(id)?.let { entity ->
                        downloadDao.update(
                            entity.copy(
                                status = DownloadStatus.FAILED.name,
                                errorMessage = errMessage
                            )
                        )
                    }
                    onDownloadStateChanged?.invoke(id, DownloadStatus.FAILED)
                }
                Log.e(TAG, "Torrent error: id=$id, error=$errMessage")
            }

            is TorrentPausedAlert -> {
                val handle = alert.handle()
                val hash = handle.infoHash().toString().lowercase()
                val id = infoHashToIdMap[hash] ?: "torrent_$hash"
                scope.launch(Dispatchers.IO) {
                    downloadDao.updateStatus(id, DownloadStatus.PAUSED.name)
                    onDownloadStateChanged?.invoke(id, DownloadStatus.PAUSED)
                }
            }

            is TorrentResumedAlert -> {
                val handle = alert.handle()
                val hash = handle.infoHash().toString().lowercase()
                val id = infoHashToIdMap[hash] ?: "torrent_$hash"
                scope.launch(Dispatchers.IO) {
                    downloadDao.updateStatus(id, DownloadStatus.DOWNLOADING.name)
                    onDownloadStateChanged?.invoke(id, DownloadStatus.DOWNLOADING)
                }
            }
        }
    }

    /**
     * Initiates download for a torrent file, URL or magnet link.
     * Returns the persistent download ID in Room DB.
     */
    suspend fun downloadTorrentOrMagnet(url: String, customTitle: String? = null): String = withContext(Dispatchers.IO) {
        val trimmedUrl = url.trim()
        val saveDir = DownloadStorageManager.getDownloadDirectory(context)
        saveDir.mkdirs()

        val isMagnet = MagnetUriParser.isMagnet(trimmedUrl)

        val (downloadId, displayName, totalSize, infoHash) = if (isMagnet) {
            val magnet = MagnetUriParser.parse(trimmedUrl)
            val hash = magnet?.infoHash ?: trimmedUrl.substringAfter("urn:btih:").substringBefore('&').trim()
            val id = "torrent_${hash.lowercase()}"
            val name = customTitle ?: magnet?.displayName ?: "Torrent_${hash.take(8)}"
            val size = magnet?.exactLength ?: -1L
            Quadruple(id, name, size, hash)
        } else {
            // .torrent file (local or remote URL)
            val torrentFile = if (trimmedUrl.startsWith("http://", ignoreCase = true) || trimmedUrl.startsWith("https://", ignoreCase = true)) {
                downloadRemoteTorrentFile(trimmedUrl)
            } else {
                File(trimmedUrl.removePrefix("file://"))
            }

            if (torrentFile != null && torrentFile.exists() && isLibTorrentAvailable) {
                try {
                    val ti = TorrentInfo(torrentFile)
                    val hash = ti.infoHash().toString()
                    val id = "torrent_${hash.lowercase()}"
                    val name = customTitle ?: ti.name().ifBlank { torrentFile.nameWithoutExtension }
                    val size = ti.totalSize()
                    Quadruple(id, name, size, hash)
                } catch (e: Exception) {
                    val hash = System.currentTimeMillis().toString()
                    Quadruple("torrent_$hash", customTitle ?: torrentFile.nameWithoutExtension, -1L, hash)
                }
            } else {
                val hash = System.currentTimeMillis().toString()
                val name = customTitle ?: trimmedUrl.substringAfterLast('/').substringBefore('?').ifBlank { "Torrent İndirmesi" }
                Quadruple("torrent_$hash", name, -1L, hash)
            }
        }

        infoHashToIdMap[infoHash.lowercase()] = downloadId

        // 1. Persist initial entity in Room database
        val destFile = File(saveDir, displayName)
        val entity = DownloadEntity(
            id = downloadId,
            url = trimmedUrl,
            fileName = displayName,
            filePath = destFile.absolutePath,
            mimeType = "application/x-bittorrent",
            category = "TORRENT",
            totalBytes = totalSize,
            downloadedBytes = 0L,
            status = DownloadStatus.DOWNLOADING.name,
            speedBytesPerSec = 0L,
            createdAt = System.currentTimeMillis()
        )
        downloadDao.insert(entity)

        // 2. Dispatch to SessionManager if available
        val sm = sessionManager
        if (sm != null && isLibTorrentAvailable) {
            try {
                if (!sm.isRunning) {
                    sm.start()
                }

                if (isMagnet) {
                    sm.download(trimmedUrl, saveDir)
                } else {
                    val localTorrentFile = if (trimmedUrl.startsWith("http://", ignoreCase = true) || trimmedUrl.startsWith("https://", ignoreCase = true)) {
                        downloadRemoteTorrentFile(trimmedUrl)
                    } else {
                        File(trimmedUrl.removePrefix("file://"))
                    }
                    if (localTorrentFile != null && localTorrentFile.exists()) {
                        val ti = TorrentInfo(localTorrentFile)
                        sm.download(ti, saveDir)
                    } else {
                        sm.download(trimmedUrl, saveDir)
                    }
                }
                startProgressMonitorIfNeeded()
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to start torrent in SessionManager", t)
                downloadDao.updateStatus(downloadId, DownloadStatus.FAILED.name)
            }
        } else {
            Log.w(TAG, "SessionManager unavailable, torrent enqueued in database only")
        }

        onDownloadStateChanged?.invoke(downloadId, DownloadStatus.DOWNLOADING)
        return@withContext downloadId
    }

    private suspend fun downloadRemoteTorrentFile(url: String): File? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).build()
            val response = OkHttpProvider.client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null
            val body = response.body ?: return@withContext null
            val tempFile = File(context.cacheDir, "torrent_${System.currentTimeMillis()}.torrent")
            FileOutputStream(tempFile).use { fos ->
                body.byteStream().copyTo(fos)
            }
            tempFile
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching remote .torrent file from $url", e)
            null
        }
    }

    private fun startProgressMonitorIfNeeded() {
        if (progressMonitorJob?.isActive == true) return

        progressMonitorJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                if (activeHandles.isEmpty()) {
                    delay(2000)
                    continue
                }

                val currentProgressMap = mutableMapOf<String, TorrentDownloadProgress>()

                activeHandles.forEach { (id, handle) ->
                    try {
                        val status = handle.status()
                        val downloaded = status.totalDone()
                        val total = if (status.total() > 0) status.total() else status.totalWanted()
                        val dlSpeed = status.downloadPayloadRate().toLong().coerceAtLeast(0L)
                        val ulSpeed = status.uploadPayloadRate().toLong().coerceAtLeast(0L)
                        val seeds = status.numSeeds()
                        val peers = status.numPeers()
                        val progress = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else 0f
                        val isPaused = pausedTorrentIds.contains(id)

                        val downloadStatus = when {
                            status.isFinished -> DownloadStatus.COMPLETED
                            isPaused -> DownloadStatus.PAUSED
                            else -> DownloadStatus.DOWNLOADING
                        }

                        currentProgressMap[id] = TorrentDownloadProgress(
                            id = id,
                            infoHash = handle.infoHash().toString(),
                            fileName = status.name().ifBlank { id },
                            status = downloadStatus,
                            progress = progress,
                            downloadedBytes = downloaded,
                            totalBytes = total,
                            downloadSpeedBytesPerSec = dlSpeed,
                            uploadSpeedBytesPerSec = ulSpeed,
                            seeds = seeds,
                            peers = peers
                        )

                        // Update database periodically
                        downloadDao.updateProgress(
                            id = id,
                            downloaded = downloaded,
                            total = total,
                            speed = dlSpeed,
                            status = downloadStatus.name
                        )

                        // Update Foreground Notification
                        DownloadForegroundService.startOrUpdate(
                            context = context,
                            downloadId = id,
                            fileName = status.name().ifBlank { "Torrent İndirmesi" },
                            progress = (progress * 100).toInt(),
                            speedBytesPerSec = dlSpeed,
                            activeCount = activeHandles.size,
                            isPaused = isPaused
                        )

                        onDownloadProgressChanged?.invoke(id, progress, dlSpeed)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error querying torrent status for id=$id", e)
                    }
                }

                _activeTorrents.value = currentProgressMap
                delay(1000)
            }
        }
    }

    fun pauseTorrent(id: String) {
        pausedTorrentIds.add(id)
        val handle = activeHandles[id]
        handle?.pause()
        scope.launch(Dispatchers.IO) {
            downloadDao.updateStatus(id, DownloadStatus.PAUSED.name)
            onDownloadStateChanged?.invoke(id, DownloadStatus.PAUSED)
        }
    }

    fun resumeTorrent(id: String) {
        pausedTorrentIds.remove(id)
        val handle = activeHandles[id]
        handle?.resume()
        scope.launch(Dispatchers.IO) {
            downloadDao.updateStatus(id, DownloadStatus.DOWNLOADING.name)
            onDownloadStateChanged?.invoke(id, DownloadStatus.DOWNLOADING)
        }
    }

    fun cancelTorrent(id: String, deleteFiles: Boolean = false) {
        pausedTorrentIds.remove(id)
        val handle = activeHandles.remove(id)
        if (handle != null) {
            sessionManager?.remove(handle)
        }
        scope.launch(Dispatchers.IO) {
            downloadDao.updateStatus(id, DownloadStatus.CANCELLED.name)
            if (deleteFiles) {
                downloadDao.getDownloadById(id)?.let { entity ->
                    val file = File(entity.filePath)
                    if (file.exists()) file.deleteRecursively()
                    downloadDao.deleteById(id)
                }
            }
            onDownloadStateChanged?.invoke(id, DownloadStatus.CANCELLED)
        }
    }

    fun getActiveTorrentIds(): List<String> = activeHandles.keys().toList()

    fun isTorrentRunning(id: String): Boolean = activeHandles.containsKey(id)
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

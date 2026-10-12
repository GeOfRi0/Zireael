package com.example.data.engine

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import com.example.data.local.DownloadDao
import com.example.data.local.DownloadEntity
import com.example.data.model.ChromeHeaderConfig
import com.example.data.model.DownloadStatus
import com.example.data.model.FileCategory
import com.example.data.model.SegmentProgress
import com.example.data.model.toHeaderConfig
import com.example.data.service.DownloadForegroundService
import com.example.ui.viewmodel.DownloadViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.regex.Pattern

class RangeNotSupportedException(message: String) : Exception(message)
class FileVerificationException(message: String) : Exception(message)

/**
 * TurboDownloadEngine: High-performance multi-segment download engine.
 *
 * Core architectural guarantees:
 * 1. Safe Single-Stream Fallback: If the server lacks "Accept-Ranges: bytes" or returns
 *    HTTP 200 instead of 206 Partial Content, the engine safely falls back to a clean
 *    single-stream download without file corruption.
 * 2. Robust Chunk Assembly & Verification: Strict multi-step validation ensuring zero
 *    missing bytes, contiguous byte boundaries, and exact file size matching before
 *    and after zero-copy merging.
 * 3. Exponential Backoff Retry: Resilient automatic reconnection against transient
 *    network drops and socket timeouts.
 * 4. Token Bucket Bandwidth Throttle: Coordinated global rate limiting across all threads.
 */
class TurboDownloadEngine(
    private val context: Context,
    private val downloadDao: DownloadDao,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val okHttpClient = OkHttpProvider.client

    // Active download coroutine jobs
    private val activeJobs = ConcurrentHashMap<String, Job>()

    // Live segment progress map: downloadId -> List<SegmentProgress>
    private val _segmentProgressMap = MutableStateFlow<Map<String, List<SegmentProgress>>>(emptyMap())
    val segmentProgressMap: StateFlow<Map<String, List<SegmentProgress>>> = _segmentProgressMap.asStateFlow()

    // Live speed map: downloadId -> speedBytesPerSec
    private val _speedMap = MutableStateFlow<Map<String, Long>>(emptyMap())
    val speedMap: StateFlow<Map<String, Long>> = _speedMap.asStateFlow()

    // Global speed limit in bytes/sec (0 = Unlimited)
    private val _maxSpeedLimitBytesPerSec = MutableStateFlow(0L)
    val maxSpeedLimitBytesPerSec: StateFlow<Long> = _maxSpeedLimitBytesPerSec.asStateFlow()

    fun setMaxSpeedLimit(bytesPerSec: Long) {
        _maxSpeedLimitBytesPerSec.value = bytesPerSec.coerceAtLeast(0L)
    }

    // Live in-memory progress state for active downloads: downloadId -> LiveProgress
    data class LiveProgress(val downloadedBytes: Long, val totalBytes: Long, val speed: Long)
    private val liveProgressMap = ConcurrentHashMap<String, LiveProgress>()
    private val lastSavedPercentMap = ConcurrentHashMap<String, Int>()

    private val notificationHelper = SystemDownloadNotificationHelper(context)

    /**
     * Coordinated Token Bucket rate limiter across all concurrent streams and active downloads.
     */
    inner class TokenBucketRateLimiter {
        private var availableTokens: Long = 0L
        private var lastRefillNanos: Long = System.nanoTime()

        suspend fun acquire(bytes: Int) {
            val limit = _maxSpeedLimitBytesPerSec.value
            if (limit <= 0L || bytes <= 0) return

            var sleepMillis = 0L
            synchronized(this) {
                val now = System.nanoTime()
                val elapsedNanos = (now - lastRefillNanos).coerceAtLeast(0L)
                lastRefillNanos = now

                val refilled = (elapsedNanos.toDouble() / 1_000_000_000.0 * limit).toLong()
                availableTokens = (availableTokens + refilled).coerceAtMost(limit)

                availableTokens -= bytes
                if (availableTokens < 0) {
                    val deficit = -availableTokens
                    val waitNanos = (deficit.toDouble() / limit * 1_000_000_000.0).toLong()
                    sleepMillis = (waitNanos / 1_000_000L).coerceAtLeast(1L)
                }
            }

            if (sleepMillis > 0) {
                delay(sleepMillis)
            }
        }
    }

    val rateLimiter = TokenBucketRateLimiter()

    suspend fun applySpeedThrottling(bytesProcessed: Int, durationNanos: Long = 0L) {
        rateLimiter.acquire(bytesProcessed)
    }

    companion object {
        const val THROTTLE_INTERVAL_MS = 500L
        const val BUFFER_SIZE = 8 * 1024 // 8 KB constant buffer for OOM prevention
        const val MIN_SPLIT_SIZE = 2 * 1024 * 1024L // 2 MB minimum remaining bytes to split for helper
        const val MAX_TOTAL_CHUNKS = 16 // Up to 16 dynamic helper channels
        const val MAX_RETRIES = 5
        const val INITIAL_BACKOFF_MS = 1000L
        const val MAX_BACKOFF_MS = 16000L
        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }

    private data class PersistedSubChunk(
        val subIndex: Int,
        val originalSegmentIndex: Int,
        val startByte: Long,
        val endByte: Long,
        val downloadedBytes: Long,
        val isHelper: Boolean
    )

    private fun getTurboStateDir(): File {
        return File(context.filesDir, "turbo_states").apply {
            if (!exists()) mkdirs()
        }
    }

    private fun getChunkFile(downloadId: String, subIndex: Int): File {
        return File(context.cacheDir, "part_${downloadId}_$subIndex.tmp")
    }

    private fun saveSegmentState(
        downloadId: String,
        url: String,
        totalBytes: Long,
        etag: String?,
        lastModified: String?,
        subChunks: List<SubChunk>,
        chunkDownloadedMap: Map<Int, AtomicLong>
    ) {
        try {
            val stateDir = getTurboStateDir()
            val stateFile = File(stateDir, "$downloadId.state")
            val json = JSONObject().apply {
                put("downloadId", downloadId)
                put("url", url)
                put("totalBytes", totalBytes)
                put("etag", etag.orEmpty())
                put("lastModified", lastModified.orEmpty())
                val arr = JSONArray()
                for (sc in subChunks) {
                    val downloaded = chunkDownloadedMap[sc.subIndex]?.get() ?: 0L
                    val obj = JSONObject().apply {
                        put("subIndex", sc.subIndex)
                        put("originalSegmentIndex", sc.originalSegmentIndex)
                        put("startByte", sc.startByte)
                        put("endByte", sc.endByte.get())
                        put("downloadedBytes", downloaded)
                        put("isHelper", sc.isHelper)
                    }
                    arr.put(obj)
                }
                put("segments", arr)
            }
            val tempFile = File(stateDir, "$downloadId.state.tmp")
            tempFile.writeText(json.toString(), Charsets.UTF_8)
            tempFile.renameTo(stateFile)
        } catch (ignored: Exception) {}
    }

    private fun loadSegmentState(
        downloadId: String,
        url: String,
        totalBytes: Long,
        currentEtag: String?
    ): List<PersistedSubChunk>? {
        try {
            val stateFile = File(getTurboStateDir(), "$downloadId.state")
            if (!stateFile.exists()) return null
            val content = stateFile.readText(Charsets.UTF_8)
            val json = JSONObject(content)
            if (json.optLong("totalBytes", -1L) != totalBytes) return null
            if (json.optString("url") != url) return null

            val savedEtag = json.optString("etag")
            if (savedEtag.isNotBlank() && !currentEtag.isNullOrBlank() && savedEtag != currentEtag) {
                stateFile.delete()
                return null
            }

            val arr = json.optJSONArray("segments") ?: return null
            val result = mutableListOf<PersistedSubChunk>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                result.add(
                    PersistedSubChunk(
                        subIndex = obj.getInt("subIndex"),
                        originalSegmentIndex = obj.getInt("originalSegmentIndex"),
                        startByte = obj.getLong("startByte"),
                        endByte = obj.getLong("endByte"),
                        downloadedBytes = obj.getLong("downloadedBytes"),
                        isHelper = obj.getBoolean("isHelper")
                    )
                )
            }
            val sumLength = result.sumOf { (it.endByte - it.startByte + 1).coerceAtLeast(0L) }
            if (sumLength != totalBytes) return null
            return result
        } catch (e: Exception) {
            return null
        }
    }

    private fun clearSegmentState(downloadId: String) {
        try {
            val stateFile = File(getTurboStateDir(), "$downloadId.state")
            if (stateFile.exists()) stateFile.delete()
        } catch (ignored: Exception) {}
    }

    private fun normalizeUrl(url: String): String {
        var trimmed = url.trim()
        if (trimmed.isBlank()) return ""
        if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) {
            trimmed = "https://$trimmed"
        }
        return trimmed
    }

    var onDownloadStateChanged: ((id: String, status: DownloadStatus) -> Unit)? = null

    fun getActiveDownloadIds(): Set<String> = activeJobs.keys.toSet()

    suspend fun createDownloadEntity(
        url: String,
        customFileName: String? = null,
        threadCount: Int = 4,
        headerConfig: ChromeHeaderConfig? = null
    ): String = withContext(Dispatchers.IO) {
        val cleanUrl = normalizeUrl(url)
        val downloadId = UUID.randomUUID().toString()
        val guessedName = customFileName ?: extractFileNameFromUrl(cleanUrl)
        val initialCategory = FileCategory.detect(guessedName, "")

        val effectiveCookies = if (!headerConfig?.customCookies.isNullOrBlank()) {
            headerConfig?.customCookies
        } else {
            try {
                android.webkit.CookieManager.getInstance().getCookie(cleanUrl)
            } catch (e: Exception) {
                null
            }
        }

        val effectiveUserAgent = headerConfig?.effectiveUserAgent ?: BROWSER_USER_AGENT
        val effectiveReferer = headerConfig?.customReferer

        val entity = DownloadEntity(
            id = downloadId,
            url = cleanUrl,
            fileName = guessedName,
            filePath = "",
            mimeType = "application/octet-stream",
            category = initialCategory.name,
            totalBytes = 0L,
            downloadedBytes = 0L,
            status = DownloadStatus.QUEUED.name,
            speedBytesPerSec = 0L,
            threadCount = threadCount.coerceIn(1, 8),
            createdAt = System.currentTimeMillis(),
            userAgent = effectiveUserAgent,
            referer = effectiveReferer,
            cookies = effectiveCookies,
            extraHeadersJson = headerConfig?.toExtraHeadersJson()
        )
        downloadDao.insert(entity)
        return@withContext downloadId
    }

    suspend fun enqueueDownload(
        url: String,
        customFileName: String? = null,
        threadCount: Int = 4,
        headerConfig: ChromeHeaderConfig? = null
    ): String = withContext(Dispatchers.IO) {
        return@withContext createDownloadEntity(url, customFileName, threadCount, headerConfig)
    }

    fun startDownload(downloadId: String) {
        if (activeJobs.containsKey(downloadId)) return

        val job = scope.launch(Dispatchers.IO) {
            val entity = downloadDao.getDownloadById(downloadId) ?: return@launch
            try {
                executeDownload(entity)
            } catch (e: CancellationException) {
                downloadDao.updateStatus(downloadId, DownloadStatus.PAUSED.name)
                onDownloadStateChanged?.invoke(downloadId, DownloadStatus.PAUSED)
            } catch (e: Exception) {
                e.printStackTrace()
                val errorMsg = e.localizedMessage ?: "İndirme sırasında bir hata oluştu"
                val updated = entity.copy(
                    status = DownloadStatus.FAILED.name,
                    errorMessage = errorMsg,
                    speedBytesPerSec = 0L
                )
                downloadDao.update(updated)
                notificationHelper.notifyFailed(updated, errorMsg)
                onDownloadStateChanged?.invoke(downloadId, DownloadStatus.FAILED)
            } finally {
                activeJobs.remove(downloadId)
                updateSpeed(downloadId, 0L)
                if (activeJobs.isEmpty()) {
                    DownloadForegroundService.stop(context)
                }
            }
        }
        activeJobs[downloadId] = job
    }

    suspend fun flushAllActiveDownloadsToDatabase() = withContext(Dispatchers.IO) {
        liveProgressMap.forEach { (id, progress) ->
            downloadDao.updateProgress(
                id = id,
                downloaded = progress.downloadedBytes,
                total = progress.totalBytes,
                speed = progress.speed,
                status = DownloadStatus.DOWNLOADING.name
            )
        }
    }

    fun pauseDownload(downloadId: String) {
        val job = activeJobs.remove(downloadId)
        job?.cancel()
        scope.launch(Dispatchers.IO) {
            val progress = liveProgressMap.remove(downloadId)
            lastSavedPercentMap.remove(downloadId)
            if (progress != null) {
                downloadDao.updateProgress(
                    id = downloadId,
                    downloaded = progress.downloadedBytes,
                    total = progress.totalBytes,
                    speed = 0L,
                    status = DownloadStatus.PAUSED.name
                )
            } else {
                downloadDao.updateStatus(downloadId, DownloadStatus.PAUSED.name)
            }
            updateSpeed(downloadId, 0L)
            notificationHelper.cancelNotification(downloadId)
            if (activeJobs.isEmpty()) {
                DownloadForegroundService.stop(context)
            }
            onDownloadStateChanged?.invoke(downloadId, DownloadStatus.PAUSED)
        }
    }

    fun resumeDownload(downloadId: String) {
        startDownload(downloadId)
    }

    fun cancelDownload(downloadId: String) {
        val job = activeJobs.remove(downloadId)
        job?.cancel()
        scope.launch(Dispatchers.IO) {
            val entity = downloadDao.getDownloadById(downloadId)
            entity?.let {
                cleanupTempFiles(downloadId, it.threadCount)
                clearSegmentState(downloadId)
                if (it.filePath.isNotBlank()) {
                    val file = File(it.filePath)
                    if (file.exists() && it.status != DownloadStatus.COMPLETED.name) {
                        file.delete()
                    }
                }
                downloadDao.delete(it)
            }
            updateSpeed(downloadId, 0L)
            removeSegmentProgress(downloadId)
            notificationHelper.cancelNotification(downloadId)
            if (activeJobs.isEmpty()) {
                DownloadForegroundService.stop(context)
            }
            onDownloadStateChanged?.invoke(downloadId, DownloadStatus.CANCELLED)
        }
    }

    private suspend fun executeDownload(initialEntity: DownloadEntity): Unit = withContext(Dispatchers.IO) {
        downloadDao.updateStatus(initialEntity.id, DownloadStatus.CONNECTING.name)
        DownloadForegroundService.startOrUpdate(
            context = context,
            downloadId = initialEntity.id,
            fileName = initialEntity.fileName,
            progress = 0,
            speedBytesPerSec = 0L,
            etaString = null,
            activeCount = activeJobs.size.coerceAtLeast(1)
        )

        val headerConfig = initialEntity.toHeaderConfig()

        // 1. Probe the server with HEAD/GET with exponential backoff retry for transient network drops
        var probeResponse: okhttp3.Response? = null
        var probeRetries = 3
        var probeBackoff = INITIAL_BACKOFF_MS

        while (probeRetries > 0 && isActive) {
            try {
                val headRequestBuilder = Request.Builder().url(initialEntity.url).head()
                headerConfig.applyTo(headRequestBuilder, initialEntity.url)
                val request = headRequestBuilder.build()

                var resp = try {
                    okHttpClient.newCall(request).execute()
                } catch (e: Exception) {
                    null
                }

                // If HEAD was rejected or returned 405 Method Not Allowed, fallback to GET range 0-0
                if (resp == null || !resp.isSuccessful) {
                    resp?.close()
                    val getTestBuilder = Request.Builder()
                        .url(initialEntity.url)
                        .header("Range", "bytes=0-0")
                        .get()
                    headerConfig.applyTo(getTestBuilder, initialEntity.url)
                    val getTestRequest = getTestBuilder.build()
                    resp = okHttpClient.newCall(getTestRequest).execute()
                }

                probeResponse = resp
                break
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                probeRetries--
                if (probeRetries <= 0) break
                delay(probeBackoff)
                probeBackoff *= 2
            }
        }

        val contentLengthHeader = probeResponse?.header("Content-Length")
        val contentRangeHeader = probeResponse?.header("Content-Range")
        val acceptRangesHeader = probeResponse?.header("Accept-Ranges")
        val disposition = probeResponse?.header("Content-Disposition").orEmpty()
        val contentType = probeResponse?.header("Content-Type").orEmpty()
        val etag = probeResponse?.header("ETag")?.trim('"', 'W', '/', ' ')
        val lastModified = probeResponse?.header("Last-Modified")
        probeResponse?.close()

        // If server returns an HTML web page: automatically sniff for direct media links
        if (contentType.contains("text/html", ignoreCase = true) && disposition.isBlank()) {
            val sniffer = MediaSniffer(okHttpClient)
            val sniffed = sniffer.sniff(initialEntity.url, headerConfig)
            val directMedia = sniffed.firstOrNull {
                it.url != initialEntity.url && (it.category == FileCategory.MEDIA || it.category == FileCategory.APKS || it.category == FileCategory.ARCHIVES)
            } ?: sniffed.firstOrNull { it.url != initialEntity.url }

            if (directMedia != null) {
                val mediaHeaders = directMedia.headerConfig
                executeDownload(
                    initialEntity.copy(
                        url = directMedia.url,
                        fileName = directMedia.title,
                        mimeType = directMedia.mimeType,
                        category = directMedia.category.name,
                        userAgent = mediaHeaders?.effectiveUserAgent ?: initialEntity.userAgent,
                        referer = mediaHeaders?.customReferer ?: initialEntity.referer ?: initialEntity.url,
                        cookies = mediaHeaders?.customCookies ?: initialEntity.cookies,
                        extraHeadersJson = mediaHeaders?.toExtraHeadersJson() ?: initialEntity.extraHeadersJson
                    )
                )
                return@withContext
            } else {
                throw Exception("Bu bağlantı doğrudan bir dosya değil, bir web sayfasıdır. Lütfen 'Medyaları Tara' butonunu kullanarak sayfadaki video ve medyaları seçin.")
            }
        }

        var totalBytes = contentLengthHeader?.toLongOrNull() ?: 0L
        if (totalBytes <= 1L && contentRangeHeader != null) {
            val totalFromRange = contentRangeHeader.substringAfterLast('/', "").toLongOrNull()
            if (totalFromRange != null) totalBytes = totalFromRange
        }

        val resolvedFileName = resolveFileName(initialEntity.url, disposition, contentType, initialEntity.fileName)
        val category = FileCategory.detect(resolvedFileName, contentType)

        val targetDir = getDownloadsDirectory()
        if (!targetDir.exists()) targetDir.mkdirs()

        // Reuse existing targetFile path on resume; generate non-colliding name for fresh downloads
        val targetFile = if (initialEntity.filePath.isNotBlank()) {
            File(initialEntity.filePath)
        } else {
            var file = File(targetDir, resolvedFileName)
            if (file.exists() && file.length() > 0) {
                val nameWithoutExt = file.nameWithoutExtension
                val ext = file.extension.let { if (it.isNotBlank()) ".$it" else "" }
                var counter = 1
                while (file.exists()) {
                    file = File(targetDir, "${nameWithoutExt}_$counter$ext")
                    counter++
                }
            }
            file
        }

        // Storage Check & Pre-allocation: account for already allocated space on disk
        if (totalBytes > 0) {
            val usableSpace = targetDir.usableSpace
            val existingLength = if (targetFile.exists()) targetFile.length() else 0L
            val spaceNeeded = (totalBytes - existingLength).coerceAtLeast(0L)
            if (usableSpace < spaceNeeded) {
                val neededFormatted = formatBytes(spaceNeeded)
                val availableFormatted = formatBytes(usableSpace)
                throw Exception("Yetersiz depolama alanı! Gerekli: $neededFormatted, Kullanılabilir: $availableFormatted")
            }
        }

        val updatedEntity = initialEntity.copy(
            fileName = resolvedFileName,
            filePath = targetFile.absolutePath,
            mimeType = contentType.ifBlank { "application/octet-stream" },
            category = category.name,
            totalBytes = totalBytes,
            status = DownloadStatus.DOWNLOADING.name
        )
        downloadDao.update(updatedEntity)

        // Strict Range verification: must have explicit "bytes" support and non-trivial file size
        val supportsRange = (acceptRangesHeader?.contains("bytes", ignoreCase = true) == true ||
            contentRangeHeader?.contains("bytes", ignoreCase = true) == true) && totalBytes > 1024 * 512

        val isYouTube = updatedEntity.url.contains("googlevideo.com") ||
            updatedEntity.url.contains("youtube.com") ||
            updatedEntity.url.contains("videoplayback")

        var activeDownloadEntity = updatedEntity
        if (isYouTube) {
            var cleanUrl = activeDownloadEntity.url
                .replace(Regex("""&range=\d+-\d+"""), "")
                .replace(Regex("""&rn=\d+"""), "")
            if (cleanUrl.contains("googlevideo.com") && !cleanUrl.contains("ratebypass=yes")) {
                val separator = if (cleanUrl.contains("?")) "&" else "?"
                cleanUrl = "${cleanUrl}${separator}ratebypass=yes"
            }
            activeDownloadEntity = activeDownloadEntity.copy(url = cleanUrl)
        }

        val effectiveThreads = if (supportsRange && !isYouTube) activeDownloadEntity.threadCount.coerceAtLeast(1) else 1

        if (supportsRange && effectiveThreads > 1) {
            try {
                downloadParallelSegments(activeDownloadEntity, targetFile, totalBytes, effectiveThreads, etag, lastModified)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Clean fallback to single stream: clean up temp chunks, truncate targetFile to 0
                cleanupTempFiles(activeDownloadEntity.id, effectiveThreads)
                clearSegmentState(activeDownloadEntity.id)
                if (targetFile.exists()) {
                    try {
                        RandomAccessFile(targetFile, "rw").use { it.setLength(0L) }
                    } catch (ignored: Exception) {}
                }
                downloadSingleStream(activeDownloadEntity, targetFile, totalBytes)
            }
        } else {
            // Direct single stream fallback when server does not support Accept-Ranges: bytes
            downloadSingleStream(activeDownloadEntity, targetFile, totalBytes)
        }

        // Final verification & magic byte inspection to guarantee real file extension (never .bin)
        if (targetFile.exists() && targetFile.length() > 0) {
            val finalBytes = targetFile.length()
            if (totalBytes > 0 && finalBytes < totalBytes) {
                throw Exception("İndirme eksik kaldı ($finalBytes / $totalBytes bayt). Bağlantı kesildi.")
            }
            var finalFile = targetFile
            var finalFileName = updatedEntity.fileName
            var finalMimeType = updatedEntity.mimeType
            var finalCategory = updatedEntity.category

            val detected = FileSignatureDetector.detect(targetFile)
            if (detected != null) {
                if (detected.isHtmlOrWebPage) {
                    val isSocial = initialEntity.url.contains("instagram.com") ||
                        initialEntity.url.contains("youtube.com") ||
                        initialEntity.url.contains("tiktok.com") ||
                        initialEntity.url.contains("twitter.com") ||
                        initialEntity.url.contains("x.com")
                    val msg = if (isSocial) {
                        "İndirilen içerik bir video değil, web sayfası/oturum engelidir. Bu platform oturum veya 'Medyaları Tara' seçeneğini gerektirir."
                    } else {
                        "Bağlantı doğrudan bir dosya yerine web sayfası döndürdü. Lütfen sayfanın içindeki gerçek dosya bağlantısını kullanın."
                    }
                    throw Exception(msg)
                }

                val currentExt = targetFile.name.substringAfterLast('.', "").lowercase()
                val isGeneric = currentExt == "bin" || currentExt.isBlank() || currentExt == "tmp" ||
                    currentExt == "download" || currentExt == "part" || currentExt == "php" || currentExt == "html"
                val detectedExtClean = detected.extension.removePrefix(".").lowercase()

                if (isGeneric || currentExt != detectedExtClean) {
                    val base = targetFile.nameWithoutExtension.ifBlank { "download_${System.currentTimeMillis()}" }
                    var candidateFile = File(targetFile.parentFile, "$base${detected.extension}")
                    var counter = 1
                    while (candidateFile.exists() && candidateFile.absolutePath != targetFile.absolutePath) {
                        candidateFile = File(targetFile.parentFile, "${base}_$counter${detected.extension}")
                        counter++
                    }
                    if (targetFile.renameTo(candidateFile)) {
                        finalFile = candidateFile
                        finalFileName = candidateFile.name
                        finalMimeType = detected.mimeType
                        finalCategory = detected.category
                    }
                } else if (finalCategory != detected.category) {
                    finalCategory = detected.category
                    finalMimeType = detected.mimeType
                }
            } else {
                val currentExt = targetFile.name.substringAfterLast('.', "").lowercase()
                if (currentExt == "bin" || currentExt == "tmp" || currentExt.isBlank()) {
                    val fallbackExt = when {
                        finalMimeType.contains("video") || finalMimeType.contains("mp4") -> ".mp4"
                        finalMimeType.contains("audio") || finalMimeType.contains("mpeg") -> ".mp3"
                        finalMimeType.contains("image") -> ".jpg"
                        finalMimeType.contains("pdf") -> ".pdf"
                        finalMimeType.contains("zip") -> ".zip"
                        else -> ""
                    }
                    if (fallbackExt.isNotBlank()) {
                        val base = targetFile.nameWithoutExtension.ifBlank { "download_${System.currentTimeMillis()}" }
                        var candidateFile = File(targetFile.parentFile, "$base$fallbackExt")
                        var counter = 1
                        while (candidateFile.exists() && candidateFile.absolutePath != targetFile.absolutePath) {
                            candidateFile = File(targetFile.parentFile, "${base}_$counter$fallbackExt")
                            counter++
                        }
                        if (targetFile.renameTo(candidateFile)) {
                            finalFile = candidateFile
                            finalFileName = candidateFile.name
                        }
                    }
                }
            }

            val actualFinalBytes = finalFile.length()
            val completedEntity = updatedEntity.copy(
                fileName = finalFileName,
                filePath = finalFile.absolutePath,
                mimeType = finalMimeType,
                category = finalCategory,
                downloadedBytes = actualFinalBytes,
                totalBytes = if (totalBytes <= 0) actualFinalBytes else totalBytes,
                status = DownloadStatus.COMPLETED.name,
                speedBytesPerSec = 0L,
                completedAt = System.currentTimeMillis()
            )
            downloadDao.update(completedEntity)

            // Trigger MediaScanner & MediaStore registration so Downloads, Gallery and Media Players see it
            DownloadStorageManager.registerWithMediaStore(
                context = context,
                file = finalFile,
                mimeType = finalMimeType,
                totalBytes = completedEntity.totalBytes
            )
            liveProgressMap.remove(completedEntity.id)
            lastSavedPercentMap.remove(completedEntity.id)
            clearSegmentState(completedEntity.id)
            cleanupSubChunkFiles(completedEntity.id)
            notificationHelper.cancelNotification(updatedEntity.id)
            notificationHelper.notifyCompleted(completedEntity)
            onDownloadStateChanged?.invoke(completedEntity.id, DownloadStatus.COMPLETED)
        } else {
            throw Exception("İndirilen dosya kaydedilemedi veya boş.")
        }
    }

    private data class SubChunk(
        val subIndex: Int,
        val originalSegmentIndex: Int,
        val startByte: Long,
        val endByte: AtomicLong,
        val isHelper: Boolean = false
    ) {
        val totalBytes: Long get() = (endByte.get() - startByte + 1).coerceAtLeast(0L)
    }

    /**
     * Verifies all chunk files, checks missing/corrupt bytes, ensures continuity,
     * and performs zero-copy transfer merge into targetFile with fsync.
     */
    private suspend fun mergeAndVerifyChunks(
        downloadId: String,
        subChunks: List<SubChunk>,
        targetFile: File,
        expectedTotalBytes: Long
    ) = withContext(Dispatchers.IO) {
        val sortedChunks = subChunks.sortedBy { it.startByte }
        var expectedNextByte = 0L

        // 1. Pre-merge validation: existence, size match, and contiguous boundary check
        for (sc in sortedChunks) {
            val chunkFile = getChunkFile(downloadId, sc.subIndex)
            if (!chunkFile.exists()) {
                throw FileVerificationException("Eksik parça: #${sc.subIndex} dosyası bulunamadı (${chunkFile.name})")
            }

            val actualLength = chunkFile.length()
            val expectedLength = sc.totalBytes

            if (actualLength < expectedLength) {
                throw FileVerificationException(
                    "Parça #${sc.subIndex} eksik byte içeriyor ($actualLength / $expectedLength bayt)."
                )
            }
            if (actualLength > expectedLength) {
                throw FileVerificationException(
                    "Parça #${sc.subIndex} fazla byte içeriyor (bozuk veri: $actualLength / $expectedLength bayt)."
                )
            }

            if (sc.startByte != expectedNextByte) {
                throw FileVerificationException(
                    "Parça #${sc.subIndex} sınırında süreksizlik/boşluk tespit edildi (Beklenen: $expectedNextByte, Gerçek: ${sc.startByte})"
                )
            }
            expectedNextByte = sc.endByte.get() + 1
        }

        // Sum of all chunk files must match expected total bytes
        val totalChunkBytes = sortedChunks.sumOf { getChunkFile(downloadId, it.subIndex).length() }
        if (expectedTotalBytes > 0 && totalChunkBytes != expectedTotalBytes) {
            throw FileVerificationException(
                "Toplam parça boyutu sunucu boyutuyla uyuşmuyor: $totalChunkBytes / $expectedTotalBytes bayt."
            )
        }

        // 2. Perform zero-copy transfer merge into targetFile
        RandomAccessFile(targetFile, "rw").use { targetRaf ->
            targetRaf.setLength(0L) // Ensure target file starts completely clean
            val targetChannel = targetRaf.channel
            var writePosition = 0L

            for (sc in sortedChunks) {
                val chunkFile = getChunkFile(downloadId, sc.subIndex)
                RandomAccessFile(chunkFile, "r").use { chunkRaf ->
                    val chunkChannel = chunkRaf.channel
                    val chunkSize = chunkChannel.size()
                    var transferred = 0L
                    while (transferred < chunkSize) {
                        val count = targetChannel.transferFrom(chunkChannel, writePosition + transferred, chunkSize - transferred)
                        if (count <= 0) break
                        transferred += count
                    }
                    writePosition += transferred
                }
            }

            // Force flush OS and disk controller page caches directly to physical storage
            targetChannel.force(true)
        }

        // 3. Post-merge final size validation
        val finalFileLength = targetFile.length()
        if (expectedTotalBytes > 0 && finalFileLength != expectedTotalBytes) {
            throw FileVerificationException(
                "Birleştirme sonrası dosya boyutu doğrulanamadı: $finalFileLength / $expectedTotalBytes bayt."
            )
        }

        // 4. Clean up temporary chunk files only after full validation success
        cleanupSubChunkFiles(downloadId)
    }

    private suspend fun downloadParallelSegments(
        entity: DownloadEntity,
        targetFile: File,
        totalBytes: Long,
        threadCount: Int,
        etag: String? = null,
        lastModified: String? = null
    ) = withContext(Dispatchers.IO) {
        val headerConfig = entity.toHeaderConfig()

        val savedState = loadSegmentState(entity.id, entity.url, totalBytes, etag)
        val subChunks = CopyOnWriteArrayList<SubChunk>()
        val chunkDownloadedMap = ConcurrentHashMap<Int, AtomicLong>()
        val nextSubIndex = AtomicInteger(threadCount)

        if (!savedState.isNullOrEmpty()) {
            var maxIdx = threadCount - 1
            for (item in savedState) {
                subChunks.add(
                    SubChunk(
                        subIndex = item.subIndex,
                        originalSegmentIndex = item.originalSegmentIndex,
                        startByte = item.startByte,
                        endByte = AtomicLong(item.endByte),
                        isHelper = item.isHelper
                    )
                )
                val chunkFile = getChunkFile(entity.id, item.subIndex)
                val fileLen = if (chunkFile.exists()) chunkFile.length() else 0L
                val safeDownloaded = maxOf(item.downloadedBytes, fileLen).coerceIn(0L, item.endByte - item.startByte + 1)
                chunkDownloadedMap[item.subIndex] = AtomicLong(safeDownloaded)
                if (item.subIndex > maxIdx) maxIdx = item.subIndex
            }
            nextSubIndex.set(maxIdx + 1)
        } else {
            val partSize = totalBytes / threadCount
            for (i in 0 until threadCount) {
                val start = i * partSize
                val end = if (i == threadCount - 1) totalBytes - 1 else (i + 1) * partSize - 1
                val sc = SubChunk(
                    subIndex = i,
                    originalSegmentIndex = i,
                    startByte = start,
                    endByte = AtomicLong(end),
                    isHelper = false
                )
                subChunks.add(sc)
                val chunkFile = getChunkFile(entity.id, i)
                val fileLen = if (chunkFile.exists()) chunkFile.length().coerceAtMost(sc.totalBytes) else 0L
                chunkDownloadedMap[i] = AtomicLong(fileLen)
            }
        }

        val initialTotalDownloaded = chunkDownloadedMap.values.sumOf { it.get() }
        val downloadedBytesAtomic = AtomicLong(initialTotalDownloaded)

        // Rate-limited segment UI progress updater (at most once every 500ms)
        val lastProgressUiUpdate = AtomicLong(0L)
        val refreshProgressUI: (Boolean) -> Unit = { force ->
            val now = System.currentTimeMillis()
            if (force || now - lastProgressUiUpdate.get() >= THROTTLE_INTERVAL_MS) {
                lastProgressUiUpdate.set(now)
                val progressList = subChunks.map { sc ->
                    val dl = chunkDownloadedMap[sc.subIndex]?.get() ?: 0L
                    SegmentProgress(
                        segmentIndex = sc.subIndex,
                        startByte = sc.startByte,
                        endByte = sc.endByte.get(),
                        downloadedBytes = dl,
                        totalBytes = sc.totalBytes,
                        isHelper = sc.isHelper,
                        helperTargetSegmentIndex = if (sc.isHelper) sc.originalSegmentIndex else null
                    )
                }
                updateSegmentProgress(entity.id, progressList)
            }
        }
        refreshProgressUI(true)

        // Speed and progress tracker background job (throttled to 500ms)
        val speedTrackerJob = scope.launch(Dispatchers.IO) {
            var lastDownloaded = downloadedBytesAtomic.get()
            var lastTime = System.currentTimeMillis()

            while (isActive) {
                delay(THROTTLE_INTERVAL_MS)
                val currentTime = System.currentTimeMillis()
                val currentDownloaded = downloadedBytesAtomic.get()
                val timeDiff = (currentTime - lastTime).coerceAtLeast(1)
                val bytesDiff = (currentDownloaded - lastDownloaded).coerceAtLeast(0)

                val speed = (bytesDiff * 1000L) / timeDiff
                updateSpeed(entity.id, speed)
                refreshProgressUI(true)

                liveProgressMap[entity.id] = LiveProgress(currentDownloaded, totalBytes, speed)
                val percent = if (totalBytes > 0) ((currentDownloaded * 100) / totalBytes).toInt().coerceIn(0, 100) else 0

                val lastSavedPercent = lastSavedPercentMap[entity.id] ?: -1
                if (lastSavedPercent == -1 || percent >= lastSavedPercent + 5 || (percent == 100 && lastSavedPercent != 100)) {
                    lastSavedPercentMap[entity.id] = percent
                    downloadDao.updateProgress(
                        entity.id,
                        currentDownloaded,
                        totalBytes,
                        speed,
                        DownloadStatus.DOWNLOADING.name
                    )
                    saveSegmentState(entity.id, entity.url, totalBytes, etag, lastModified, subChunks, chunkDownloadedMap)
                }
                val eta = DownloadViewModel.calculateEta(currentDownloaded, totalBytes, speed)
                notificationHelper.updateProgressNotification(
                    entity.copy(downloadedBytes = currentDownloaded, totalBytes = totalBytes),
                    speed
                )
                DownloadForegroundService.startOrUpdate(
                    context = context,
                    downloadId = entity.id,
                    fileName = entity.fileName,
                    progress = percent,
                    speedBytesPerSec = speed,
                    etaString = eta,
                    activeCount = activeJobs.size.coerceAtLeast(1)
                )

                lastDownloaded = currentDownloaded
                lastTime = currentTime
            }
        }

        val activeWorkers = ConcurrentHashMap<Int, SubChunk>()
        val completedSubIndexes = ConcurrentHashMap.newKeySet<Int>()

        suspend fun downloadChunkStream(chunk: SubChunk, workerId: Int) {
            val dlAtomic = chunkDownloadedMap.getOrPut(chunk.subIndex) { AtomicLong(0L) }
            val chunkFile = getChunkFile(entity.id, chunk.subIndex)

            var initialBytes = if (chunkFile.exists()) chunkFile.length() else dlAtomic.get()
            dlAtomic.set(initialBytes)

            val totalChunkBytes = chunk.totalBytes
            if (initialBytes >= totalChunkBytes && totalChunkBytes > 0) {
                completedSubIndexes.add(chunk.subIndex)
                return
            }

            var retriesLeft = MAX_RETRIES
            var backoffMs = INITIAL_BACKOFF_MS

            while (retriesLeft > 0 && isActive) {
                initialBytes = if (chunkFile.exists()) chunkFile.length() else dlAtomic.get()
                dlAtomic.set(initialBytes)

                val currentEnd = chunk.endByte.get()
                val requestStart = chunk.startByte + initialBytes
                val requestEnd = currentEnd

                if (requestStart > requestEnd) {
                    completedSubIndexes.add(chunk.subIndex)
                    return
                }

                val segRequestBuilder = Request.Builder()
                    .url(entity.url)
                    .header("Range", "bytes=$requestStart-$requestEnd")
                    .get()
                headerConfig.applyTo(segRequestBuilder, entity.url)
                val segRequest = segRequestBuilder.build()

                var segResponse: okhttp3.Response? = null
                try {
                    segResponse = okHttpClient.newCall(segRequest).execute()

                    // Server refused Partial Content (206)
                    if (segResponse.code == 200) {
                        throw RangeNotSupportedException("Sunucu Range desteğini reddetti (HTTP 200). Tek kanallı akışa geçiliyor.")
                    }

                    if (!segResponse.isSuccessful && segResponse.code != 206) {
                        if (segResponse.code == 416) {
                            if (requestStart >= chunk.startByte + totalChunkBytes) {
                                completedSubIndexes.add(chunk.subIndex)
                                return
                            }
                        }
                        throw Exception("Parça #${chunk.subIndex} isteği başarısız: HTTP ${segResponse.code}")
                    }

                    val inputStream = segResponse.body?.byteStream() ?: throw Exception("Boş akış")
                    val raf = RandomAccessFile(chunkFile, "rw")
                    raf.seek(initialBytes)
                    val channel = raf.channel

                    val buffer = ByteArray(BUFFER_SIZE)
                    val byteBuffer = ByteBuffer.wrap(buffer)
                    var currentWritePosition = initialBytes

                    try {
                        while (isActive) {
                            val currentLimit = chunk.endByte.get() - chunk.startByte
                            val remainingInChunk = currentLimit - currentWritePosition + 1
                            if (remainingInChunk <= 0) break

                            val toRead = minOf(buffer.size.toLong(), remainingInChunk).toInt()
                            val read = inputStream.read(buffer, 0, toRead)
                            if (read == -1) break

                            byteBuffer.position(0)
                            byteBuffer.limit(read)

                            while (byteBuffer.hasRemaining()) {
                                val written = channel.write(byteBuffer, currentWritePosition)
                                currentWritePosition += written
                            }

                            downloadedBytesAtomic.addAndGet(read.toLong())
                            dlAtomic.addAndGet(read.toLong())
                            refreshProgressUI(false)

                            rateLimiter.acquire(read)
                        }
                        channel.force(true)
                    } finally {
                        channel.close()
                        raf.close()
                        inputStream.close()
                    }

                    val finalDl = dlAtomic.get()
                    if (finalDl >= chunk.totalBytes || chunkFile.length() >= chunk.totalBytes) {
                        completedSubIndexes.add(chunk.subIndex)
                        return
                    }

                    // Socket dropped gracefully before completing: retry with backoff
                    retriesLeft--
                    if (retriesLeft > 0) {
                        delay(backoffMs)
                        backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    if (e is RangeNotSupportedException) throw e
                    retriesLeft--
                    if (retriesLeft <= 0) {
                        throw e
                    }
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                } finally {
                    segResponse?.close()
                }
            }
        }

        /**
         * Dynamic Work-Stealing: idle workers split the largest remaining active segment in half
         * and stream a helper chunk into an isolated chunk file.
         */
        suspend fun tryStealWork(workerId: Int): Boolean {
            if (subChunks.size >= MAX_TOTAL_CHUNKS) return false

            var candidate: SubChunk? = null
            var maxRemaining = 0L

            for (sc in subChunks) {
                val currentEnd = sc.endByte.get()
                val dl = chunkDownloadedMap[sc.subIndex]?.get() ?: 0L
                val currentHead = sc.startByte + dl
                val remaining = currentEnd - currentHead + 1

                if (remaining >= 2 * MIN_SPLIT_SIZE && remaining > maxRemaining) {
                    maxRemaining = remaining
                    candidate = sc
                }
            }

            val target = candidate ?: return false

            val dl = chunkDownloadedMap[target.subIndex]?.get() ?: 0L
            val currentHead = target.startByte + dl
            val remaining = target.endByte.get() - currentHead + 1
            if (remaining < 2 * MIN_SPLIT_SIZE) return false

            val halfRemaining = remaining / 2
            val newTargetEnd = currentHead + halfRemaining
            val helperStart = newTargetEnd + 1
            val oldEnd = target.endByte.getAndSet(newTargetEnd)

            if (oldEnd <= helperStart) {
                target.endByte.set(oldEnd)
                return false
            }

            val helperChunk = SubChunk(
                subIndex = nextSubIndex.getAndIncrement(),
                originalSegmentIndex = target.originalSegmentIndex,
                startByte = helperStart,
                endByte = AtomicLong(oldEnd),
                isHelper = true
            )

            subChunks.add(helperChunk)
            chunkDownloadedMap[helperChunk.subIndex] = AtomicLong(0L)
            activeWorkers[workerId] = helperChunk

            refreshProgressUI(true)

            try {
                downloadChunkStream(helperChunk, workerId)
            } finally {
                activeWorkers.remove(workerId)
            }

            return true
        }

        try {
            // Launch worker coroutines (1 per partitioned segment with work-stealing loop)
            val workers = (0 until threadCount).map { workerId ->
                async(Dispatchers.IO) {
                    val initialTask = subChunks.getOrNull(workerId)
                    if (initialTask != null) {
                        activeWorkers[workerId] = initialTask
                        try {
                            downloadChunkStream(initialTask, workerId)
                        } finally {
                            activeWorkers.remove(workerId)
                        }
                    }

                    // Work-stealing loop
                    while (isActive) {
                        val stole = tryStealWork(workerId)
                        if (!stole) break
                    }
                }
            }

            workers.awaitAll()

            // Verify all subChunks are finished
            for (sc in subChunks) {
                val downloaded = chunkDownloadedMap[sc.subIndex]?.get() ?: 0L
                if (downloaded < sc.totalBytes) {
                    downloadChunkStream(sc, 0)
                }
            }

            // Merge and verify chunks into the final targetFile
            mergeAndVerifyChunks(entity.id, subChunks, targetFile, totalBytes)

            clearSegmentState(entity.id)
        } finally {
            speedTrackerJob.cancel()
            saveSegmentState(entity.id, entity.url, totalBytes, etag, lastModified, subChunks, chunkDownloadedMap)
        }
    }

    private suspend fun downloadSingleStream(
        entity: DownloadEntity,
        targetFile: File,
        totalBytes: Long
    ) = withContext(Dispatchers.IO) {
        val existingBytes = if (targetFile.exists()) targetFile.length() else 0L

        val headerConfig = entity.toHeaderConfig()

        val downloadedAtomic = AtomicLong(existingBytes)
        val resolvedTotal = if (totalBytes > 0) totalBytes else -1L

        val updateSingleStreamSegmentUI: () -> Unit = {
            val currentDownloaded = downloadedAtomic.get()
            val total = if (resolvedTotal > 0) resolvedTotal else currentDownloaded
            val segList = listOf(
                SegmentProgress(
                    segmentIndex = 0,
                    startByte = 0L,
                    endByte = (total - 1).coerceAtLeast(0L),
                    downloadedBytes = currentDownloaded,
                    totalBytes = total,
                    speedBytesPerSec = _speedMap.value[entity.id] ?: 0L,
                    isHelper = false,
                    helperTargetSegmentIndex = null
                )
            )
            updateSegmentProgress(entity.id, segList)
        }
        updateSingleStreamSegmentUI()

        val speedTrackerJob = scope.launch(Dispatchers.IO) {
            var lastDownloaded = downloadedAtomic.get()
            var lastTime = System.currentTimeMillis()

            while (isActive) {
                delay(THROTTLE_INTERVAL_MS)
                val currentTime = System.currentTimeMillis()
                val currentDownloaded = downloadedAtomic.get()
                val timeDiff = (currentTime - lastTime).coerceAtLeast(1)
                val bytesDiff = (currentDownloaded - lastDownloaded).coerceAtLeast(0)

                val speed = (bytesDiff * 1000L) / timeDiff
                updateSpeed(entity.id, speed)
                updateSingleStreamSegmentUI()

                liveProgressMap[entity.id] = LiveProgress(currentDownloaded, resolvedTotal, speed)
                val percent = if (resolvedTotal > 0) ((currentDownloaded * 100) / resolvedTotal).toInt().coerceIn(0, 100) else 0

                val lastSavedPercent = lastSavedPercentMap[entity.id] ?: -1
                if (lastSavedPercent == -1 || percent >= lastSavedPercent + 5 || (percent == 100 && lastSavedPercent != 100)) {
                    lastSavedPercentMap[entity.id] = percent
                    downloadDao.updateProgress(
                        entity.id,
                        currentDownloaded,
                        resolvedTotal,
                        speed,
                        DownloadStatus.DOWNLOADING.name
                    )
                }
                val eta = DownloadViewModel.calculateEta(currentDownloaded, resolvedTotal, speed)
                notificationHelper.updateProgressNotification(
                    entity.copy(downloadedBytes = currentDownloaded, totalBytes = resolvedTotal),
                    speed
                )
                DownloadForegroundService.startOrUpdate(
                    context = context,
                    downloadId = entity.id,
                    fileName = entity.fileName,
                    progress = percent,
                    speedBytesPerSec = speed,
                    etaString = eta,
                    activeCount = activeJobs.size.coerceAtLeast(1)
                )

                lastDownloaded = currentDownloaded
                lastTime = currentTime
            }
        }

        var retriesLeft = MAX_RETRIES
        var backoffMs = INITIAL_BACKOFF_MS

        try {
            while (retriesLeft > 0 && isActive) {
                val currentDownloaded = downloadedAtomic.get()
                if (resolvedTotal > 0 && currentDownloaded >= resolvedTotal) break

                val requestBuilder = Request.Builder().url(entity.url)
                headerConfig.applyTo(requestBuilder, entity.url)

                if (currentDownloaded > 0 && (resolvedTotal <= 0 || resolvedTotal > currentDownloaded)) {
                    requestBuilder.header("Range", "bytes=$currentDownloaded-")
                }

                var response: okhttp3.Response? = null
                try {
                    response = okHttpClient.newCall(requestBuilder.get().build()).execute()
                    if (!response.isSuccessful && response.code != 206 && response.code != 200) {
                        throw Exception("Sunucu yanıt vermedi: HTTP ${response.code}")
                    }

                    val append = (response.code == 206 && currentDownloaded > 0)
                    val inputStream: InputStream = response.body?.byteStream() ?: throw Exception("Boş veri akışı")

                    val targetRaf = RandomAccessFile(targetFile, "rw")
                    if (!append && currentDownloaded > 0) {
                        targetRaf.setLength(0L)
                        downloadedAtomic.set(0L)
                    }
                    val channel = targetRaf.channel
                    val startWritePos = if (append) currentDownloaded else 0L

                    val buffer = ByteArray(BUFFER_SIZE)
                    val byteBuffer = ByteBuffer.wrap(buffer)
                    var read: Int
                    var currentWritePosition = startWritePos

                    try {
                        while (isActive) {
                            read = inputStream.read(buffer)
                            if (read == -1) break

                            byteBuffer.position(0)
                            byteBuffer.limit(read)

                            while (byteBuffer.hasRemaining()) {
                                val written = channel.write(byteBuffer, currentWritePosition)
                                currentWritePosition += written
                            }

                            downloadedAtomic.addAndGet(read.toLong())
                            rateLimiter.acquire(read)
                        }
                        channel.force(true)
                    } finally {
                        channel.close()
                        targetRaf.close()
                        inputStream.close()
                    }

                    val finalDownloaded = downloadedAtomic.get()
                    if (resolvedTotal <= 0 || finalDownloaded >= resolvedTotal) {
                        break // Successfully completed stream
                    }

                    // Transient drop mid-stream: decrement retry and backoff
                    retriesLeft--
                    if (retriesLeft > 0) {
                        delay(backoffMs)
                        backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    retriesLeft--
                    if (retriesLeft <= 0) {
                        throw e
                    }
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                } finally {
                    response?.close()
                }
            }

            val finalDownloaded = downloadedAtomic.get()
            if (resolvedTotal > 0 && finalDownloaded < resolvedTotal) {
                throw Exception("İndirme tamamlanamadı: Bağlantı erken kesildi (${formatBytes(finalDownloaded)} / ${formatBytes(resolvedTotal)}).")
            }
        } finally {
            speedTrackerJob.cancel()
        }
    }

    private fun cleanupSubChunkFiles(downloadId: String) {
        val files = context.cacheDir.listFiles { _, name ->
            name.startsWith("part_${downloadId}_") && name.endsWith(".tmp")
        }
        files?.forEach { it.delete() }
    }

    private fun cleanupTempFiles(downloadId: String, threadCount: Int) {
        cleanupSubChunkFiles(downloadId)
    }

    private fun getDownloadsDirectory(): File {
        return DownloadStorageManager.getDownloadDirectory(context)
    }

    private fun updateSpeed(id: String, speed: Long) {
        val current = _speedMap.value.toMutableMap()
        if (speed <= 0) current.remove(id) else current[id] = speed
        _speedMap.value = current
    }

    private fun updateSegmentProgress(id: String, segments: List<SegmentProgress>) {
        val current = _segmentProgressMap.value.toMutableMap()
        current[id] = segments
        _segmentProgressMap.value = current
    }

    private fun removeSegmentProgress(id: String) {
        val current = _segmentProgressMap.value.toMutableMap()
        current.remove(id)
        _segmentProgressMap.value = current
    }

    private fun extractFileNameFromUrl(url: String): String {
        val clean = url.substringBefore('?').substringBefore('#')
        val lastSegment = clean.substringAfterLast('/', "")
        if (lastSegment.isNotBlank() && lastSegment.contains('.')) {
            val decoded = try { URLDecoder.decode(lastSegment, "UTF-8") } catch (e: Exception) { lastSegment }
            val sanitized = sanitizeFileName(decoded)
            if (sanitized.isNotBlank()) return sanitized
        }
        return "file_${System.currentTimeMillis()}"
    }

    private fun extractFileNameFromQueryParams(url: String): String {
        return try {
            val uri = android.net.Uri.parse(url)
            val candidates = listOf("filename", "file", "response-content-disposition", "name", "title", "fn")
            for (param in candidates) {
                val value = uri.getQueryParameter(param)
                if (!value.isNullOrBlank()) {
                    val decoded = URLDecoder.decode(value, "UTF-8")
                    if (decoded.contains("filename=")) {
                        val sub = decoded.substringAfter("filename=").trim('"', '\'').substringBefore(';')
                        if (sub.isNotBlank() && sub.contains('.')) return sub
                    }
                    if (decoded.contains('.')) return decoded
                }
            }
            ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun extractExtensionFromQueryParams(url: String): String {
        return try {
            val uri = android.net.Uri.parse(url)
            val candidates = listOf("filename", "file", "response-content-disposition", "name", "title", "fn", "format", "ext")
            for (param in candidates) {
                val value = uri.getQueryParameter(param)
                if (!value.isNullOrBlank()) {
                    val decoded = URLDecoder.decode(value, "UTF-8")
                    if (decoded.contains('.')) {
                        val ext = ".${decoded.substringAfterLast('.').lowercase()}"
                        if (ext.length in 3..6) return ext
                    }
                }
            }
            ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun detectExtension(mimeType: String, url: String): String {
        val cleanMime = mimeType.substringBefore(';').trim().lowercase()

        // 1. Check URL path for explicit file extension
        val urlPath = url.substringBefore('?').substringBefore('#')
        val lastPathSegment = urlPath.substringAfterLast('/', "")
        if (lastPathSegment.contains('.')) {
            val ext = ".${lastPathSegment.substringAfterLast('.').lowercase()}"
            val knownExtensions = listOf(
                ".mp4", ".mkv", ".webm", ".avi", ".mov", ".flv", ".3gp", ".ts", ".m4v",
                ".mp3", ".wav", ".flac", ".aac", ".ogg", ".m4a", ".opus",
                ".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp", ".svg",
                ".pdf", ".epub", ".docx", ".xlsx", ".pptx", ".txt",
                ".zip", ".rar", ".7z", ".tar", ".gz", ".apk", ".iso"
            )
            if (knownExtensions.contains(ext)) {
                return ext
            }
        }

        // Check query parameters for file extension hints
        val queryExt = extractExtensionFromQueryParams(url)
        if (queryExt.isNotBlank()) {
            return queryExt
        }

        // 2. Android MimeTypeMap lookup
        if (cleanMime.isNotBlank()) {
            val mapExt = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(cleanMime)
            if (!mapExt.isNullOrBlank() && mapExt != "bin") {
                return ".$mapExt"
            }
        }

        // 3. Rich dictionary matching by MIME type
        val lowerMime = cleanMime.lowercase()
        val dictExt = when {
            lowerMime.contains("mp4") || lowerMime.contains("m4v") -> ".mp4"
            lowerMime.contains("webm") -> ".webm"
            lowerMime.contains("matroska") || lowerMime.contains("mkv") -> ".mkv"
            lowerMime.contains("quicktime") || lowerMime.contains("mov") -> ".mov"
            lowerMime.contains("msvideo") || lowerMime.contains("avi") -> ".avi"
            lowerMime.contains("3gpp") || lowerMime.contains("3gp") -> ".3gp"
            lowerMime.contains("flv") -> ".flv"
            lowerMime.contains("audio/mpeg") || lowerMime.contains("mp3") -> ".mp3"
            lowerMime.contains("m4a") || lowerMime.contains("audio/mp4") -> ".m4a"
            lowerMime.contains("wav") -> ".wav"
            lowerMime.contains("flac") -> ".flac"
            lowerMime.contains("ogg") || lowerMime.contains("opus") -> ".ogg"
            lowerMime.contains("aac") -> ".aac"
            lowerMime.contains("jpeg") || lowerMime.contains("jpg") -> ".jpg"
            lowerMime.contains("png") -> ".png"
            lowerMime.contains("webp") -> ".webp"
            lowerMime.contains("gif") -> ".gif"
            lowerMime.contains("svg") -> ".svg"
            lowerMime.contains("pdf") -> ".pdf"
            lowerMime.contains("vnd.android.package-archive") || lowerMime.contains("apk") -> ".apk"
            lowerMime.contains("zip") -> ".zip"
            lowerMime.contains("rar") -> ".rar"
            lowerMime.contains("7z") -> ".7z"
            lowerMime.contains("tar") -> ".tar"
            lowerMime.contains("gzip") || lowerMime.contains("gz") -> ".gz"
            lowerMime.contains("msword") || lowerMime.contains("wordprocessingml") -> ".docx"
            lowerMime.contains("ms-excel") || lowerMime.contains("spreadsheetml") -> ".xlsx"
            lowerMime.contains("ms-powerpoint") || lowerMime.contains("presentationml") -> ".pptx"
            lowerMime.contains("text/html") -> ".html"
            lowerMime.contains("text/plain") -> ".txt"
            lowerMime.contains("json") -> ".json"
            lowerMime.startsWith("video/") -> ".mp4"
            lowerMime.startsWith("audio/") -> ".mp3"
            lowerMime.startsWith("image/") -> ".jpg"
            else -> ""
        }
        if (dictExt.isNotBlank()) return dictExt

        // 4. URL host and path context hints
        val lowerUrl = url.lowercase()
        return when {
            lowerUrl.contains("googlevideo.com") || lowerUrl.contains("youtube.com") || lowerUrl.contains("youtu.be") -> ".mp4"
            lowerUrl.contains("cdninstagram.com") || lowerUrl.contains("instagram.com") -> ".mp4"
            lowerUrl.contains("tiktokcdn.com") || lowerUrl.contains("tiktok.com") -> ".mp4"
            lowerUrl.contains("fbcdn.net") || lowerUrl.contains("facebook.com") -> ".mp4"
            lowerUrl.contains("/audio/") || lowerUrl.contains("audio") || lowerUrl.contains("sound") -> ".mp3"
            lowerUrl.contains("/image/") || lowerUrl.contains("photo") || lowerUrl.contains("picture") -> ".jpg"
            else -> ""
        }
    }

    private fun resolveFileName(url: String, disposition: String, mimeType: String, fallback: String): String {
        // 1. Content-Disposition header
        if (disposition.isNotBlank()) {
            val rfcPattern = Pattern.compile("""filename\*=UTF-8''([^;\s]+)""", Pattern.CASE_INSENSITIVE)
            val rfcMatcher = rfcPattern.matcher(disposition)
            if (rfcMatcher.find()) {
                val raw = rfcMatcher.group(1).orEmpty()
                val decoded = try { URLDecoder.decode(raw, "UTF-8") } catch (e: Exception) { raw }
                if (decoded.isNotBlank()) {
                    val sanitized = sanitizeFileName(decoded)
                    if (sanitized.contains('.')) return sanitized
                    val ext = detectExtension(mimeType, url)
                    return if (ext.isNotBlank()) "$sanitized$ext" else sanitized
                }
            }

            val standardPattern = Pattern.compile("""filename\s*=\s*["']?([^"';]+)["']?""", Pattern.CASE_INSENSITIVE)
            val standardMatcher = standardPattern.matcher(disposition)
            if (standardMatcher.find()) {
                val raw = standardMatcher.group(1).orEmpty().trim('"', '\'')
                val decoded = try { URLDecoder.decode(raw, "UTF-8") } catch (e: Exception) { raw }
                if (decoded.isNotBlank()) {
                    val sanitized = sanitizeFileName(decoded)
                    if (sanitized.contains('.')) return sanitized
                    val ext = detectExtension(mimeType, url)
                    return if (ext.isNotBlank()) "$sanitized$ext" else sanitized
                }
            }
        }

        // 2. Query parameter check (e.g. ?filename=video.mp4)
        val queryFileName = extractFileNameFromQueryParams(url)
        if (queryFileName.isNotBlank() && queryFileName.contains('.')) {
            return sanitizeFileName(queryFileName)
        }

        // 3. Fallback title
        val cleanFallback = fallback.trim()
        val detectedExt = detectExtension(mimeType, url)
        val baseName: String

        if (cleanFallback.isNotBlank() && !cleanFallback.startsWith("file_") && !cleanFallback.startsWith("download_")) {
            if (cleanFallback.contains('.')) {
                return sanitizeFileName(cleanFallback)
            } else {
                baseName = sanitizeFileName(cleanFallback)
            }
        } else {
            val fromUrl = extractFileNameFromUrl(url)
            if (fromUrl.contains('.')) {
                return sanitizeFileName(fromUrl)
            }
            baseName = sanitizeFileName(fromUrl)
        }

        // 4. Append detected extension without duplication
        return if (detectedExt.isBlank() || baseName.endsWith(detectedExt, ignoreCase = true)) {
            baseName
        } else {
            "$baseName$detectedExt"
        }
    }

    private fun sanitizeFileName(name: String): String {
        return PathSecuritySanitizer.sanitizeFileName(name)
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
        return String.format(java.util.Locale.getDefault(), "%.2f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }
}

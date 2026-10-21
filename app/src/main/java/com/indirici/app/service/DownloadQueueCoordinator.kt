package com.indirici.app.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.indirici.app.data.local.DownloadDao
import com.indirici.app.data.local.DownloadTaskEntity
import com.indirici.app.data.settings.SettingsRepository
import com.indirici.app.domain.exception.DrmProtectedException
import com.indirici.app.domain.model.DownloadItem
import com.indirici.app.domain.model.DownloadStatus
import com.indirici.app.domain.model.EngineType
import com.indirici.app.domain.model.UrlMetadata
import com.indirici.app.domain.model.VideoDownloadTask
import com.indirici.app.domain.security.EphemeralSessionStore
import com.indirici.app.engine.DrmDetector
import com.indirici.app.engine.EngineRouter
import com.indirici.app.engine.MediaFileDetector
import com.indirici.app.engine.MediaFormatCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class DownloadQueueCoordinator(
    private val appContext: Context,
    private val downloadDao: DownloadDao,
    private val settingsRepository: SettingsRepository,
    private val engineRouter: EngineRouter,
    private val ephemeralSessionStore: EphemeralSessionStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scheduleMutex = Mutex()
    private val activeJobs = ConcurrentHashMap<Long, Job>()

    val downloadsFlow: Flow<List<DownloadItem>> = downloadDao.observeAllTasks()
        .map { list -> list.map { it.toDomain() } }

    init {
        scope.launch {
            runCatching {
                val interrupted = downloadDao.getTasksByStatus(DownloadStatus.DOWNLOADING.name)
                for (task in interrupted) {
                    if (!activeJobs.containsKey(task.id)) {
                        downloadDao.updateTaskStatus(task.id, DownloadStatus.PAUSED.name, null)
                    }
                }
            }
            settingsRepository.settingsFlow.collect {
                pumpQueue()
            }
        }
    }

    /**
     * V3: Kullanıcı mini tarayıcıda Cloudflare doğrulamasını geçtiğinde,
     * ön analiz (analyzeUrl) ve hemen ardından başlayacak indirme için oturumu geçici olarak RAM'e yazar.
     */
    fun storePreEnqueueSession(
        targetUrl: String,
        userAgent: String,
        cookie: String,
        referer: String
    ) {
        ephemeralSessionStore.putSession(
            downloadId = EphemeralSessionStore.PRE_ENQUEUE_SESSION_ID,
            targetUrl = targetUrl,
            userAgent = userAgent,
            cookieHeader = cookie,
            referer = referer
        )
    }

    fun clearPreEnqueueSession() {
        ephemeralSessionStore.clearSession(EphemeralSessionStore.PRE_ENQUEUE_SESSION_ID)
    }

    fun hasPreEnqueueSession(): Boolean {
        return ephemeralSessionStore.hasSession(EphemeralSessionStore.PRE_ENQUEUE_SESSION_ID)
    }

    suspend fun analyzeUrl(url: String): UrlMetadata {
        val cleanUrl = url.trim()
        val sessionId = if (hasPreEnqueueSession()) {
            EphemeralSessionStore.PRE_ENQUEUE_SESSION_ID
        } else {
            null
        }
        val primaryEngine = engineRouter.detectEngineForUrl(cleanUrl)
        val initialMeta = primaryEngine.analyze(cleanUrl, sessionId)

        if (primaryEngine.engineType == EngineType.DIRECT &&
            initialMeta.mimeType?.contains("text/html", ignoreCase = true) == true
        ) {
            return runCatching {
                engineRouter.mediaEngine.analyze(cleanUrl, sessionId)
            }.getOrDefault(initialMeta)
        }

        return initialMeta
    }

    /**
     * WebView Sniffer veya Cloudflare doğrulaması sonrası yakalanan VideoDownloadTask nesnesini kuyruğa ekler:
     * - .m3u8 / HLS akışlarını FfmpegHlsEngine (FFmpegKit.executeWithArgumentsAsync) ile indirir.
     * - Bilinen video/ses platformlarını MediaEngine (yt-dlp) ile indirir.
     * - Doğrudan ses/video/arşiv dosyalarını DirectEngine ile indirir.
     * - Cookie, Referer ve User-Agent oturum verilerini SADECE o site ve SADECE o indirme boyunca
     *   RAM'de (EphemeralSessionStore) tutar.
     */
    suspend fun enqueueSniffedVideoTask(task: VideoDownloadTask): Long {
        if (task.isDrmProtected) {
            throw DrmProtectedException()
        }
        DrmDetector.assertNotDrm(url = task.url, mimeType = task.mimeType)

        val isHls = MediaFileDetector.isHlsManifestUrl(task.url, task.mimeType)
        val detectedEngine = engineRouter.detectEngineForUrl(task.url)
        val engineType = when {
            isHls -> EngineType.FFMPEG_HLS
            detectedEngine.engineType == EngineType.MEDIA -> EngineType.MEDIA
            else -> EngineType.DIRECT
        }

        val ext = if (isHls) {
            "mp4"
        } else {
            MediaFileDetector.extractExtensionFromUrl(task.url)
                ?: MediaFileDetector.resolveExtensionFromMime(task.mimeType)
                ?: "mp4"
        }

        val rawTitle = task.pageTitle?.takeIf { it.isNotBlank() }
            ?: "video_${System.currentTimeMillis()}"
        val cleanBase = rawTitle
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(90)
            .ifBlank { "video_${System.currentTimeMillis()}" }
        val fileName = "$cleanBase.$ext"

        return enqueueDownload(
            url = task.url,
            customFileName = fileName,
            selectedFormatId = when {
                isHls -> "HLS"
                engineType == EngineType.MEDIA -> MediaFormatCatalog.DEFAULT_FORMAT_ID
                else -> ext.uppercase()
            },
            explicitEngineType = engineType,
            ephemeralUserAgent = task.userAgent,
            ephemeralCookieHeader = task.cookie,
            ephemeralReferer = task.referer
        )
    }

    suspend fun enqueueDownload(
        url: String,
        customFileName: String? = null,
        customChunkCount: Int? = null,
        selectedFormatId: String? = null,
        explicitEngineType: EngineType? = null,
        estimatedTotalBytes: Long = 0L,
        ephemeralUserAgent: String? = null,
        ephemeralCookieHeader: String? = null,
        ephemeralReferer: String? = null
    ): Long {
        val cleanUrl = url.trim()
        val detectedEngine = engineRouter.detectEngineForUrl(cleanUrl)
        val finalEngineType = explicitEngineType
            ?: if (selectedFormatId != null && detectedEngine.engineType == EngineType.MEDIA) {
                EngineType.MEDIA
            } else {
                detectedEngine.engineType
            }

        val settings = settingsRepository.settingsFlow.first()
        val chunks = (customChunkCount ?: settings.defaultChunkCount).coerceIn(1, 16)

        val formatToUse = when (finalEngineType) {
            EngineType.MEDIA -> selectedFormatId ?: MediaFormatCatalog.DEFAULT_FORMAT_ID
            EngineType.FFMPEG_HLS -> selectedFormatId ?: "HLS"
            EngineType.DIRECT -> null
        }

        val fallbackName = customFileName?.trim()?.takeIf { it.isNotEmpty() }?.let { rawCustom ->
            if (finalEngineType == EngineType.MEDIA) {
                MediaFormatCatalog.sanitizeTitleToFileName(rawCustom, formatToUse)
            } else {
                rawCustom
            }
        } ?: when (finalEngineType) {
            EngineType.MEDIA -> MediaFormatCatalog.sanitizeTitleToFileName(
                "medya_${System.currentTimeMillis()}",
                formatToUse
            )
            EngineType.FFMPEG_HLS -> "video_${System.currentTimeMillis()}.mp4"
            EngineType.DIRECT -> cleanUrl.substringBefore('?').substringAfterLast('/').ifBlank { "indirilen_dosya" }
        }

        val entity = DownloadTaskEntity(
            url = cleanUrl,
            fileName = fallbackName,
            mimeType = when (finalEngineType) {
                EngineType.MEDIA -> MediaFormatCatalog.getMimeTypeForFormatId(formatToUse)
                EngineType.FFMPEG_HLS -> "video/mp4"
                EngineType.DIRECT -> null
            },
            totalBytes = estimatedTotalBytes.coerceAtLeast(0L),
            downloadedBytes = 0L,
            speedBytesPerSec = 0L,
            chunkCount = chunks,
            supportsRange = finalEngineType == EngineType.MEDIA,
            status = DownloadStatus.QUEUED.name,
            engineType = finalEngineType.name,
            selectedFormatId = formatToUse
        )

        val taskId = downloadDao.insertTask(entity)

        if (!ephemeralUserAgent.isNullOrBlank() ||
            !ephemeralCookieHeader.isNullOrBlank() ||
            !ephemeralReferer.isNullOrBlank()
        ) {
            ephemeralSessionStore.putSession(
                downloadId = taskId,
                targetUrl = cleanUrl,
                userAgent = ephemeralUserAgent.orEmpty(),
                cookieHeader = ephemeralCookieHeader.orEmpty(),
                referer = ephemeralReferer.orEmpty()
            )
            ephemeralSessionStore.clearSession(EphemeralSessionStore.PRE_ENQUEUE_SESSION_ID)
        } else if (hasPreEnqueueSession()) {
            // Ön analiz sırasında Cloudflare doğrulamasından alınan oturumu bu taskId'ye aktar
            ephemeralSessionStore.moveSession(
                fromId = EphemeralSessionStore.PRE_ENQUEUE_SESSION_ID,
                toDownloadId = taskId
            )
        }

        ensureForegroundServiceRunning()
        pumpQueue()
        return taskId
    }

    fun pauseDownload(taskId: Long) {
        scope.launch {
            downloadDao.updateTaskStatus(taskId, DownloadStatus.PAUSED.name, null)
            activeJobs.remove(taskId)?.cancel()
            pumpQueue()
        }
    }

    fun resumeDownload(taskId: Long) {
        scope.launch {
            downloadDao.updateTaskStatus(taskId, DownloadStatus.QUEUED.name, null)
            ensureForegroundServiceRunning()
            pumpQueue()
        }
    }

    fun cancelDownload(taskId: Long) {
        scope.launch {
            activeJobs.remove(taskId)?.cancel()
            val task = downloadDao.getTaskById(taskId)
            task?.tempFilePath?.let { path ->
                runCatching { File(path).deleteRecursively() }
            }
            downloadDao.deleteChunksForTask(taskId)
            ephemeralSessionStore.clearSession(taskId)
            downloadDao.updateTaskStatus(taskId, DownloadStatus.CANCELED.name, null)
            pumpQueue()
        }
    }

    fun deleteDownload(taskId: Long) {
        scope.launch {
            activeJobs.remove(taskId)?.cancel()
            val task = downloadDao.getTaskById(taskId)
            task?.tempFilePath?.let { path ->
                runCatching { File(path).deleteRecursively() }
            }
            downloadDao.deleteChunksForTask(taskId)
            ephemeralSessionStore.clearSession(taskId)
            downloadDao.deleteTask(taskId)
            pumpQueue()
        }
    }

    fun pauseAllActive() {
        scope.launch {
            val activeIds = activeJobs.keys().toList()
            for (id in activeIds) {
                downloadDao.updateTaskStatus(id, DownloadStatus.PAUSED.name, null)
                activeJobs.remove(id)?.cancel()
            }
            val queued = downloadDao.getTasksByStatus(DownloadStatus.QUEUED.name)
            for (task in queued) {
                downloadDao.updateTaskStatus(task.id, DownloadStatus.PAUSED.name, null)
            }
        }
    }

    fun pumpQueue() {
        scope.launch {
            scheduleMutex.withLock {
                val finishedIds = activeJobs.filterValues { !it.isActive }.keys
                finishedIds.forEach { activeJobs.remove(it) }

                val settings = settingsRepository.settingsFlow.first()
                val maxConcurrent = settings.maxConcurrentDownloads
                val availableSlots = (maxConcurrent - activeJobs.size).coerceAtLeast(0)
                if (availableSlots == 0) return@withLock

                val queuedTasks = downloadDao.getTasksByStatus(DownloadStatus.QUEUED.name)
                    .filter { !activeJobs.containsKey(it.id) }
                    .take(availableSlots)

                for (task in queuedTasks) {
                    startTaskExecution(task)
                }
            }
        }
    }

    fun hasActiveOrQueuedWork(): Boolean {
        return activeJobs.values.any { it.isActive }
    }

    private fun startTaskExecution(task: DownloadTaskEntity) {
        val taskId = task.id
        val job = scope.launch {
            try {
                downloadDao.updateTaskStatus(taskId, DownloadStatus.DOWNLOADING.name, null)
                val engineType = runCatching { EngineType.valueOf(task.engineType) }
                    .getOrDefault(EngineType.DIRECT)
                val engine = engineRouter.getEngineByType(engineType)

                val savedUri = engine.executeDownload(taskId) { downloadedBytes, totalBytes, speedBytesPerSec ->
                    downloadDao.updateTaskProgress(
                        taskId = taskId,
                        downloadedBytes = downloadedBytes,
                        totalBytes = totalBytes,
                        speedBytesPerSec = speedBytesPerSec
                    )
                }

                downloadDao.markTaskCompleted(taskId = taskId, savedUri = savedUri)
                // Kural: İndirme tamamlandığında geçici oturum çerezlerini RAM'den sil
                ephemeralSessionStore.clearSession(taskId)
            } catch (ce: CancellationException) {
                throw ce
            } catch (drm: DrmProtectedException) {
                ephemeralSessionStore.clearSession(taskId)
                downloadDao.updateTaskStatus(
                    taskId = taskId,
                    status = DownloadStatus.FAILED.name,
                    errorMessage = DrmProtectedException.DRM_UNSUPPORTED_MESSAGE
                )
            } catch (e: Exception) {
                ephemeralSessionStore.clearSession(taskId)
                downloadDao.updateTaskStatus(
                    taskId = taskId,
                    status = DownloadStatus.FAILED.name,
                    errorMessage = e.message ?: "Bilinmeyen indirme hatası"
                )
            } finally {
                activeJobs.remove(taskId)
                pumpQueue()
            }
        }
        activeJobs[taskId] = job
    }

    private fun ensureForegroundServiceRunning() {
        runCatching {
            val intent = Intent(appContext, DownloadForegroundService::class.java).apply {
                action = DownloadForegroundService.ACTION_START_OR_UPDATE
            }
            ContextCompat.startForegroundService(appContext, intent)
        }
    }
}

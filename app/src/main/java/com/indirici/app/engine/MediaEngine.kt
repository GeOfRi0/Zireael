package com.indirici.app.engine

import android.content.Context
import com.indirici.app.data.local.DownloadDao
import com.indirici.app.data.storage.DownloadsFileSaver
import com.indirici.app.domain.engine.DownloadEngine
import com.indirici.app.domain.exception.MediaEngineException
import com.indirici.app.domain.model.EngineType
import com.indirici.app.domain.model.UrlMetadata
import com.indirici.app.domain.security.EphemeralSessionStore
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * yt-dlp güncelleme işlemi sonucunu temsil eder.
 */
sealed class YtDlpUpdateResult {
    data class Updated(val version: String) : YtDlpUpdateResult()
    data class AlreadyUpToDate(val version: String) : YtDlpUpdateResult()
    data class Error(val userMessage: String, val cause: Throwable? = null) : YtDlpUpdateResult()
}

/**
 * V2 (Video/Ses Siteleri ve HLS / H.265 / AAC / TS / fMP4 Akış Motoru):
 * youtubedl-android (yt-dlp + ffmpeg) kullanarak linkleri analiz eder,
 * kalite ve format seçeneklerini (Video: 1080p/720p/480p MP4, H.265, MKV, WebM, MOV, AVI, WMV, TS, fMP4;
 * Ses: MP3, M4A, AAC, WAV, OGG, FLAC, OPUS) çıkarır ve seçilen türde indirir.
 */
class MediaEngine(
    private val context: Context,
    private val downloadDao: DownloadDao,
    private val fileSaver: DownloadsFileSaver,
    private val ephemeralSessionStore: EphemeralSessionStore
) : DownloadEngine {

    override val engineType: EngineType = EngineType.MEDIA

    private val initMutex = Mutex()
    @Volatile
    private var isInitialized = false

    @Volatile
    private var cachedYtDlpVersion: String? = null

    private val _ytDlpVersionState = MutableStateFlow<String>("Bilinmiyor")
    val ytDlpVersionState: StateFlow<String> = _ytDlpVersionState.asStateFlow()

    suspend fun ensureInitialized() {
        if (isInitialized) return
        initMutex.withLock {
            ensureInitializedLocked()
        }
    }

    private fun ensureInitializedLocked() {
        if (isInitialized) return
        YoutubeDL.getInstance().init(context)
        runCatching { FFmpeg.getInstance().init(context) }
        isInitialized = true
    }

    private fun readYtDlpVersionLocked(): String {
        val fromPrefs = runCatching {
            YoutubeDL.getInstance().version(context)?.trim()?.takeIf { it.isNotBlank() }
        }.getOrNull()
        if (fromPrefs != null) {
            return fromPrefs
        }
        val fromCmd = runCatching {
            val req = YoutubeDLRequest(emptyList<String>()).addOption("--version")
            YoutubeDL.getInstance().execute(req, null, null).out.trim().takeIf { it.isNotBlank() }
        }.getOrNull()
        return fromCmd ?: "Bilinmiyor"
    }

    /**
     * Kurulu yt-dlp sürümünü döndürür ve UI StateFlow'unu günceller.
     */
    suspend fun getYtDlpVersion(): String {
        cachedYtDlpVersion?.let {
            _ytDlpVersionState.value = it
            return it
        }
        return withContext(Dispatchers.IO) {
            val fromPrefs = runCatching {
                YoutubeDL.getInstance().version(context)?.trim()?.takeIf { it.isNotBlank() }
            }.getOrNull()
            if (fromPrefs != null) {
                cachedYtDlpVersion = fromPrefs
                _ytDlpVersionState.value = fromPrefs
                return@withContext fromPrefs
            }

            val fromCmd = runCatching {
                ensureInitialized()
                val req = YoutubeDLRequest(emptyList<String>()).addOption("--version")
                YoutubeDL.getInstance().execute(req, null, null).out.trim().takeIf { it.isNotBlank() }
            }.getOrNull()
            val version = fromCmd ?: "Bilinmiyor"
            cachedYtDlpVersion = version
            _ytDlpVersionState.value = version
            version
        }
    }

    /**
     * yt-dlp video motorunu STABLE kanalından günceller.
     * ensureInitialized ile aynı initMutex kilidini paylaşarak eşzamanlı çakışmaları engeller.
     */
    suspend fun updateYtDlp(): YtDlpUpdateResult = withContext(Dispatchers.IO) {
        initMutex.withLock {
            ensureInitializedLocked()
            try {
                val status = withTimeout(90_000L) {
                    YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE)
                }
                cachedYtDlpVersion = null
                val newVersion = readYtDlpVersionLocked()
                cachedYtDlpVersion = newVersion
                _ytDlpVersionState.value = newVersion

                when (status) {
                    YoutubeDL.UpdateStatus.DONE -> YtDlpUpdateResult.Updated(newVersion)
                    YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> YtDlpUpdateResult.AlreadyUpToDate(newVersion)
                    else -> YtDlpUpdateResult.AlreadyUpToDate(newVersion)
                }
            } catch (e: Exception) {
                val friendly = mapUpdateErrorToUserMessage(e)
                YtDlpUpdateResult.Error(userMessage = friendly, cause = e)
            }
        }
    }

    override suspend fun analyze(url: String, downloadId: Long?): UrlMetadata = withContext(Dispatchers.IO) {
        ensureInitialized()

        val request = YoutubeDLRequest(url).apply {
            addOption("--no-playlist")
            addOption("--socket-timeout", 25)
            applyEphemeralHeadersIfPresent(this, url, downloadId)
        }

        val videoInfo = try {
            YoutubeDL.getInstance().getInfo(request)
        } catch (e: Exception) {
            val rawError = e.message?.trim().takeIf { !it.isNullOrBlank() } ?: "Bilinmeyen hata"
            val version = getYtDlpVersion()
            throw MediaEngineException(
                message = "Bu link indirilemedi.",
                rawError = rawError,
                ytDlpVersion = version,
                cause = e
            )
        }

        val rawStreams = videoInfo.formats.orEmpty().map { fmt ->
            val size = when {
                fmt.fileSize > 0L -> fmt.fileSize
                fmt.fileSizeApproximate > 0L -> fmt.fileSizeApproximate
                else -> -1L
            }
            MediaFormatCatalog.RawStreamSummary(
                height = fmt.height,
                vcodec = fmt.vcodec,
                acodec = fmt.acodec,
                ext = fmt.ext,
                fileSizeBytes = size
            )
        }

        val formatOptions = MediaFormatCatalog.buildFormatOptions(rawStreams)
        val defaultOption = formatOptions.firstOrNull {
            it.formatId == MediaFormatCatalog.DEFAULT_FORMAT_ID
        } ?: formatOptions.first()

        val suggestedFileName = MediaFormatCatalog.sanitizeTitleToFileName(
            rawTitle = videoInfo.title,
            formatId = defaultOption.formatId
        )

        val estimatedTotalBytes = when {
            defaultOption.estimatedSizeBytes > 0L -> defaultOption.estimatedSizeBytes
            videoInfo.fileSize > 0L -> videoInfo.fileSize
            videoInfo.fileSizeApproximate > 0L -> videoInfo.fileSizeApproximate
            else -> -1L
        }

        UrlMetadata(
            originalUrl = url,
            resolvedUrl = url,
            fileName = suggestedFileName,
            mimeType = MediaFormatCatalog.getMimeTypeForFormatId(defaultOption.formatId),
            totalBytes = estimatedTotalBytes,
            supportsRange = true,
            engineType = EngineType.MEDIA,
            availableFormats = formatOptions
        )
    }

    override suspend fun executeDownload(
        taskId: Long,
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long, speedBytesPerSec: Long) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val task = downloadDao.getTaskById(taskId)
            ?: throw IOException("İndirme görevi bulunamadı (id=$taskId)")

        ensureInitialized()

        val selectedFormatId = task.selectedFormatId ?: MediaFormatCatalog.DEFAULT_FORMAT_ID
        val targetExt = MediaFormatCatalog.getExtensionForFormatId(selectedFormatId)
        val targetMime = MediaFormatCatalog.getMimeTypeForFormatId(selectedFormatId)

        val workDir = fileSaver.getScopedTempDownloadsDir("partial_media/task_$taskId")
        val outputTemplate = File(workDir, "output.%(ext)s").absolutePath

        val finalFileName = MediaFormatCatalog.sanitizeTitleToFileName(
            rawTitle = task.fileName,
            formatId = selectedFormatId
        )

        downloadDao.updateTaskMetadata(
            taskId = taskId,
            fileName = finalFileName,
            mimeType = targetMime,
            totalBytes = task.totalBytes,
            supportsRange = true,
            tempFilePath = workDir.absolutePath
        )

        val request = YoutubeDLRequest(task.url).apply {
            addOption("--no-playlist")
            addOption("--continue")
            addOption("--newline")
            addOption("--concurrent-fragments", task.chunkCount.coerceIn(1, 16))
            addOption("-o", outputTemplate)
            applyFormatOptions(this, selectedFormatId)
            applyEphemeralHeadersIfPresent(this, task.url, taskId)
        }

        val processId = "indirici_media_task_$taskId"

        val cancellationHandle = coroutineContext[Job]?.invokeOnCompletion { cause ->
            if (cause != null) {
                runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
            }
        }

        var lastReportMs = 0L
        var dynamicTotalBytes = task.totalBytes.takeIf { it > 0L } ?: -1L

        try {
            YoutubeDL.getInstance().execute(request, processId) { progressPercent, _, line ->
                val parsedTotal = parseTotalBytesFromYtDlpLine(line)
                if (parsedTotal > 0L) {
                    dynamicTotalBytes = parsedTotal
                }
                val now = System.currentTimeMillis()
                if (now - lastReportMs >= 300L) {
                    lastReportMs = now
                    val safePercent = progressPercent.coerceIn(0f, 100f)
                    val knownTotal = if (dynamicTotalBytes > 0L) dynamicTotalBytes else 0L
                    val downloaded = if (knownTotal > 0L) {
                        ((safePercent / 100f) * knownTotal.toFloat()).toLong()
                    } else {
                        0L
                    }
                    val speed = parseSpeedFromYtDlpLine(line)

                    runCatching {
                        kotlinx.coroutines.runBlocking {
                            onProgress(downloaded, knownTotal, speed)
                        }
                    }
                }
            }
        } catch (ce: CancellationException) {
            runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
            throw ce
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            throw IOException("Medya indirme hatası: ${e.message ?: "Bilinmeyen hata"}", e)
        } finally {
            cancellationHandle?.dispose()
        }

        coroutineContext.ensureActive()

        val completedFile = findDownloadedMediaFile(workDir)
            ?: throw IOException("İndirilen medya dosyası oluşturulamadı.")

        // Eğer çıktı uzantısı hedef uzantıdan farklıysa (örn: ffmpeg .ogg için .ogg veya .opus ürettiyse)
        // doğru uzantıyla kaydet
        val actualSize = completedFile.length()
        onProgress(actualSize, actualSize, 0L)

        val savedUri = fileSaver.saveToPublicDownloads(
            tempFile = completedFile,
            desiredFileName = finalFileName,
            mimeType = targetMime
        )

        runCatching { workDir.deleteRecursively() }
        return@withContext savedUri
    }

    private fun applyFormatOptions(request: YoutubeDLRequest, formatId: String) {
        when (formatId) {
            MediaFormatCatalog.FORMAT_VIDEO_1080P -> {
                request.addOption(
                    "-f",
                    "bestvideo[height<=1080][ext=mp4]+bestaudio[ext=m4a]/best[height<=1080][ext=mp4]/best[height<=1080]/best"
                )
                request.addOption("--merge-output-format", "mp4")
            }

            MediaFormatCatalog.FORMAT_VIDEO_720P -> {
                request.addOption(
                    "-f",
                    "bestvideo[height<=720][ext=mp4]+bestaudio[ext=m4a]/best[height<=720][ext=mp4]/best[height<=720]/best"
                )
                request.addOption("--merge-output-format", "mp4")
            }

            MediaFormatCatalog.FORMAT_VIDEO_480P -> {
                request.addOption(
                    "-f",
                    "bestvideo[height<=480][ext=mp4]+bestaudio[ext=m4a]/best[height<=480][ext=mp4]/best[height<=480]/best"
                )
                request.addOption("--merge-output-format", "mp4")
            }

            MediaFormatCatalog.FORMAT_VIDEO_H265 -> {
                request.addOption(
                    "-f",
                    "bestvideo[vcodec~='^((he|h)vc|h265)']+bestaudio/best[vcodec~='^((he|h)vc|h265)']/bestvideo+bestaudio/best"
                )
                request.addOption("--merge-output-format", "mp4")
            }

            MediaFormatCatalog.FORMAT_VIDEO_MKV -> {
                request.addOption("-f", "bestvideo+bestaudio/best")
                request.addOption("--merge-output-format", "mkv")
                request.addOption("--remux-video", "mkv")
            }

            MediaFormatCatalog.FORMAT_VIDEO_WEBM -> {
                request.addOption(
                    "-f",
                    "bestvideo[ext=webm]+bestaudio[ext=webm]/best[ext=webm]/bestvideo+bestaudio/best"
                )
                request.addOption("--merge-output-format", "webm")
                request.addOption("--remux-video", "webm")
            }

            MediaFormatCatalog.FORMAT_VIDEO_MOV -> {
                request.addOption("-f", "best[ext=mov]/bestvideo+bestaudio/best")
                request.addOption("--remux-video", "mov")
            }

            MediaFormatCatalog.FORMAT_VIDEO_AVI -> {
                request.addOption("-f", "best[ext=avi]/bestvideo+bestaudio/best")
                request.addOption("--recode-video", "avi")
            }

            MediaFormatCatalog.FORMAT_VIDEO_WMV -> {
                request.addOption("-f", "best[ext=wmv]/bestvideo+bestaudio/best")
            }

            MediaFormatCatalog.FORMAT_VIDEO_TS -> {
                request.addOption("-f", "best[ext=ts]/bestvideo+bestaudio/best")
                request.addOption("--hls-use-mpegts")
            }

            MediaFormatCatalog.FORMAT_VIDEO_FMP4 -> {
                request.addOption(
                    "-f",
                    "bestvideo[protocol*=m3u8]+bestaudio/bestvideo[ext=mp4]+bestaudio[ext=m4a]/best"
                )
                request.addOption("--merge-output-format", "mp4")
            }

            MediaFormatCatalog.FORMAT_AUDIO_MP3 -> {
                request.addOption("-f", "bestaudio/best")
                request.addOption("-x")
                request.addOption("--audio-format", "mp3")
                request.addOption("--audio-quality", "0")
            }

            MediaFormatCatalog.FORMAT_AUDIO_M4A -> {
                request.addOption("-f", "bestaudio[ext=m4a]/bestaudio/best")
                request.addOption("-x")
                request.addOption("--audio-format", "m4a")
            }

            MediaFormatCatalog.FORMAT_AUDIO_AAC -> {
                request.addOption("-f", "bestaudio[ext=aac]/bestaudio[acodec^=mp4a]/bestaudio/best")
                request.addOption("-x")
                request.addOption("--audio-format", "aac")
            }

            MediaFormatCatalog.FORMAT_AUDIO_WAV -> {
                request.addOption("-f", "bestaudio/best")
                request.addOption("-x")
                request.addOption("--audio-format", "wav")
            }

            MediaFormatCatalog.FORMAT_AUDIO_OGG -> {
                request.addOption("-f", "bestaudio/best")
                request.addOption("-x")
                request.addOption("--audio-format", "vorbis")
            }

            MediaFormatCatalog.FORMAT_AUDIO_FLAC -> {
                request.addOption("-f", "bestaudio/best")
                request.addOption("-x")
                request.addOption("--audio-format", "flac")
            }

            MediaFormatCatalog.FORMAT_AUDIO_OPUS -> {
                request.addOption("-f", "bestaudio[acodec=opus]/bestaudio/best")
                request.addOption("-x")
                request.addOption("--audio-format", "opus")
            }

            else -> {
                request.addOption("-f", formatId)
            }
        }
    }

    private fun applyEphemeralHeadersIfPresent(
        request: YoutubeDLRequest,
        url: String,
        downloadId: Long?
    ) {
        if (downloadId == null) return
        val headers = ephemeralSessionStore.resolveHeadersForUrl(downloadId, url)
        headers["User-Agent"]?.let { ua ->
            request.addOption("--user-agent", ua)
        }
        headers["Referer"]?.let { referer ->
            request.addOption("--referer", referer)
        }
        headers["Cookie"]?.let { cookie ->
            request.addOption("--add-header", "Cookie:$cookie")
        }
    }

    private fun findDownloadedMediaFile(workDir: File): File? {
        val files = workDir.listFiles().orEmpty()
        return files
            .filter {
                it.isFile &&
                    !it.name.endsWith(".part", ignoreCase = true) &&
                    !it.name.endsWith(".ytdl", ignoreCase = true) &&
                    !it.name.endsWith(".temp", ignoreCase = true)
            }
            .maxByOrNull { it.length() }
    }

    companion object {
        private val SPEED_REGEX = Regex("at\\s+([0-9.]+)\\s*([KMG]i?B)/s", RegexOption.IGNORE_CASE)
        private val TOTAL_SIZE_REGEX = Regex("of\\s+~?\\s*([0-9.]+)\\s*([KMG]i?B)", RegexOption.IGNORE_CASE)

        internal fun parseSpeedFromYtDlpLine(line: String?): Long {
            if (line.isNullOrBlank()) return 0L
            val match = SPEED_REGEX.find(line) ?: return 0L
            val value = match.groupValues[1].toDoubleOrNull() ?: return 0L
            val unit = match.groupValues[2].uppercase()
            return convertToBytes(value, unit)
        }

        internal fun parseTotalBytesFromYtDlpLine(line: String?): Long {
            if (line.isNullOrBlank()) return -1L
            val match = TOTAL_SIZE_REGEX.find(line) ?: return -1L
            val value = match.groupValues[1].toDoubleOrNull() ?: return -1L
            val unit = match.groupValues[2].uppercase()
            return convertToBytes(value, unit)
        }

        private fun convertToBytes(value: Double, unit: String): Long {
            val multiplier = when (unit) {
                "KIB", "KB" -> 1024.0
                "MIB", "MB" -> 1024.0 * 1024.0
                "GIB", "GB" -> 1024.0 * 1024.0 * 1024.0
                else -> 1.0
            }
            return (value * multiplier).toLong()
        }

        internal fun mapUpdateErrorToUserMessage(e: Throwable): String {
            val message = e.message.orEmpty().lowercase()
            return when {
                e is TimeoutCancellationException || message.contains("timed out") || message.contains("timeout") -> {
                    "Güncelleme işlemi zaman aşımına uğradı. İnternet bağlantınızı kontrol edin."
                }
                e is java.net.UnknownHostException ||
                message.contains("unable to resolve host") ||
                message.contains("no address associated with hostname") ||
                message.contains("failed to connect") ||
                message.contains("connection refused") ||
                message.contains("network is unreachable") -> {
                    "Güncelleme sunucusuna bağlanılamadı. İnternet bağlantınızı kontrol edin."
                }
                message.contains("rate limit") || message.contains("api rate limit exceeded") || message.contains("403") -> {
                    "GitHub istek sınırı aşıldı. Lütfen daha sonra tekrar deneyin."
                }
                message.contains("404") || message.contains("not found") -> {
                    "Güncelleme paketi bulunamadı."
                }
                message.contains("space") || message.contains("enospc") -> {
                    "Yetersiz depolama alanı. Lütfen cihazınızda yer açın."
                }
                else -> {
                    "Video motoru güncellenemedi. Lütfen daha sonra tekrar deneyin."
                }
            }
        }
    }
}

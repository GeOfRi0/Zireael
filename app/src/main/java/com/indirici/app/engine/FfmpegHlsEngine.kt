package com.indirici.app.engine

import android.content.Context
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.indirici.app.data.local.DownloadDao
import com.indirici.app.data.storage.DownloadsFileSaver
import com.indirici.app.domain.engine.DownloadEngine
import com.indirici.app.domain.exception.DrmProtectedException
import com.indirici.app.domain.model.EngineType
import com.indirici.app.domain.model.UrlMetadata
import com.indirici.app.domain.security.EphemeralSessionStore
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * FFmpegKit.executeWithArgumentsAsync tabanlı HLS (.m3u8 / H.265 HLS / AAC HLS / TS / fMP4) indirme motoru.
 * String tırnaklama/ayrıştırma (escaping) hatalarını ve çerezlerdeki özel karakterlerin komutu
 * bozmasını önlemek için argümanları doğrudan Array<String> olarak FFmpeg'e iletir.
 */
class FfmpegHlsEngine(
    private val context: Context,
    private val downloadDao: DownloadDao,
    private val fileSaver: DownloadsFileSaver,
    private val ephemeralSessionStore: EphemeralSessionStore
) : DownloadEngine {

    override val engineType: EngineType = EngineType.FFMPEG_HLS

    override suspend fun analyze(url: String, downloadId: Long?): UrlMetadata = withContext(Dispatchers.IO) {
        DrmDetector.assertNotDrm(url = url)
        val defaultName = MediaFormatCatalog.sanitizeTitleToFileName(
            rawTitle = "video_${System.currentTimeMillis()}",
            formatId = MediaFormatCatalog.FORMAT_VIDEO_1080P
        )
        UrlMetadata(
            originalUrl = url,
            resolvedUrl = url,
            fileName = defaultName,
            mimeType = "video/mp4",
            totalBytes = -1L,
            supportsRange = false,
            engineType = EngineType.FFMPEG_HLS,
            availableFormats = MediaFormatCatalog.buildFormatOptions()
        )
    }

    override suspend fun executeDownload(
        taskId: Long,
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long, speedBytesPerSec: Long) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val task = downloadDao.getTaskById(taskId)
            ?: throw IOException("İndirme görevi bulunamadı (id=$taskId)")

        DrmDetector.assertNotDrm(url = task.url, mimeType = task.mimeType)

        val workDir = fileSaver.getScopedTempDownloadsDir("partial_ffmpeg/task_$taskId")
        val cleanFileName = if (task.fileName.endsWith(".mp4", ignoreCase = true) ||
            task.fileName.endsWith(".mkv", ignoreCase = true) ||
            task.fileName.endsWith(".ts", ignoreCase = true) ||
            task.fileName.endsWith(".m4a", ignoreCase = true) ||
            task.fileName.endsWith(".aac", ignoreCase = true)
        ) {
            task.fileName
        } else {
            "${task.fileName.substringBeforeLast('.', task.fileName)}.mp4"
        }

        val outputFile = File(workDir, "ffmpeg_output_${taskId}.mp4")
        if (outputFile.exists()) {
            outputFile.delete()
        }

        downloadDao.updateTaskMetadata(
            taskId = taskId,
            fileName = cleanFileName,
            mimeType = "video/mp4",
            totalBytes = task.totalBytes,
            supportsRange = false,
            tempFilePath = workDir.absolutePath
        )

        val sessionHeaders = ephemeralSessionStore.resolveHeadersForUrl(taskId, task.url)
        val userAgent = sessionHeaders["User-Agent"] ?: DEFAULT_USER_AGENT
        val referer = sessionHeaders["Referer"].orEmpty()
        val cookie = sessionHeaders["Cookie"].orEmpty()

        val arguments: Array<String> = buildFfmpegArguments(
            m3u8Url = task.url,
            outputPath = outputFile.absolutePath,
            userAgent = userAgent,
            referer = referer,
            cookie = cookie
        )

        var lastReportTimeMs = System.currentTimeMillis()
        var lastReportedBytes = 0L
        val drmDetected = AtomicBoolean(false)

        try {
            suspendCancellableCoroutine<Unit> { continuation ->
                val ffmpegSession = FFmpegKit.executeWithArgumentsAsync(
                    arguments,
                    { completedSession ->
                        if (!continuation.isActive) return@executeWithArgumentsAsync
                        val returnCode = completedSession.returnCode
                        when {
                            drmDetected.get() -> {
                                continuation.resumeWithException(DrmProtectedException())
                            }
                            ReturnCode.isSuccess(returnCode) -> {
                                continuation.resume(Unit)
                            }
                            ReturnCode.isCancel(returnCode) -> {
                                continuation.resumeWithException(
                                    CancellationException("FFmpeg indirmesi kullanıcı tarafından durduruldu")
                                )
                            }
                            else -> {
                                val logs = completedSession.allLogsAsString
                                try {
                                    DrmDetector.assertNotDrmInErrorMessage(logs)
                                    continuation.resumeWithException(
                                        IOException("FFmpeg video indirme hatası (Kod: $returnCode)")
                                    )
                                } catch (drm: DrmProtectedException) {
                                    continuation.resumeWithException(drm)
                                }
                            }
                        }
                    },
                    { log ->
                        val message = log?.message
                        if (!message.isNullOrBlank()) {
                            try {
                                DrmDetector.assertNotDrmInErrorMessage(message)
                            } catch (_: DrmProtectedException) {
                                drmDetected.set(true)
                                FFmpegKit.cancel()
                            }
                        }
                    },
                    { statistics ->
                        val currentBytes = statistics?.size ?: 0L
                        val now = System.currentTimeMillis()
                        val elapsedMs = now - lastReportTimeMs
                        if (currentBytes > 0L && elapsedMs >= 300L) {
                            val deltaBytes = (currentBytes - lastReportedBytes).coerceAtLeast(0L)
                            val speed = (deltaBytes * 1000L) / elapsedMs
                            lastReportTimeMs = now
                            lastReportedBytes = currentBytes
                            runCatching {
                                runBlocking {
                                    onProgress(currentBytes, task.totalBytes, speed)
                                }
                            }
                        }
                    }
                )

                continuation.invokeOnCancellation {
                    runCatching { FFmpegKit.cancel(ffmpegSession.sessionId) }
                }
            }
        } catch (t: Throwable) {
            if (t is CancellationException || t is DrmProtectedException) throw t
            coroutineContext.ensureActive()
            // 32-bit (armeabi-v7a) cihazlarda veya FFmpegKit'in çözemediği özel HLS manifestlerinde
            // tüm ABI'leri destekleyen gömülü yt-dlp + ffmpeg motoruna (youtubedl-android) geri düş.
            if (outputFile.exists()) outputFile.delete()
            executeYtDlpHlsFallback(
                taskId = taskId,
                m3u8Url = task.url,
                outputFile = outputFile,
                userAgent = userAgent,
                referer = referer,
                cookie = cookie,
                totalBytesHint = task.totalBytes,
                onProgress = onProgress
            )
        }

        if (!outputFile.exists() || outputFile.length() == 0L) {
            throw IOException("FFmpeg çıktı dosyası oluşturulamadı.")
        }

        val finalSize = outputFile.length()
        onProgress(finalSize, finalSize, 0L)

        val savedUri = fileSaver.saveToPublicDownloads(
            tempFile = outputFile,
            desiredFileName = cleanFileName,
            mimeType = "video/mp4",
            saveVideoToMovies = true
        )

        runCatching { workDir.deleteRecursively() }
        return@withContext savedUri
    }

    private suspend fun executeYtDlpHlsFallback(
        taskId: Long,
        m3u8Url: String,
        outputFile: File,
        userAgent: String,
        referer: String,
        cookie: String,
        totalBytesHint: Long,
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long, speedBytesPerSec: Long) -> Unit
    ) {
        YoutubeDL.getInstance().init(context)
        runCatching { FFmpeg.getInstance().init(context) }

        val request = YoutubeDLRequest(m3u8Url).apply {
            addOption("--no-playlist")
            addOption("--newline")
            addOption("--merge-output-format", "mp4")
            addOption("-o", outputFile.absolutePath)
            if (userAgent.isNotBlank()) addOption("--user-agent", userAgent)
            if (referer.isNotBlank()) addOption("--referer", referer)
            if (cookie.isNotBlank()) addOption("--add-header", "Cookie:$cookie")
        }

        val processId = "indirici_hls_fallback_$taskId"
        val cancelHandle = coroutineContext[Job]?.invokeOnCompletion { cause ->
            if (cause != null) {
                runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
            }
        }

        var lastReportMs = 0L
        var dynamicTotalBytes = totalBytesHint.takeIf { it > 0L } ?: -1L
        try {
            YoutubeDL.getInstance().execute(request, processId) { progressPercent, _, line ->
                DrmDetector.assertNotDrmInErrorMessage(line)
                val parsedTotal = MediaEngine.parseTotalBytesFromYtDlpLine(line)
                if (parsedTotal > 0L) dynamicTotalBytes = parsedTotal
                val now = System.currentTimeMillis()
                if (now - lastReportMs >= 300L) {
                    lastReportMs = now
                    val safePercent = progressPercent.coerceIn(0f, 100f)
                    val knownTotal = if (dynamicTotalBytes > 0L) dynamicTotalBytes else 0L
                    val downloaded = if (knownTotal > 0L) {
                        ((safePercent / 100f) * knownTotal.toFloat()).toLong()
                    } else {
                        outputFile.takeIf { it.exists() }?.length() ?: 0L
                    }
                    val speed = MediaEngine.parseSpeedFromYtDlpLine(line)
                    runCatching {
                        runBlocking {
                            onProgress(downloaded, knownTotal, speed)
                        }
                    }
                }
            }
        } catch (ce: CancellationException) {
            runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
            throw ce
        } catch (drm: DrmProtectedException) {
            throw drm
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            DrmDetector.assertNotDrmInErrorMessage(e.message)
            throw IOException("HLS video indirme hatası: ${e.message ?: "Bilinmeyen hata"}", e)
        } finally {
            cancelHandle?.dispose()
        }
    }

    companion object {
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        /**
         * String tırnaklama/ayrıştırma (escaping) hatalarını ve çerezlerdeki özel karakterlerin
         * (", ', ;, =, boşluk vb.) komutu bozmasını önlemek için FFmpeg argümanlarını
         * doğrudan Array<String> formatında üretir.
         */
        fun buildFfmpegArguments(
            m3u8Url: String,
            outputPath: String,
            userAgent: String,
            referer: String,
            cookie: String
        ): Array<String> {
            val headersBlock = buildString {
                if (userAgent.isNotBlank()) {
                    append("User-Agent: ").append(sanitizeHeaderLineValue(userAgent)).append("\r\n")
                }
                if (referer.isNotBlank()) {
                    append("Referer: ").append(sanitizeHeaderLineValue(referer)).append("\r\n")
                }
                if (cookie.isNotBlank()) {
                    append("Cookie: ").append(sanitizeHeaderLineValue(cookie)).append("\r\n")
                }
            }

            val args = ArrayList<String>(18)
            if (headersBlock.isNotEmpty()) {
                args.add("-headers")
                args.add(headersBlock)
            }
            args.addAll(
                listOf(
                    "-reconnect", "1",
                    "-reconnect_at_eof", "1",
                    "-reconnect_streamed", "1",
                    "-reconnect_delay_max", "5",
                    "-i", m3u8Url,
                    "-c", "copy",
                    "-bsf:a", "aac_adtstoasc",
                    "-y", outputPath
                )
            )

            return args.toTypedArray()
        }

        private fun sanitizeHeaderLineValue(raw: String): String {
            // Tırnak, noktalı virgül, eşittir vb. tüm çerez/UA karakterlerini olduğu gibi korur;
            // yalnızca tek bir başlık satırını bozabilecek satır sonu (\r, \n) karakterlerini temizler.
            return raw.replace("\r", "").replace("\n", "").trim()
        }
    }
}

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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
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

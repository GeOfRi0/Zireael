package com.indirici.app.engine

import android.content.Context
import android.webkit.MimeTypeMap
import com.indirici.app.data.local.DownloadChunkEntity
import com.indirici.app.data.local.DownloadDao
import com.indirici.app.data.storage.DownloadsFileSaver
import com.indirici.app.domain.engine.DownloadEngine
import com.indirici.app.domain.exception.EngineRerouteRequiredException
import com.indirici.app.domain.model.EngineType
import com.indirici.app.domain.model.UrlMetadata
import com.indirici.app.domain.security.EphemeralSessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

class DirectEngine(
    private val context: Context,
    private val downloadDao: DownloadDao,
    private val fileSaver: DownloadsFileSaver,
    private val ephemeralSessionStore: EphemeralSessionStore,
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
) : DownloadEngine {

    override val engineType: EngineType = EngineType.DIRECT

    override suspend fun analyze(url: String, downloadId: Long?): UrlMetadata = withContext(Dispatchers.IO) {
        DrmDetector.assertNotDrm(url = url)

        val rangeProbeRequest = buildRequest(url, downloadId) {
            get()
            header("Range", "bytes=0-4095")
        }

        okHttpClient.newCall(rangeProbeRequest).execute().use { response ->
            val resolvedUrl = response.request.url.toString()
            val rawMime = response.header("Content-Type")?.substringBefore(';')?.trim()
            val detectedExt = MediaFileDetector.extractExtensionFromUrl(resolvedUrl)
                ?: MediaFileDetector.resolveExtensionFromMime(rawMime)
            val mimeType = rawMime ?: MediaFileDetector.resolveMimeFromExtension(detectedExt)

            DrmDetector.assertNotDrm(
                url = resolvedUrl,
                mimeType = mimeType,
                headers = response.headers.toMultimap()
            )

            if (!response.isSuccessful && response.code != 416) {
                throw IOException("Sunucu hatası: HTTP ${response.code}")
            }

            // Eğer yanıt bir HLS (.m3u8) manifestiyse içeriğin ilk kısmını okuyup DRM ve H.265/AAC/fMP4 kontrolü yap
            if (MediaFileDetector.isHlsManifestUrl(resolvedUrl, mimeType)) {
                val sampleText = readBodyPreview(response)
                val hlsInspection = MediaFileDetector.inspectHlsManifest(resolvedUrl, sampleText)
                val baseName = extractFileName(
                    contentDisposition = response.header("Content-Disposition"),
                    resolvedUrl = resolvedUrl,
                    mimeType = mimeType
                ).substringBeforeLast('.')

                val defaultFormat = when {
                    hlsInspection.isAacAudioStream -> MediaFormatCatalog.FORMAT_AUDIO_AAC
                    hlsInspection.hasH265Hevc -> MediaFormatCatalog.FORMAT_VIDEO_H265
                    hlsInspection.usesFmp4M4sSegments -> MediaFormatCatalog.FORMAT_VIDEO_FMP4
                    else -> MediaFormatCatalog.DEFAULT_FORMAT_ID
                }

                return@withContext UrlMetadata(
                    originalUrl = url,
                    resolvedUrl = resolvedUrl,
                    fileName = MediaFormatCatalog.sanitizeTitleToFileName(baseName, defaultFormat),
                    mimeType = MediaFormatCatalog.getMimeTypeForFormatId(defaultFormat),
                    totalBytes = -1L,
                    supportsRange = true,
                    engineType = EngineType.MEDIA,
                    availableFormats = MediaFormatCatalog.buildFormatOptions()
                )
            }

            // Eğer yanıt bir HTML sayfasıysa ve içinde doğrudan medya linki (.wav, .flac, .mkv, .m3u8, .m4s vb.) gömülüyse yakala
            if (mimeType?.contains("text/html", ignoreCase = true) == true) {
                val htmlSample = readBodyPreview(response)
                val sniffedLinks = MediaFileDetector.extractMediaLinksFromContent(htmlSample, resolvedUrl)
                val firstDirectMedia = sniffedLinks.firstOrNull()
                if (firstDirectMedia != null && firstDirectMedia != url) {
                    if (MediaFileDetector.isHlsManifestUrl(firstDirectMedia)) {
                        return@withContext UrlMetadata(
                            originalUrl = url,
                            resolvedUrl = firstDirectMedia,
                            fileName = MediaFormatCatalog.sanitizeTitleToFileName("akis_medya", MediaFormatCatalog.DEFAULT_FORMAT_ID),
                            mimeType = "video/mp4",
                            totalBytes = -1L,
                            supportsRange = true,
                            engineType = EngineType.MEDIA,
                            availableFormats = MediaFormatCatalog.buildFormatOptions()
                        )
                    } else {
                        val ext = MediaFileDetector.extractExtensionFromUrl(firstDirectMedia) ?: "mp4"
                        val sniffedName = extractFileName(null, firstDirectMedia, MediaFileDetector.resolveMimeFromExtension(ext))
                        return@withContext UrlMetadata(
                            originalUrl = url,
                            resolvedUrl = firstDirectMedia,
                            fileName = sniffedName,
                            mimeType = MediaFileDetector.resolveMimeFromExtension(ext),
                            totalBytes = -1L,
                            supportsRange = true,
                            engineType = EngineType.DIRECT
                        )
                    }
                }
            }

            val contentRange = response.header("Content-Range")
            val acceptRanges = response.header("Accept-Ranges")
            val isPartial = response.code == 206 ||
                acceptRanges?.contains("bytes", ignoreCase = true) == true ||
                contentRange?.startsWith("bytes", ignoreCase = true) == true

            val totalBytesFromRange = parseTotalBytesFromContentRange(contentRange)
            val contentLength = response.header("Content-Length")?.toLongOrNull() ?: -1L
            val totalBytes = when {
                totalBytesFromRange > 0L -> totalBytesFromRange
                response.code == 200 && contentLength > 0L -> contentLength
                else -> -1L
            }

            val fileName = extractFileName(
                contentDisposition = response.header("Content-Disposition"),
                resolvedUrl = resolvedUrl,
                mimeType = mimeType
            )

            return@withContext UrlMetadata(
                originalUrl = url,
                resolvedUrl = resolvedUrl,
                fileName = fileName,
                mimeType = mimeType,
                totalBytes = totalBytes,
                supportsRange = isPartial && totalBytes > 0L,
                engineType = EngineType.DIRECT
            )
        }
    }

    override suspend fun executeDownload(
        taskId: Long,
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long, speedBytesPerSec: Long) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val initialTask = downloadDao.getTaskById(taskId)
            ?: throw IOException("İndirme görevi bulunamadı (id=$taskId)")

        DrmDetector.assertNotDrm(url = initialTask.url, mimeType = initialTask.mimeType)

        val (effectiveUrl, fileName, mimeType, totalBytes, supportsRange, tempFile) = if (
            initialTask.tempFilePath.isNullOrBlank() || initialTask.totalBytes == 0L
        ) {
            val meta = analyze(initialTask.url, taskId)
            val resolvedName = initialTask.fileName
                .takeIf { it.isNotBlank() && it != "indirilen_dosya" && it.contains('.') }
                ?: meta.fileName

            // Eğer yönlendirme (redirect) veya başlık analizi sonucunda kaynağın bir HLS (.m3u8) veya
            // medya akışı olduğu ortaya çıkarsa, sahte/bozuk dosya indirmek yerine görevi gerçek motoruna devret
            if (meta.engineType != EngineType.DIRECT) {
                downloadDao.updateTaskEngineAndUrl(
                    taskId = taskId,
                    url = meta.resolvedUrl.ifBlank { initialTask.url },
                    fileName = resolvedName,
                    mimeType = meta.mimeType,
                    engineType = meta.engineType.name
                )
                throw EngineRerouteRequiredException(meta.engineType)
            }
            if (meta.mimeType?.contains("text/html", ignoreCase = true) == true) {
                throw IOException(
                    "Bu bağlantı bir dosya değil, web sayfası döndürdü. Lütfen 'Tarayıcı ile Yakala' veya 'Cloudflare Geç' seçeneğini kullanın."
                )
            }

            val partDir = fileSaver.getScopedTempDownloadsDir("partial_direct")
            val partFile = File(partDir, "download_${taskId}.part")
            downloadDao.updateTaskMetadata(
                taskId = taskId,
                fileName = resolvedName,
                mimeType = meta.mimeType,
                totalBytes = meta.totalBytes,
                supportsRange = meta.supportsRange,
                tempFilePath = partFile.absolutePath
            )
            TaskExecutionMeta(
                effectiveUrl = meta.resolvedUrl.ifBlank { initialTask.url },
                fileName = resolvedName,
                mimeType = meta.mimeType,
                totalBytes = meta.totalBytes,
                supportsRange = meta.supportsRange,
                tempFile = partFile
            )
        } else {
            TaskExecutionMeta(
                effectiveUrl = initialTask.url,
                fileName = initialTask.fileName,
                mimeType = initialTask.mimeType,
                totalBytes = initialTask.totalBytes,
                supportsRange = initialTask.supportsRange,
                tempFile = File(initialTask.tempFilePath)
            )
        }

        tempFile.parentFile?.mkdirs()

        var chunks = downloadDao.getChunksForTask(taskId)
        if (chunks.isEmpty()) {
            val slices = ChunkCalculator.calculateSlices(
                totalBytes = totalBytes,
                requestedChunks = initialTask.chunkCount,
                supportsRange = supportsRange
            )
            val entities = slices.map { slice ->
                DownloadChunkEntity(
                    taskId = taskId,
                    chunkIndex = slice.chunkIndex,
                    startByte = slice.startByte,
                    endByte = slice.endByte,
                    downloadedBytes = 0L,
                    isCompleted = false
                )
            }
            downloadDao.insertChunks(entities)
            chunks = downloadDao.getChunksForTask(taskId)
        }

        if (!supportsRange && chunks.size == 1 && !chunks.first().isCompleted && chunks.first().downloadedBytes > 0L) {
            if (tempFile.exists()) tempFile.delete()
            downloadDao.updateChunkProgress(taskId, 0, 0L, false)
            chunks = downloadDao.getChunksForTask(taskId)
        }

        if (totalBytes > 0L && !tempFile.exists()) {
            RandomAccessFile(tempFile, "rw").use { raf ->
                raf.setLength(totalBytes)
            }
        }

        val initialDownloaded = chunks.sumOf { it.downloadedBytes }
        val globalDownloaded = AtomicLong(initialDownloaded)
        val progressMutex = Mutex()
        var lastReportTimeMs = System.currentTimeMillis()
        var lastReportedBytes = initialDownloaded

        onProgress(initialDownloaded, totalBytes, 0L)

        val totalChunkCount = chunks.size
        val restartSingleChunkFallback = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            coroutineScope {
                chunks.filter { !it.isCompleted }.map { chunk ->
                    async(Dispatchers.IO) {
                        downloadSingleChunk(
                            taskId = taskId,
                            url = effectiveUrl,
                            tempFile = tempFile,
                            chunk = chunk,
                            totalChunkCount = totalChunkCount,
                            supportsRange = supportsRange,
                            totalBytes = totalBytes,
                            globalDownloaded = globalDownloaded,
                            onChunkBytesRead = {
                                val now = System.currentTimeMillis()
                                if (now - lastReportTimeMs >= 250L) {
                                    progressMutex.withLock {
                                        val elapsedMs = now - lastReportTimeMs
                                        if (elapsedMs >= 250L) {
                                            val currentTotal = globalDownloaded.get()
                                            val deltaBytes = (currentTotal - lastReportedBytes).coerceAtLeast(0L)
                                            val speed = (deltaBytes * 1000L) / elapsedMs
                                            lastReportTimeMs = now
                                            lastReportedBytes = currentTotal
                                            onProgress(currentTotal, totalBytes, speed)
                                        }
                                    }
                                }
                            }
                        )
                    }
                }.awaitAll()
            }
        } catch (fallbackEx: SingleStreamFallbackException) {
            restartSingleChunkFallback.set(true)
        }

        if (restartSingleChunkFallback.get()) {
            // Sunucu/CDN çoklu parça (HTTP 206) isteğini reddedip HTTP 200 döndürdüyse,
            // otomatik olarak tek parçalı (single-stream) indirmeye düş ve görevi kurtar
            if (tempFile.exists()) tempFile.delete()
            downloadDao.deleteChunksForTask(taskId)
            val singleChunk = DownloadChunkEntity(
                taskId = taskId,
                chunkIndex = 0,
                startByte = 0L,
                endByte = if (totalBytes > 0L) totalBytes - 1L else -1L,
                downloadedBytes = 0L,
                isCompleted = false
            )
            downloadDao.insertChunks(listOf(singleChunk))
            val savedSingle = downloadDao.getChunksForTask(taskId).first()
            globalDownloaded.set(0L)
            lastReportedBytes = 0L
            lastReportTimeMs = System.currentTimeMillis()
            downloadSingleChunk(
                taskId = taskId,
                url = effectiveUrl,
                tempFile = tempFile,
                chunk = savedSingle,
                totalChunkCount = 1,
                supportsRange = false,
                totalBytes = totalBytes,
                globalDownloaded = globalDownloaded,
                onChunkBytesRead = {
                    val now = System.currentTimeMillis()
                    if (now - lastReportTimeMs >= 250L) {
                        progressMutex.withLock {
                            val elapsedMs = now - lastReportTimeMs
                            if (elapsedMs >= 250L) {
                                val currentTotal = globalDownloaded.get()
                                val deltaBytes = (currentTotal - lastReportedBytes).coerceAtLeast(0L)
                                val speed = (deltaBytes * 1000L) / elapsedMs
                                lastReportTimeMs = now
                                lastReportedBytes = currentTotal
                                onProgress(currentTotal, totalBytes, speed)
                            }
                        }
                    }
                }
            )
        }

        val finalBytes = if (totalBytes > 0L) totalBytes else globalDownloaded.get()
        onProgress(finalBytes, finalBytes, 0L)

        val savedUri = fileSaver.saveToPublicDownloads(
            tempFile = tempFile,
            desiredFileName = fileName,
            mimeType = mimeType
        )
        downloadDao.deleteChunksForTask(taskId)
        return@withContext savedUri
    }

    private suspend fun downloadSingleChunk(
        taskId: Long,
        url: String,
        tempFile: File,
        chunk: DownloadChunkEntity,
        totalChunkCount: Int,
        supportsRange: Boolean,
        totalBytes: Long,
        globalDownloaded: AtomicLong,
        onChunkBytesRead: suspend () -> Unit
    ) {
        var chunkDownloaded = chunk.downloadedBytes
        val currentStart = chunk.startByte + chunkDownloaded

        if (supportsRange && chunk.endByte >= 0L && currentStart > chunk.endByte) {
            downloadDao.updateChunkProgress(taskId, chunk.chunkIndex, chunkDownloaded, true)
            return
        }

        val request = buildRequest(url, taskId) {
            get()
            if (supportsRange && chunk.endByte >= 0L) {
                header("Range", "bytes=$currentStart-${chunk.endByte}")
            }
        }

        val call = okHttpClient.newCall(request)
        val cancelHandle = coroutineContext[Job]?.invokeOnCompletion { cause ->
            if (cause != null) {
                runCatching { call.cancel() }
            }
        }

        try {
            call.execute().use { response ->
                DrmDetector.assertNotDrm(
                    url = response.request.url.toString(),
                    mimeType = response.header("Content-Type"),
                    headers = response.headers.toMultimap()
                )

                if (!response.isSuccessful) {
                    throw IOException("Parça ${chunk.chunkIndex + 1} indirilemedi: HTTP ${response.code}")
                }

                val body = response.body ?: throw IOException("Sunucudan boş yanıt döndü")

                val writeStartOffset = if (supportsRange && response.code == 206) {
                    currentStart
                } else if (chunk.chunkIndex == 0 && totalChunkCount == 1) {
                    globalDownloaded.addAndGet(-chunkDownloaded)
                    chunkDownloaded = 0L
                    0L
                } else {
                    throw SingleStreamFallbackException()
                }

                RandomAccessFile(tempFile, "rw").use { raf ->
                    raf.seek(writeStartOffset)
                    body.byteStream().use { inputStream ->
                        val buffer = ByteArray(64 * 1024)
                        var lastDbSyncMs = System.currentTimeMillis()

                        while (true) {
                            coroutineContext.ensureActive()
                            val bytesRead = inputStream.read(buffer)
                            if (bytesRead == -1) break

                            val maxAllowed = if (supportsRange && chunk.endByte >= 0L) {
                                val remaining = (chunk.endByte - chunk.startByte + 1L) - chunkDownloaded
                                bytesRead.toLong().coerceAtMost(remaining).toInt()
                            } else {
                                bytesRead
                            }

                            if (maxAllowed <= 0) break

                            raf.write(buffer, 0, maxAllowed)
                            chunkDownloaded += maxAllowed
                            globalDownloaded.addAndGet(maxAllowed.toLong())

                            val now = System.currentTimeMillis()
                            if (now - lastDbSyncMs >= 400L) {
                                lastDbSyncMs = now
                                downloadDao.updateChunkProgress(
                                    taskId = taskId,
                                    chunkIndex = chunk.chunkIndex,
                                    downloadedBytes = chunkDownloaded,
                                    isCompleted = false
                                )
                            }

                            onChunkBytesRead()

                            if (supportsRange && chunk.endByte >= 0L &&
                                chunkDownloaded >= (chunk.endByte - chunk.startByte + 1L)
                            ) {
                                break
                            }
                        }
                    }
                }

                downloadDao.updateChunkProgress(
                    taskId = taskId,
                    chunkIndex = chunk.chunkIndex,
                    downloadedBytes = chunkDownloaded,
                    isCompleted = true
                )
            }
        } catch (e: IOException) {
            coroutineContext.ensureActive()
            throw e
        } finally {
            cancelHandle?.dispose()
        }
    }

    private fun buildRequest(
        url: String,
        downloadId: Long?,
        block: Request.Builder.() -> Unit
    ): Request {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", DEFAULT_USER_AGENT)

        if (downloadId != null) {
            val sessionHeaders = ephemeralSessionStore.resolveHeadersForUrl(downloadId, url)
            for ((k, v) in sessionHeaders) {
                builder.header(k, v)
            }
        }

        builder.block()
        return builder.build()
    }

    private fun readBodyPreview(response: okhttp3.Response, maxBytes: Long = 65_536L): String {
        val source = response.body?.source() ?: return ""
        return runCatching {
            source.request(maxBytes)
            val toRead = minOf(source.buffer.size, maxBytes)
            source.buffer.clone().readString(toRead, Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun parseTotalBytesFromContentRange(contentRange: String?): Long {
        if (contentRange.isNullOrBlank()) return -1L
        val slashIndex = contentRange.lastIndexOf('/')
        if (slashIndex == -1 || slashIndex == contentRange.lastIndex) return -1L
        return contentRange.substring(slashIndex + 1).trim().toLongOrNull() ?: -1L
    }

    private fun extractFileName(
        contentDisposition: String?,
        resolvedUrl: String,
        mimeType: String?
    ): String {
        if (!contentDisposition.isNullOrBlank()) {
            val utf8Match = Regex("filename\\*=UTF-8''([^;]+)", RegexOption.IGNORE_CASE)
                .find(contentDisposition)
            if (utf8Match != null) {
                val decoded = runCatching {
                    URLDecoder.decode(utf8Match.groupValues[1].trim('"', ' '), "UTF-8")
                }.getOrNull()
                if (!decoded.isNullOrBlank()) return decoded
            }

            val normalMatch = Regex("filename=\"?([^\";]+)\"?", RegexOption.IGNORE_CASE)
                .find(contentDisposition)
            if (normalMatch != null) {
                val name = normalMatch.groupValues[1].trim()
                if (name.isNotBlank()) return name
            }
        }

        val cleanPath = resolvedUrl.substringBefore('?').substringBefore('#')
        val lastSegment = cleanPath.substringAfterLast('/', "")
        val decodedSegment = runCatching { URLDecoder.decode(lastSegment, "UTF-8") }
            .getOrDefault(lastSegment)
            .trim()

        if (decodedSegment.isNotBlank() && decodedSegment.contains('.')) {
            return decodedSegment
        }

        // Önce MediaFileDetector'un genişletilmiş (.wav, .ogg, .flac, .opus, .mkv, .mov, .webm, .avi, .wmv, .ts, .fmp4, .m4s) tablosuna bak
        val ext = MediaFileDetector.resolveExtensionFromMime(mimeType)
            ?: mimeType?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            ?: "bin"

        val base = decodedSegment.ifBlank { "indirilen_${System.currentTimeMillis()}" }
        return "$base.$ext"
    }

    private class SingleStreamFallbackException : Exception("Sunucu çoklu parça yerine tek akış döndürdü")

    private data class TaskExecutionMeta(
        val effectiveUrl: String,
        val fileName: String,
        val mimeType: String?,
        val totalBytes: Long,
        val supportsRange: Boolean,
        val tempFile: File
    )

    companion object {
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    }
}

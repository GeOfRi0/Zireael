package com.example.data.engine

import com.example.data.model.ChromeHeaderConfig
import com.example.data.model.ExtractedMedia
import com.example.data.model.FileCategory
import com.example.data.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Custom OkHttp Downloader bridging OkHttpProvider to NewPipeExtractor.
 */
class NewPipeOkHttpDownloader(
    private val client: OkHttpClient = OkHttpProvider.client
) : Downloader() {

    @Throws(IOException::class)
    override fun execute(request: Request): Response {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend = request.dataToSend()

        val requestBody = if (dataToSend != null) {
            val contentType = headers["Content-Type"]?.firstOrNull() ?: "application/json; charset=utf-8"
            dataToSend.toRequestBody(contentType.toMediaTypeOrNull())
        } else null

        val okRequestBuilder = okhttp3.Request.Builder()
            .url(url)
            .method(httpMethod, requestBody)

        for ((key, values) in headers) {
            for (value in values) {
                okRequestBuilder.addHeader(key, value)
            }
        }

        val okResponse = client.newCall(okRequestBuilder.build()).execute()
        val responseBody = okResponse.body?.string().orEmpty()
        val responseCode = okResponse.code
        val responseMessage = okResponse.message
        val latestUrl = okResponse.request.url.toString()
        val responseHeaders = okResponse.headers.toMultimap()

        return Response(responseCode, responseMessage, responseHeaders, responseBody, latestUrl)
    }
}

/**
 * Data structures for parsed stream options.
 */
data class VideoStreamOption(
    val url: String,
    val resolution: String,
    val format: String,
    val quality: String,
    val isVideoOnly: Boolean,
    val codec: String,
    val bitrate: Int
)

data class AudioStreamOption(
    val url: String,
    val format: String,
    val averageBitrate: Int,
    val codec: String
)

data class ExtractedMediaBundle(
    val serviceName: String,
    val url: String,
    val title: String,
    val uploader: String,
    val durationSeconds: Long,
    val thumbnailUrl: String?,
    val videoStreams: List<VideoStreamOption>,
    val audioStreams: List<AudioStreamOption>
)

/**
 * Manager coordinating extraction of media streams across supported platforms
 * (YouTube, SoundCloud, PeerTube, Bandcamp, etc.) via NewPipeExtractor.
 */
class ExtractorManager(
    private val downloader: Downloader = NewPipeOkHttpDownloader()
) {

    companion object {
        private val isInitialized = AtomicBoolean(false)

        fun initialize(downloader: Downloader = NewPipeOkHttpDownloader()) {
            if (isInitialized.compareAndSet(false, true)) {
                NewPipe.init(downloader)
            }
        }
    }

    init {
        initialize(downloader)
    }

    /**
     * Checks if the given URL corresponds to a streaming service supported by NewPipeExtractor.
     */
    fun isSupported(url: String): Boolean {
        return try {
            NewPipe.getServiceByUrl(url) != null
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Extracts streaming information, video resolutions, and audio tracks for the URL.
     */
    suspend fun extractStreams(url: String): Result<ExtractedMediaBundle> = withContext(Dispatchers.IO) {
        try {
            initialize(downloader)
            val service = NewPipe.getServiceByUrl(url)
                ?: return@withContext Result.failure(
                    IllegalArgumentException("Desteklenmeyen platform veya geçersiz URL: $url")
                )

            val streamInfo = StreamInfo.getInfo(service, url)

            val videoList = mutableListOf<VideoStreamOption>()

            // 1. Combined audio+video streams
            streamInfo.videoStreams?.forEach { vs ->
                val streamUrl = vs.url
                if (!streamUrl.isNullOrBlank()) {
                    videoList.add(
                        VideoStreamOption(
                            url = streamUrl,
                            resolution = vs.resolution ?: "${vs.height}p",
                            format = vs.format?.name ?: "mp4",
                            quality = vs.quality ?: vs.resolution ?: "HD",
                            isVideoOnly = false,
                            codec = vs.codec.orEmpty(),
                            bitrate = vs.bitrate
                        )
                    )
                }
            }

            // 2. High-res video-only streams (e.g. 1080p, 1440p, 4K)
            streamInfo.videoOnlyStreams?.forEach { vs ->
                val streamUrl = vs.url
                if (!streamUrl.isNullOrBlank()) {
                    videoList.add(
                        VideoStreamOption(
                            url = streamUrl,
                            resolution = vs.resolution ?: "${vs.height}p",
                            format = vs.format?.name ?: "mp4",
                            quality = "${vs.resolution ?: "${vs.height}p"} (Sadece Video - Mux Gerekir)",
                            isVideoOnly = true,
                            codec = vs.codec.orEmpty(),
                            bitrate = vs.bitrate
                        )
                    )
                }
            }

            // 3. Audio streams
            val audioList = mutableListOf<AudioStreamOption>()
            streamInfo.audioStreams?.forEach { asStream ->
                val streamUrl = asStream.url
                if (!streamUrl.isNullOrBlank()) {
                    audioList.add(
                        AudioStreamOption(
                            url = streamUrl,
                            format = asStream.format?.name ?: "m4a",
                            averageBitrate = asStream.averageBitrate,
                            codec = asStream.codec.orEmpty()
                        )
                    )
                }
            }

            val bestThumbnail = streamInfo.thumbnails?.maxByOrNull { it.height }?.url

            val bundle = ExtractedMediaBundle(
                serviceName = service.serviceInfo.name,
                url = url,
                title = streamInfo.name.orEmpty(),
                uploader = streamInfo.uploaderName.orEmpty(),
                durationSeconds = streamInfo.duration,
                thumbnailUrl = bestThumbnail,
                videoStreams = videoList,
                audioStreams = audioList
            )

            Result.success(bundle)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Converts the extracted streams into ExtractedMedia items ready for Zireael download engine.
     */
    fun toExtractedMediaList(
        bundle: ExtractedMediaBundle,
        headerConfig: ChromeHeaderConfig? = null
    ): List<ExtractedMedia> {
        val results = mutableListOf<ExtractedMedia>()

        // Video options
        for (v in bundle.videoStreams) {
            val suffix = if (v.isVideoOnly) " [Video]" else ""
            results.add(
                ExtractedMedia(
                    url = v.url,
                    title = "${bundle.title} - ${v.resolution} (${v.format})$suffix",
                    category = FileCategory.MEDIA,
                    mimeType = "video/${v.format.lowercase()}",
                    thumbnailUrl = bundle.thumbnailUrl,
                    headerConfig = headerConfig,
                    mediaType = MediaType.VIDEO
                )
            )
        }

        // Audio options
        for (a in bundle.audioStreams) {
            results.add(
                ExtractedMedia(
                    url = a.url,
                    title = "${bundle.title} - Ses (${a.averageBitrate}kbps ${a.format})",
                    category = FileCategory.MEDIA,
                    mimeType = "audio/${a.format.lowercase()}",
                    thumbnailUrl = bundle.thumbnailUrl,
                    headerConfig = headerConfig,
                    mediaType = MediaType.AUDIO
                )
            )
        }

        // Thumbnail image
        if (!bundle.thumbnailUrl.isNullOrBlank()) {
            results.add(
                ExtractedMedia(
                    url = bundle.thumbnailUrl,
                    title = "${bundle.title} - Küçük Resim",
                    category = FileCategory.MEDIA,
                    mimeType = "image/jpeg",
                    thumbnailUrl = bundle.thumbnailUrl,
                    headerConfig = headerConfig,
                    mediaType = MediaType.IMAGE
                )
            )
        }

        return results
    }
}

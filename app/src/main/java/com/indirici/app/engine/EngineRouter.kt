package com.indirici.app.engine

import com.indirici.app.domain.engine.DownloadEngine
import com.indirici.app.domain.exception.DrmProtectedException
import com.indirici.app.domain.model.EngineType
import java.net.URI

/**
 * URL'ye veya görev türüne göre uygun indirme motorunu (DirectEngine, MediaEngine veya FfmpegHlsEngine) seçer.
 */
class EngineRouter(
    val directEngine: DirectEngine,
    val mediaEngine: DownloadEngine,
    val ffmpegHlsEngine: DownloadEngine
) {

    private val knownDrmStreamingHosts = setOf(
        "netflix.com",
        "disneyplus.com",
        "primevideo.com",
        "open.spotify.com",
        "music.apple.com",
        "max.com",
        "hbomax.com",
        "blutv.com",
        "exxen.com",
        "gain.tv"
    )

    private val knownMediaSiteHosts = setOf(
        "youtube.com",
        "youtu.be",
        "m.youtube.com",
        "music.youtube.com",
        "vimeo.com",
        "dailymotion.com",
        "tiktok.com",
        "instagram.com",
        "twitter.com",
        "x.com",
        "soundcloud.com",
        "twitch.tv",
        "clips.twitch.tv",
        "facebook.com",
        "fb.watch",
        "reddit.com",
        "v.redd.it",
        "bilibili.com",
        "bandcamp.com",
        "vk.com"
    )

    fun detectEngineForUrl(url: String): DownloadEngine {
        DrmDetector.assertNotDrm(url = url)

        val host = extractHost(url)
        if (host != null && knownDrmStreamingHosts.any { host == it || host.endsWith(".$it") }) {
            throw DrmProtectedException()
        }

        // Doğrudan .m3u8 HLS manifest linkleri FFmpegKit HLS motoruna yönlendirilir
        if (MediaFileDetector.isHlsManifestUrl(url)) {
            return ffmpegHlsEngine
        }

        val category = MediaFileDetector.classifyUrl(url)
        if (category == DetectedMediaCategory.DIRECT_AUDIO ||
            category == DetectedMediaCategory.DIRECT_VIDEO ||
            category == DetectedMediaCategory.STREAM_SEGMENT
        ) {
            return directEngine
        }

        if (host != null && knownMediaSiteHosts.any { host == it || host.endsWith(".$it") }) {
            return mediaEngine
        }

        return directEngine
    }

    fun getEngineByType(engineType: EngineType): DownloadEngine {
        return when (engineType) {
            EngineType.DIRECT -> directEngine
            EngineType.MEDIA -> mediaEngine
            EngineType.FFMPEG_HLS -> ffmpegHlsEngine
        }
    }

    private fun extractHost(url: String): String? {
        return try {
            URI(url.trim()).host?.lowercase()?.removePrefix("www.")
        } catch (_: Exception) {
            null
        }
    }
}

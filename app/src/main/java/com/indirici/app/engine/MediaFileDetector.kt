package com.indirici.app.engine

import java.net.URI

enum class DetectedMediaCategory {
    DIRECT_AUDIO,
    DIRECT_VIDEO,
    STREAM_SEGMENT,
    HLS_MANIFEST,
    DASH_MANIFEST,
    UNKNOWN
}

data class HlsStreamInspection(
    val isHls: Boolean,
    val hasH265Hevc: Boolean,
    val isAacAudioStream: Boolean,
    val usesFmp4M4sSegments: Boolean,
    val usesMpegTsSegments: Boolean
)

/**
 * Doğrudan linklerde, HTTP başlıklarında, HLS (.m3u8) içeriklerinde ve web sayfalarında:
 * - Ses: .wav, .ogg, .flac, .opus, .mp3, .m4a, .aac
 * - Video: .mp4, .mkv, .mov, .webm, .avi, .wmv, .m4v
 * - Akış / Parça (Stream/Segment): H.265 HLS, AAC HLS, .m3u8, .ts, .m2ts, .fmp4, .m4s
 * türlerini algılar ve uygun dosya uzantısı / MIME tipi eşlemesini sağlar.
 */
object MediaFileDetector {

    val supportedAudioExtensions = setOf(
        "mp3", "m4a", "aac", "wav", "ogg", "oga", "flac", "opus"
    )

    val supportedVideoExtensions = setOf(
        "mp4", "mkv", "mov", "webm", "avi", "wmv", "m4v"
    )

    val supportedSegmentExtensions = setOf(
        "ts", "m2ts", "mts", "fmp4", "m4s"
    )

    val supportedHlsExtensions = setOf(
        "m3u8", "m3u"
    )

    val supportedDashExtensions = setOf(
        "mpd"
    )

    private val mimeToExtensionMap = mapOf(
        // Ses MIME tipleri
        "audio/wav" to "wav",
        "audio/x-wav" to "wav",
        "audio/wave" to "wav",
        "audio/vnd.wave" to "wav",
        "audio/ogg" to "ogg",
        "application/ogg" to "ogg",
        "audio/flac" to "flac",
        "audio/x-flac" to "flac",
        "audio/opus" to "opus",
        "audio/aac" to "aac",
        "audio/aacp" to "aac",
        "audio/mp4" to "m4a",
        "audio/x-m4a" to "m4a",
        "audio/mpeg" to "mp3",
        "audio/mp3" to "mp3",

        // Video MIME tipleri
        "video/mp4" to "mp4",
        "video/x-matroska" to "mkv",
        "video/matroska" to "mkv",
        "video/quicktime" to "mov",
        "video/webm" to "webm",
        "audio/webm" to "webm",
        "video/x-msvideo" to "avi",
        "video/avi" to "avi",
        "video/msvideo" to "avi",
        "video/x-ms-wmv" to "wmv",
        "video/x-m4v" to "m4v",

        // HLS / TS / fMP4 / .m4s MIME tipleri
        "application/vnd.apple.mpegurl" to "m3u8",
        "application/x-mpegurl" to "m3u8",
        "audio/mpegurl" to "m3u8",
        "audio/x-mpegurl" to "m3u8",
        "video/mp2t" to "ts",
        "video/vnd.dlna.mpeg-tts" to "ts",
        "video/iso.segment" to "m4s",
        "application/mp4" to "fmp4",
        "application/dash+xml" to "mpd"
    )

    private val extensionToMimeMap = mapOf(
        "wav" to "audio/wav",
        "ogg" to "audio/ogg",
        "oga" to "audio/ogg",
        "flac" to "audio/flac",
        "opus" to "audio/opus",
        "aac" to "audio/aac",
        "m4a" to "audio/mp4",
        "mp3" to "audio/mpeg",
        "mp4" to "video/mp4",
        "mkv" to "video/x-matroska",
        "mov" to "video/quicktime",
        "webm" to "video/webm",
        "avi" to "video/x-msvideo",
        "wmv" to "video/x-ms-wmv",
        "m4v" to "video/x-m4v",
        "ts" to "video/mp2t",
        "m2ts" to "video/mp2t",
        "mts" to "video/mp2t",
        "fmp4" to "video/mp4",
        "m4s" to "video/iso.segment",
        "m3u8" to "application/vnd.apple.mpegurl",
        "mpd" to "application/dash+xml"
    )

    /**
     * URL ve isteğe bağlı MIME tipine bakarak medya kategorisini belirler.
     */
    fun classifyUrl(url: String, mimeType: String? = null): DetectedMediaCategory {
        val ext = extractExtensionFromUrl(url) ?: resolveExtensionFromMime(mimeType)
        val cleanMime = mimeType?.substringBefore(';')?.trim()?.lowercase()

        if (ext in supportedHlsExtensions ||
            cleanMime == "application/vnd.apple.mpegurl" ||
            cleanMime == "application/x-mpegurl" ||
            cleanMime == "audio/mpegurl" ||
            cleanMime == "audio/x-mpegurl"
        ) {
            return DetectedMediaCategory.HLS_MANIFEST
        }

        if (ext in supportedDashExtensions || cleanMime == "application/dash+xml") {
            return DetectedMediaCategory.DASH_MANIFEST
        }

        if (ext in supportedAudioExtensions || cleanMime?.startsWith("audio/") == true) {
            return DetectedMediaCategory.DIRECT_AUDIO
        }

        if (ext in supportedSegmentExtensions || cleanMime == "video/mp2t" || cleanMime == "video/iso.segment") {
            return DetectedMediaCategory.STREAM_SEGMENT
        }

        if (ext in supportedVideoExtensions || cleanMime?.startsWith("video/") == true) {
            return DetectedMediaCategory.DIRECT_VIDEO
        }

        return DetectedMediaCategory.UNKNOWN
    }

    /**
     * URL'nin bir HLS (.m3u8) akışı olup olmadığını kontrol eder.
     * HLS akışları (.ts, fMP4 .m4s, H.265 HLS, AAC HLS) doğrudan tek dosya indirme yerine
     * MediaEngine (yt-dlp + ffmpeg) tarafından tüm parçaları birleştirilerek indirilmelidir.
     */
    fun isHlsManifestUrl(url: String, mimeType: String? = null): Boolean {
        return classifyUrl(url, mimeType) == DetectedMediaCategory.HLS_MANIFEST
    }

    /**
     * URL'nin bir DASH (.mpd) akışı olup olmadığını kontrol eder.
     */
    fun isDashManifestUrl(url: String, mimeType: String? = null): Boolean {
        return classifyUrl(url, mimeType) == DetectedMediaCategory.DASH_MANIFEST
    }

    /**
     * URL yolundan bilinen bir medya veya dosya uzantısını çıkarır.
     */
    fun extractExtensionFromUrl(url: String): String? {
        val cleanPath = url.trim()
            .substringBefore('#')
            .substringBefore('?')
            .lowercase()

        val lastSegment = cleanPath.substringAfterLast('/', "")
        if (!lastSegment.contains('.')) return null
        val ext = lastSegment.substringAfterLast('.', "").trim()
        return ext.takeIf { it.length in 2..5 && it.all { ch -> ch.isLetterOrDigit() } }
    }

    fun resolveExtensionFromMime(mimeType: String?): String? {
        if (mimeType.isNullOrBlank()) return null
        val normalized = mimeType.substringBefore(';').trim().lowercase()
        return mimeToExtensionMap[normalized]
    }

    fun resolveMimeFromExtension(extension: String?): String? {
        if (extension.isNullOrBlank()) return null
        return extensionToMimeMap[extension.trim().trimStart('.').lowercase()]
    }

    /**
     * Bir HLS (.m3u8) manifest içeriğini inceleyerek H.265 (HEVC), AAC HLS,
     * fMP4 (.m4s / init.mp4) veya MPEG-TS (.ts) yapısını tespit eder.
     */
    fun inspectHlsManifest(url: String, manifestContent: String): HlsStreamInspection {
        val lower = manifestContent.lowercase()
        val isHls = lower.contains("#extm3u")

        val hasH265 = lower.contains("hvc1") ||
            lower.contains("hev1") ||
            lower.contains("hevc") ||
            lower.contains("h265") ||
            lower.contains("h.265")

        val hasVideoCodec = hasH265 ||
            lower.contains("avc1") ||
            lower.contains("vp09") ||
            lower.contains("av01") ||
            lower.contains("resolution=")

        val hasAacIndicator = lower.contains("mp4a.40") ||
            lower.contains(".aac") ||
            lower.contains("type=audio")

        val isAacAudioStream = hasAacIndicator && !hasVideoCodec

        val usesFmp4 = lower.contains("#ext-x-map") ||
            lower.contains(".m4s") ||
            lower.contains(".fmp4") ||
            lower.contains(".cmfv") ||
            lower.contains(".cmfa")

        val usesTs = lower.contains(".ts") || lower.contains(".m2ts") || lower.contains(".aac")

        return HlsStreamInspection(
            isHls = isHls,
            hasH265Hevc = hasH265,
            isAacAudioStream = isAacAudioStream,
            usesFmp4M4sSegments = usesFmp4,
            usesMpegTsSegments = usesTs
        )
    }

    /**
     * Bir web sayfası kaynağında (HTML / JSON / script) gömülü olan
     * doğrudan ses (.wav, .ogg, .flac, .opus, .mp3, .m4a, .aac),
     * video (.mkv, .mov, .webm, .avi, .wmv, .mp4) ve
     * akış (.m3u8, .ts, .fmp4, .m4s) linklerini yakalar.
     */
    fun extractMediaLinksFromContent(content: String, baseUrl: String? = null): List<String> {
        if (content.isBlank()) return emptyList()
        val normalizedContent = content.replace("\\/", "/")
        val results = LinkedHashSet<String>()

        // 1. Tam http/https medya linklerini yakala
        ABSOLUTE_MEDIA_URL_REGEX.findAll(normalizedContent).forEach { match ->
            val rawUrl = match.value.trim('"', '\'', ' ', '\\')
            results.add(rawUrl)
        }

        // 2. Bağıl (relative) medya yollarını baseUrl ile birleştirerek yakala
        if (!baseUrl.isNullOrBlank()) {
            val baseUri = runCatching { URI(baseUrl) }.getOrNull()
            if (baseUri != null) {
                RELATIVE_MEDIA_URL_REGEX.findAll(normalizedContent).forEach { match ->
                    val rel = match.groupValues[1].trim()
                    if (!rel.startsWith("http://") && !rel.startsWith("https://")) {
                        runCatching {
                            val resolved = baseUri.resolve(rel).toString()
                            if (resolved.startsWith("http://") || resolved.startsWith("https://")) {
                                results.add(resolved)
                            }
                        }
                    }
                }
            }
        }

        return results.toList()
    }

    private const val EXT_PATTERN =
        "(?:wav|ogg|oga|flac|opus|mp3|m4a|aac|mp4|mkv|mov|webm|avi|wmv|m4v|m3u8|mpd|ts|m2ts|mts|fmp4|m4s)"

    private val ABSOLUTE_MEDIA_URL_REGEX = Regex(
        """https?:\\?/\\?/[^\s"'<>\\]+?\.$EXT_PATTERN(?:\?[^\s"'<>\\]*)?""",
        RegexOption.IGNORE_CASE
    )

    private val RELATIVE_MEDIA_URL_REGEX = Regex(
        """["']((?:/|\./|\.\./)[^"'<>\\\s]+?\.$EXT_PATTERN(?:\?[^"'<>\\\s]*)?)["']""",
        RegexOption.IGNORE_CASE
    )
}

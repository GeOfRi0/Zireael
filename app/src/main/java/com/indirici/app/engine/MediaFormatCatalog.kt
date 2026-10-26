package com.indirici.app.engine

import com.indirici.app.domain.model.MediaFormatOption

/**
 * V2 (Video/Ses Siteleri ve HLS/fMP4/TS Akışları) için kalite ve kapsayıcı (container) seçeneklerini yönetir:
 * - Video: 1080p/720p/480p MP4, H.265 (HEVC), MKV, WebM, MOV, AVI, WMV, MPEG-TS (.ts), fMP4 (.m4s/.mp4)
 * - Ses: MP3, M4A, AAC (HLS), WAV, OGG, FLAC, OPUS
 */
object MediaFormatCatalog {

    // Video Format Kimlikleri
    const val FORMAT_VIDEO_1080P = "video_1080p"
    const val FORMAT_VIDEO_720P = "video_720p"
    const val FORMAT_VIDEO_480P = "video_480p"
    const val FORMAT_VIDEO_H265 = "video_h265"
    const val FORMAT_VIDEO_MKV = "video_mkv"
    const val FORMAT_VIDEO_WEBM = "video_webm"
    const val FORMAT_VIDEO_MOV = "video_mov"
    const val FORMAT_VIDEO_AVI = "video_avi"
    const val FORMAT_VIDEO_WMV = "video_wmv"
    const val FORMAT_VIDEO_TS = "video_ts"
    const val FORMAT_VIDEO_FMP4 = "video_fmp4"

    // Ses Format Kimlikleri
    const val FORMAT_AUDIO_MP3 = "audio_mp3"
    const val FORMAT_AUDIO_M4A = "audio_m4a"
    const val FORMAT_AUDIO_AAC = "audio_aac"
    const val FORMAT_AUDIO_WAV = "audio_wav"
    const val FORMAT_AUDIO_OGG = "audio_ogg"
    const val FORMAT_AUDIO_FLAC = "audio_flac"
    const val FORMAT_AUDIO_OPUS = "audio_opus"

    const val DEFAULT_FORMAT_ID = FORMAT_VIDEO_720P

    data class RawStreamSummary(
        val height: Int,
        val vcodec: String?,
        val acodec: String?,
        val ext: String?,
        val fileSizeBytes: Long,
        val protocol: String? = null
    )

    /**
     * yt-dlp'den veya HLS manifestinden gelen ham akış bilgilerine göre
     * tüm video (.mp4, .mkv, .webm, .mov, .avi, .wmv, H.265, .ts, fMP4) ve
     * ses (.mp3, .m4a, .aac, .wav, .ogg, .flac, .opus) seçeneklerini oluşturur.
     */
    fun buildFormatOptions(rawStreams: List<RawStreamSummary> = emptyList()): List<MediaFormatOption> {
        val bestAudioSize = rawStreams
            .filter { (it.vcodec == null || it.vcodec == "none") && it.fileSizeBytes > 0L }
            .maxOfOrNull { it.fileSizeBytes } ?: 0L

        val bestOverallVideoSize = rawStreams
            .filter { it.vcodec != null && it.vcodec != "none" && it.fileSizeBytes > 0L }
            .maxOfOrNull { stream ->
                val hasAudio = stream.acodec != null && stream.acodec != "none"
                if (hasAudio) stream.fileSizeBytes else stream.fileSizeBytes + bestAudioSize
            } ?: -1L

        fun estimateVideoSize(targetHeight: Int): Long {
            val matchingVideos = rawStreams.filter {
                it.height in (targetHeight - 120)..targetHeight &&
                    it.vcodec != null &&
                    it.vcodec != "none" &&
                    it.fileSizeBytes > 0L
            }
            val bestVideoStream = matchingVideos.maxByOrNull { it.fileSizeBytes } ?: return -1L
            val hasEmbeddedAudio = bestVideoStream.acodec != null && bestVideoStream.acodec != "none"
            return if (hasEmbeddedAudio) {
                bestVideoStream.fileSizeBytes
            } else {
                bestVideoStream.fileSizeBytes + bestAudioSize
            }
        }

        val h265Size = rawStreams
            .filter {
                val vc = it.vcodec?.lowercase().orEmpty()
                (vc.contains("hev") || vc.contains("hvc") || vc.contains("265")) && it.fileSizeBytes > 0L
            }
            .maxOfOrNull {
                val hasAudio = it.acodec != null && it.acodec != "none"
                if (hasAudio) it.fileSizeBytes else it.fileSizeBytes + bestAudioSize
            } ?: bestOverallVideoSize

        val audioBaseSize = if (bestAudioSize > 0L) bestAudioSize else -1L

        return listOf(
            // --- Ana Çözünürlük Seçenekleri (MP4) ---
            MediaFormatOption(
                formatId = FORMAT_VIDEO_1080P,
                label = "1080p Full HD (MP4)",
                extension = "mp4",
                isAudioOnly = false,
                estimatedSizeBytes = estimateVideoSize(1080)
            ),
            MediaFormatOption(
                formatId = FORMAT_VIDEO_720P,
                label = "720p HD (MP4)",
                extension = "mp4",
                isAudioOnly = false,
                estimatedSizeBytes = estimateVideoSize(720)
            ),
            MediaFormatOption(
                formatId = FORMAT_VIDEO_480P,
                label = "480p SD (MP4)",
                extension = "mp4",
                isAudioOnly = false,
                estimatedSizeBytes = estimateVideoSize(480)
            ),

            // --- Kodek, Kapsayıcı ve Akış (H.265 / MKV / WebM / MOV / AVI / WMV / TS / fMP4) Seçenekleri ---
            MediaFormatOption(
                formatId = FORMAT_VIDEO_H265,
                label = "H.265 / HEVC Video (HLS / MP4)",
                extension = "mp4",
                isAudioOnly = false,
                estimatedSizeBytes = h265Size
            ),
            MediaFormatOption(
                formatId = FORMAT_VIDEO_MKV,
                label = "MKV Video (En İyi Kalite • .mkv)",
                extension = "mkv",
                isAudioOnly = false,
                estimatedSizeBytes = bestOverallVideoSize
            ),
            MediaFormatOption(
                formatId = FORMAT_VIDEO_WEBM,
                label = "WebM Video (.webm)",
                extension = "webm",
                isAudioOnly = false,
                estimatedSizeBytes = bestOverallVideoSize
            ),
            MediaFormatOption(
                formatId = FORMAT_VIDEO_MOV,
                label = "MOV Video (.mov)",
                extension = "mov",
                isAudioOnly = false,
                estimatedSizeBytes = bestOverallVideoSize
            ),
            MediaFormatOption(
                formatId = FORMAT_VIDEO_AVI,
                label = "AVI Video (.avi)",
                extension = "avi",
                isAudioOnly = false,
                estimatedSizeBytes = bestOverallVideoSize
            ),
            MediaFormatOption(
                formatId = FORMAT_VIDEO_WMV,
                label = "WMV Video (.wmv)",
                extension = "wmv",
                isAudioOnly = false,
                estimatedSizeBytes = bestOverallVideoSize
            ),
            MediaFormatOption(
                formatId = FORMAT_VIDEO_TS,
                label = "MPEG-TS Akışı (HLS • .ts)",
                extension = "ts",
                isAudioOnly = false,
                estimatedSizeBytes = bestOverallVideoSize
            ),
            MediaFormatOption(
                formatId = FORMAT_VIDEO_FMP4,
                label = "fMP4 / M4S Akışı (.mp4)",
                extension = "mp4",
                isAudioOnly = false,
                estimatedSizeBytes = bestOverallVideoSize
            ),

            // --- Ses Seçenekleri (MP3, M4A, AAC, WAV, OGG, FLAC, OPUS) ---
            MediaFormatOption(
                formatId = FORMAT_AUDIO_MP3,
                label = "MP3 (Yalnızca Ses • .mp3)",
                extension = "mp3",
                isAudioOnly = true,
                estimatedSizeBytes = audioBaseSize
            ),
            MediaFormatOption(
                formatId = FORMAT_AUDIO_M4A,
                label = "M4A (Yalnızca Ses • .m4a)",
                extension = "m4a",
                isAudioOnly = true,
                estimatedSizeBytes = audioBaseSize
            ),
            MediaFormatOption(
                formatId = FORMAT_AUDIO_AAC,
                label = "AAC (HLS Ses Akışı • .aac)",
                extension = "aac",
                isAudioOnly = true,
                estimatedSizeBytes = audioBaseSize
            ),
            MediaFormatOption(
                formatId = FORMAT_AUDIO_WAV,
                label = "WAV (Kayıpsız PCM Ses • .wav)",
                extension = "wav",
                isAudioOnly = true,
                estimatedSizeBytes = audioBaseSize
            ),
            MediaFormatOption(
                formatId = FORMAT_AUDIO_OGG,
                label = "OGG (Vorbis Ses • .ogg)",
                extension = "ogg",
                isAudioOnly = true,
                estimatedSizeBytes = audioBaseSize
            ),
            MediaFormatOption(
                formatId = FORMAT_AUDIO_FLAC,
                label = "FLAC (Kayıpsız Ses • .flac)",
                extension = "flac",
                isAudioOnly = true,
                estimatedSizeBytes = audioBaseSize
            ),
            MediaFormatOption(
                formatId = FORMAT_AUDIO_OPUS,
                label = "OPUS (Yüksek Verimli Ses • .opus)",
                extension = "opus",
                isAudioOnly = true,
                estimatedSizeBytes = audioBaseSize
            )
        )
    }

    fun getExtensionForFormatId(formatId: String?): String {
        return when (formatId) {
            FORMAT_AUDIO_MP3 -> "mp3"
            FORMAT_AUDIO_M4A -> "m4a"
            FORMAT_AUDIO_AAC -> "aac"
            FORMAT_AUDIO_WAV -> "wav"
            FORMAT_AUDIO_OGG -> "ogg"
            FORMAT_AUDIO_FLAC -> "flac"
            FORMAT_AUDIO_OPUS -> "opus"
            FORMAT_VIDEO_MKV -> "mkv"
            FORMAT_VIDEO_WEBM -> "webm"
            FORMAT_VIDEO_MOV -> "mov"
            FORMAT_VIDEO_AVI -> "avi"
            FORMAT_VIDEO_WMV -> "wmv"
            FORMAT_VIDEO_TS -> "ts"
            FORMAT_VIDEO_FMP4,
            FORMAT_VIDEO_H265,
            FORMAT_VIDEO_1080P,
            FORMAT_VIDEO_720P,
            FORMAT_VIDEO_480P -> "mp4"
            else -> "mp4"
        }
    }

    fun getMimeTypeForFormatId(formatId: String?): String {
        val ext = getExtensionForFormatId(formatId)
        return MediaFileDetector.resolveMimeFromExtension(ext) ?: "video/mp4"
    }

    fun getShortBadgeLabel(formatId: String?): String? {
        return when (formatId) {
            FORMAT_VIDEO_1080P -> "1080p"
            FORMAT_VIDEO_720P -> "720p"
            FORMAT_VIDEO_480P -> "480p"
            FORMAT_VIDEO_H265 -> "H.265"
            FORMAT_VIDEO_MKV -> "MKV"
            FORMAT_VIDEO_WEBM -> "WebM"
            FORMAT_VIDEO_MOV -> "MOV"
            FORMAT_VIDEO_AVI -> "AVI"
            FORMAT_VIDEO_WMV -> "WMV"
            FORMAT_VIDEO_TS -> "TS"
            FORMAT_VIDEO_FMP4 -> "fMP4"
            FORMAT_AUDIO_MP3 -> "MP3"
            FORMAT_AUDIO_M4A -> "M4A"
            FORMAT_AUDIO_AAC -> "AAC"
            FORMAT_AUDIO_WAV -> "WAV"
            FORMAT_AUDIO_OGG -> "OGG"
            FORMAT_AUDIO_FLAC -> "FLAC"
            FORMAT_AUDIO_OPUS -> "OPUS"
            else -> formatId
        }
    }

    private val knownStripExtensions = listOf(
        ".mp4", ".mkv", ".mov", ".webm", ".avi", ".wmv", ".m4v",
        ".ts", ".m2ts", ".mts", ".fmp4", ".m4s", ".m3u8",
        ".mp3", ".m4a", ".aac", ".wav", ".ogg", ".oga", ".flac", ".opus"
    )

    fun sanitizeTitleToFileName(rawTitle: String?, formatId: String?): String {
        val ext = getExtensionForFormatId(formatId)
        var cleanedBase = (rawTitle ?: "medya_indirici")
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()

        for (suffix in knownStripExtensions) {
            if (cleanedBase.endsWith(suffix, ignoreCase = true)) {
                cleanedBase = cleanedBase.dropLast(suffix.length)
                break
            }
        }

        cleanedBase = cleanedBase
            .trimEnd { it == '.' || it.isWhitespace() }
            .take(110)
            .trimEnd { it == '.' || it.isWhitespace() }
            .ifBlank { "medya_${System.currentTimeMillis()}" }

        return "${cleanedBase.ifBlank { "medya" }}.$ext"
    }
}

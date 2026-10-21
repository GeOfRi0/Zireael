package com.indirici.app.domain.model

/**
 * V2'de video/ses sitelerinden çözümlenen kalite seçeneklerini temsil eder.
 */
data class MediaFormatOption(
    val formatId: String,
    val label: String,        // Örn: "1080p MP4", "720p MP4", "MP3 192kbps"
    val extension: String,    // Örn: "mp4", "mp3", "m4a"
    val isAudioOnly: Boolean,
    val estimatedSizeBytes: Long = -1L
)

/**
 * Link analiz edildiğinde (V1 HEAD/Range kontrolü veya V2 yt-dlp çözümü) dönen üst veri.
 */
data class UrlMetadata(
    val originalUrl: String,
    val resolvedUrl: String,
    val fileName: String,
    val mimeType: String?,
    val totalBytes: Long,
    val supportsRange: Boolean,
    val engineType: EngineType,
    val availableFormats: List<MediaFormatOption> = emptyList() // V2 için hazır
)

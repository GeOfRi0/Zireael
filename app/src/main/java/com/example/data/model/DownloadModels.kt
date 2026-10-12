package com.example.data.model

enum class DownloadStatus(val titleTr: String) {
    QUEUED("Sırada"),
    CONNECTING("Bağlanıyor..."),
    DOWNLOADING("İndiriliyor"),
    PAUSED("Duraklatıldı"),
    COMPLETED("Tamamlandı"),
    FAILED("Hata"),
    CANCELLED("İptal Edildi")
}

enum class FileCategory(val label: String) {
    ALL("Tümü"),
    APKS("Uygulamalar (APK)"),
    MEDIA("Medya (MP4, MP3)"),
    DOCUMENTS("Belgeler"),
    ARCHIVES("Arşivler"),
    OTHER("Diğer");

    companion object {
        fun detect(fileName: String, mimeType: String): FileCategory {
            val lowerName = fileName.lowercase()
            val lowerMime = mimeType.lowercase()

            return when {
                // 1. Applications (APK, XAPK, APKS)
                lowerName.endsWith(".apk") || lowerName.endsWith(".xapk") || lowerName.endsWith(".apks") ||
                    lowerMime.contains("android.package-archive") || lowerMime.contains("application/vnd.android.package-archive") -> APKS

                // 2. Media (MP4, MP3, Video & Audio, HLS, DASH)
                lowerMime.startsWith("video/") || lowerMime.startsWith("audio/") ||
                    lowerMime.contains("mpegurl") || lowerMime.contains("dash+xml") ||
                    lowerName.endsWith(".m3u8") || lowerName.endsWith(".mpd") ||
                    lowerName.endsWith(".ts") || lowerName.endsWith(".m4s") ||
                    lowerName.endsWith(".mp4") || lowerName.endsWith(".mkv") || lowerName.endsWith(".webm") ||
                    lowerName.endsWith(".avi") || lowerName.endsWith(".mov") || lowerName.endsWith(".flv") ||
                    lowerName.endsWith(".3gp") || lowerName.endsWith(".mp3") || lowerName.endsWith(".wav") ||
                    lowerName.endsWith(".aac") || lowerName.endsWith(".flac") || lowerName.endsWith(".ogg") ||
                    lowerName.endsWith(".m4a") || lowerMime.startsWith("image/") || lowerName.endsWith(".jpg") ||
                    lowerName.endsWith(".jpeg") || lowerName.endsWith(".png") || lowerName.endsWith(".webp") ||
                    lowerName.endsWith(".gif") || lowerName.endsWith(".svg") -> MEDIA

                // 3. Archives & Torrents (ZIP, RAR, 7Z, TAR, GZ, TORRENT)
                lowerName.endsWith(".zip") || lowerName.endsWith(".rar") || lowerName.endsWith(".7z") ||
                    lowerName.endsWith(".tar") || lowerName.endsWith(".gz") || lowerName.endsWith(".bz2") ||
                    lowerName.endsWith(".xz") || lowerName.endsWith(".iso") || lowerName.endsWith(".torrent") ||
                    lowerName.startsWith("magnet:") || lowerName.startsWith("magnet://") ||
                    lowerMime.contains("bittorrent") || lowerMime.contains("zip") || lowerMime.contains("rar") ||
                    lowerMime.contains("tar") || lowerMime.contains("compressed") || lowerMime.contains("7z-compressed") -> ARCHIVES

                // 4. Documents (PDF, DOC, DOCX, XLS, XLSX, PPT, PPTX, TXT, EPUB)
                lowerName.endsWith(".pdf") || lowerName.endsWith(".doc") || lowerName.endsWith(".docx") ||
                    lowerName.endsWith(".xls") || lowerName.endsWith(".xlsx") || lowerName.endsWith(".ppt") ||
                    lowerName.endsWith(".pptx") || lowerName.endsWith(".txt") || lowerName.endsWith(".epub") ||
                    lowerName.endsWith(".csv") || lowerName.endsWith(".rtf") ||
                    lowerMime.contains("pdf") || lowerMime.contains("document") || lowerMime.contains("spreadsheet") ||
                    lowerMime.contains("presentation") || lowerMime.contains("text/plain") -> DOCUMENTS

                else -> OTHER
            }
        }
    }
}

enum class MediaType(val label: String) {
    VIDEO("Video"),
    AUDIO("Ses"),
    IMAGE("Görsel"),
    DOCUMENT("Belge");

    companion object {
        fun detect(fileName: String, mimeType: String = ""): MediaType {
            val lowerMime = mimeType.lowercase()
            val lowerName = fileName.lowercase()

            return when {
                lowerMime.startsWith("video/") ||
                    listOf(".mp4", ".mkv", ".webm", ".m3u8", ".mpd", ".ts", ".m4s", ".m4v", ".mov", ".avi", ".flv", ".3gp").any { lowerName.endsWith(it) } -> VIDEO

                lowerMime.startsWith("audio/") ||
                    listOf(".mp3", ".m4a", ".aac", ".wav", ".ogg", ".flac", ".opus").any { lowerName.endsWith(it) } -> AUDIO

                lowerMime.startsWith("image/") ||
                    listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".ico", ".svg", ".bmp").any { lowerName.endsWith(it) } -> IMAGE

                lowerMime.contains("pdf") || lowerMime.contains("document") || lowerMime.contains("spreadsheet") ||
                    lowerMime.contains("presentation") || lowerMime.contains("text/") ||
                    listOf(".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".txt", ".epub").any { lowerName.endsWith(it) } -> DOCUMENT

                else -> VIDEO
            }
        }
    }
}

data class ExtractedMedia(
    val url: String,
    val title: String,
    val category: FileCategory,
    val mimeType: String,
    val approxSize: Long = -1L,
    val thumbnailUrl: String? = null,
    val isSelected: Boolean = true,
    val headerConfig: ChromeHeaderConfig? = null,
    val mediaType: MediaType = MediaType.detect(title, mimeType)
)

data class SegmentProgress(
    val segmentIndex: Int,
    val startByte: Long,
    val endByte: Long,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val speedBytesPerSec: Long = 0L,
    val isHelper: Boolean = false,
    val helperTargetSegmentIndex: Int? = null
) {
    val progress: Float
        get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}

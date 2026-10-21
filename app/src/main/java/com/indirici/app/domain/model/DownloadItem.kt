package com.indirici.app.domain.model

data class DownloadItem(
    val id: Long,
    val url: String,
    val fileName: String,
    val mimeType: String?,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val speedBytesPerSec: Long,
    val chunkCount: Int,
    val supportsRange: Boolean,
    val status: DownloadStatus,
    val engineType: EngineType,
    val selectedFormatId: String? = null, // V2 için ayrıldı (örn: "1080p", "mp3")
    val errorMessage: String? = null,
    val savedUri: String? = null,
    val createdAt: Long
) {
    val progressPercent: Int
        get() = if (totalBytes > 0) {
            ((downloadedBytes.toDouble() / totalBytes.toDouble()) * 100.0).toInt().coerceIn(0, 100)
        } else {
            0
        }
}

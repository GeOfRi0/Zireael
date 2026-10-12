package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room database entity to persist user download history, including
 * file names, URLs, local paths, categories, and download status.
 */
@Entity(
    tableName = "downloads",
    indices = [
        Index(value = ["status"]),
        Index(value = ["createdAt"]),
        Index(value = ["category"])
    ]
)
data class DownloadEntity(
    @PrimaryKey
    val id: String,
    val url: String,
    val fileName: String,
    val filePath: String,
    val mimeType: String,
    val category: String,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val status: String,
    val speedBytesPerSec: Long = 0L,
    val threadCount: Int = 4,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long = 0L,
    val errorMessage: String? = null,
    val userAgent: String? = null,
    val referer: String? = null,
    val cookies: String? = null,
    val extraHeadersJson: String? = null
)

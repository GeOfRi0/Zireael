package com.indirici.app.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.indirici.app.domain.model.DownloadItem
import com.indirici.app.domain.model.DownloadStatus
import com.indirici.app.domain.model.EngineType

@Entity(tableName = "download_tasks")
data class DownloadTaskEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val url: String,
    val fileName: String,
    val mimeType: String?,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val speedBytesPerSec: Long,
    val chunkCount: Int,
    val supportsRange: Boolean,
    val status: String,     // DownloadStatus.name
    val engineType: String, // EngineType.name
    val selectedFormatId: String? = null,
    val errorMessage: String? = null,
    val tempFilePath: String? = null,
    val savedUri: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toDomain(): DownloadItem {
        return DownloadItem(
            id = id,
            url = url,
            fileName = fileName,
            mimeType = mimeType,
            totalBytes = totalBytes,
            downloadedBytes = downloadedBytes,
            speedBytesPerSec = speedBytesPerSec,
            chunkCount = chunkCount,
            supportsRange = supportsRange,
            status = runCatching { DownloadStatus.valueOf(status) }.getOrDefault(DownloadStatus.FAILED),
            engineType = runCatching { EngineType.valueOf(engineType) }.getOrDefault(EngineType.DIRECT),
            selectedFormatId = selectedFormatId,
            errorMessage = errorMessage,
            savedUri = savedUri,
            createdAt = createdAt
        )
    }
}

@Entity(
    tableName = "download_chunks",
    foreignKeys = [
        ForeignKey(
            entity = DownloadTaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["taskId", "chunkIndex"], unique = true)]
)
data class DownloadChunkEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val taskId: Long,
    val chunkIndex: Int,
    val startByte: Long,
    val endByte: Long,
    val downloadedBytes: Long = 0L,
    val isCompleted: Boolean = false
)

package com.indirici.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {

    @Query("SELECT * FROM download_tasks ORDER BY createdAt DESC")
    fun observeAllTasks(): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_tasks WHERE id = :taskId LIMIT 1")
    suspend fun getTaskById(taskId: Long): DownloadTaskEntity?

    @Query("SELECT * FROM download_tasks WHERE status = :status ORDER BY createdAt ASC")
    suspend fun getTasksByStatus(status: String): List<DownloadTaskEntity>

    @Query("SELECT * FROM download_tasks WHERE status IN ('QUEUED', 'DOWNLOADING') ORDER BY createdAt ASC")
    suspend fun getActiveAndQueuedTasks(): List<DownloadTaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: DownloadTaskEntity): Long

    @Query(
        """
        UPDATE download_tasks
        SET fileName = :fileName,
            mimeType = :mimeType,
            totalBytes = :totalBytes,
            supportsRange = :supportsRange,
            tempFilePath = :tempFilePath
        WHERE id = :taskId
        """
    )
    suspend fun updateTaskMetadata(
        taskId: Long,
        fileName: String,
        mimeType: String?,
        totalBytes: Long,
        supportsRange: Boolean,
        tempFilePath: String
    )

    @Query(
        """
        UPDATE download_tasks
        SET url = :url,
            fileName = :fileName,
            mimeType = :mimeType,
            engineType = :engineType
        WHERE id = :taskId
        """
    )
    suspend fun updateTaskEngineAndUrl(
        taskId: Long,
        url: String,
        fileName: String,
        mimeType: String?,
        engineType: String
    )

    @Query(
        """
        UPDATE download_tasks
        SET downloadedBytes = :downloadedBytes,
            totalBytes = :totalBytes,
            speedBytesPerSec = :speedBytesPerSec
        WHERE id = :taskId
        """
    )
    suspend fun updateTaskProgress(
        taskId: Long,
        downloadedBytes: Long,
        totalBytes: Long,
        speedBytesPerSec: Long
    )

    @Query(
        """
        UPDATE download_tasks
        SET status = :status,
            speedBytesPerSec = 0,
            errorMessage = :errorMessage
        WHERE id = :taskId
        """
    )
    suspend fun updateTaskStatus(
        taskId: Long,
        status: String,
        errorMessage: String? = null
    )

    @Query(
        """
        UPDATE download_tasks
        SET status = 'COMPLETED',
            downloadedBytes = totalBytes,
            speedBytesPerSec = 0,
            savedUri = :savedUri,
            errorMessage = NULL
        WHERE id = :taskId
        """
    )
    suspend fun markTaskCompleted(
        taskId: Long,
        savedUri: String
    )

    @Query("DELETE FROM download_tasks WHERE id = :taskId")
    suspend fun deleteTask(taskId: Long)

    // --- Parça (Chunk) Sorguları ---

    @Query("SELECT * FROM download_chunks WHERE taskId = :taskId ORDER BY chunkIndex ASC")
    suspend fun getChunksForTask(taskId: Long): List<DownloadChunkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChunks(chunks: List<DownloadChunkEntity>)

    @Query(
        """
        UPDATE download_chunks
        SET downloadedBytes = :downloadedBytes,
            isCompleted = :isCompleted
        WHERE taskId = :taskId AND chunkIndex = :chunkIndex
        """
    )
    suspend fun updateChunkProgress(
        taskId: Long,
        chunkIndex: Int,
        downloadedBytes: Long,
        isCompleted: Boolean
    )

    @Query("DELETE FROM download_chunks WHERE taskId = :taskId")
    suspend fun deleteChunksForTask(taskId: Long)
}

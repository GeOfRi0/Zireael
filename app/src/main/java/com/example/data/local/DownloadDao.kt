package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object (DAO) for interacting with the downloads database table.
 * Supports reactive queries (Flow), insertions, status/progress updates, and deletions.
 */
@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun getAllDownloads(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id LIMIT 1")
    suspend fun getDownloadById(id: String): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE status IN ('DOWNLOADING', 'CONNECTING', 'QUEUED') ORDER BY createdAt DESC")
    fun getActiveDownloads(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE status = 'COMPLETED' ORDER BY completedAt DESC")
    fun getCompletedDownloads(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE status = :status ORDER BY createdAt DESC")
    fun getDownloadsByStatus(status: String): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE status = :status ORDER BY createdAt ASC")
    suspend fun getDownloadsByStatusList(status: String): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE category = :category ORDER BY createdAt DESC")
    fun getDownloadsByCategory(category: String): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE fileName LIKE '%' || :query || '%' ORDER BY createdAt DESC")
    fun searchDownloads(query: String): Flow<List<DownloadEntity>>

    @Query("SELECT COUNT(*) FROM downloads")
    fun getDownloadCount(): Flow<Int>

    @Query("SELECT SUM(downloadedBytes) FROM downloads WHERE status = 'COMPLETED'")
    fun getTotalCompletedBytes(): Flow<Long?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(download: DownloadEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(downloads: List<DownloadEntity>)

    @Update
    suspend fun update(download: DownloadEntity)

    @Query("UPDATE downloads SET downloadedBytes = :downloaded, totalBytes = :total, speedBytesPerSec = :speed, status = :status WHERE id = :id")
    suspend fun updateProgress(id: String, downloaded: Long, total: Long, speed: Long, status: String)

    @Query("UPDATE downloads SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    @Delete
    suspend fun delete(download: DownloadEntity)

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM downloads WHERE status = :status")
    suspend fun deleteByStatus(status: String): Int

    @Query("DELETE FROM downloads")
    suspend fun clearAll()
}

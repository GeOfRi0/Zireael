package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import com.example.data.local.DownloadDao
import com.example.data.local.DownloadEntity
import com.example.data.model.DownloadStatus
import com.example.data.model.FileCategory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: DownloadDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.downloadDao()
    }

    @After
    fun closeDb() {
        database.close()
    }

    @Test
    fun insertAndRetrieveDownloadById() = runBlocking {
        val download = DownloadEntity(
            id = "test-id-1",
            url = "https://example.com/video.mp4",
            fileName = "video.mp4",
            filePath = "/storage/downloads/video.mp4",
            mimeType = "video/mp4",
            category = FileCategory.MEDIA.name,
            totalBytes = 10485760L,
            downloadedBytes = 0L,
            status = DownloadStatus.QUEUED.name
        )

        dao.insert(download)

        val retrieved = dao.getDownloadById("test-id-1")
        assertNotNull(retrieved)
        assertEquals("video.mp4", retrieved?.fileName)
        assertEquals("https://example.com/video.mp4", retrieved?.url)
        assertEquals(DownloadStatus.QUEUED.name, retrieved?.status)
    }

    @Test
    fun updateStatusAndProgress() = runBlocking {
        val download = DownloadEntity(
            id = "test-id-2",
            url = "https://example.com/photo.jpg",
            fileName = "photo.jpg",
            filePath = "/storage/downloads/photo.jpg",
            mimeType = "image/jpeg",
            category = FileCategory.MEDIA.name,
            totalBytes = 2048000L,
            downloadedBytes = 0L,
            status = DownloadStatus.DOWNLOADING.name
        )

        dao.insert(download)

        // Update progress
        dao.updateProgress(
            id = "test-id-2",
            downloaded = 1024000L,
            total = 2048000L,
            speed = 512000L,
            status = DownloadStatus.DOWNLOADING.name
        )

        var retrieved = dao.getDownloadById("test-id-2")
        assertEquals(1024000L, retrieved?.downloadedBytes)
        assertEquals(512000L, retrieved?.speedBytesPerSec)

        // Update status to COMPLETED
        dao.updateStatus("test-id-2", DownloadStatus.COMPLETED.name)
        retrieved = dao.getDownloadById("test-id-2")
        assertEquals(DownloadStatus.COMPLETED.name, retrieved?.status)
    }

    @Test
    fun deleteDownloadById() = runBlocking {
        val download = DownloadEntity(
            id = "test-id-3",
            url = "https://example.com/archive.zip",
            fileName = "archive.zip",
            filePath = "/storage/downloads/archive.zip",
            mimeType = "application/zip",
            category = FileCategory.ARCHIVES.name,
            totalBytes = 5000000L,
            downloadedBytes = 5000000L,
            status = DownloadStatus.COMPLETED.name
        )

        dao.insert(download)
        assertNotNull(dao.getDownloadById("test-id-3"))

        dao.deleteById("test-id-3")
        assertNull(dao.getDownloadById("test-id-3"))
    }

    @Test
    fun flowObservesActiveDownloads() = runBlocking {
        val active1 = DownloadEntity(
            id = "active-1",
            url = "https://example.com/song.mp3",
            fileName = "song.mp3",
            filePath = "/storage/downloads/song.mp3",
            mimeType = "audio/mpeg",
            category = FileCategory.MEDIA.name,
            totalBytes = 3000000L,
            downloadedBytes = 1000000L,
            status = DownloadStatus.DOWNLOADING.name
        )

        val completed = DownloadEntity(
            id = "completed-1",
            url = "https://example.com/document.pdf",
            fileName = "document.pdf",
            filePath = "/storage/downloads/document.pdf",
            mimeType = "application/pdf",
            category = FileCategory.DOCUMENTS.name,
            totalBytes = 500000L,
            downloadedBytes = 500000L,
            status = DownloadStatus.COMPLETED.name
        )

        dao.insert(active1)
        dao.insert(completed)

        val activeList = dao.getActiveDownloads().first()
        assertEquals(1, activeList.size)
        assertEquals("active-1", activeList[0].id)
    }
}

package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.FileCategory
import com.example.ui.viewmodel.DownloadViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `read app_name from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Zireael", appName)
    }

    @Test
    fun `file category detection works correctly`() {
        assertEquals(FileCategory.MEDIA, FileCategory.detect("movie.mp4", "video/mp4"))
        assertEquals(FileCategory.MEDIA, FileCategory.detect("scenery.jpg", "image/jpeg"))
        assertEquals(FileCategory.MEDIA, FileCategory.detect("song.mp3", "audio/mpeg"))
        assertEquals(FileCategory.DOCUMENTS, FileCategory.detect("report.pdf", "application/pdf"))
        assertEquals(FileCategory.ARCHIVES, FileCategory.detect("archive.zip", "application/zip"))
        assertEquals(FileCategory.APKS, FileCategory.detect("app.apk", "application/vnd.android.package-archive"))
    }

    @Test
    fun `speed and byte formatters work correctly`() {
        assertEquals("0 KB/s", DownloadViewModel.formatSpeed(0L))
        assertTrue(DownloadViewModel.formatSpeed(1024 * 1024 * 5).contains("MB/s"))
        assertTrue(DownloadViewModel.formatBytes(1024 * 1024 * 50).contains("MB"))
    }

    @Test
    fun `percentage progress and ETA calculate accurately`() {
        val downloaded = 50L
        val total = 100L
        val percent = ((downloaded * 100) / total).toInt()
        assertEquals(50, percent)

        val eta = DownloadViewModel.calculateEta(50 * 1024 * 1024L, 100 * 1024 * 1024L, 5 * 1024 * 1024L)
        assertEquals("00:10", eta)
    }
}

package com.example

import com.example.data.engine.MediaSniffer
import com.example.data.model.FileCategory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaSnifferTest {

    private val sniffer = MediaSniffer()

    @Test
    fun testFileCategoryDetection() {
        assertEquals(FileCategory.MEDIA, FileCategory.detect("sample.mp4", "video/mp4"))
        assertEquals(FileCategory.MEDIA, FileCategory.detect("stream.mkv", "video/x-matroska"))
        assertEquals(FileCategory.MEDIA, FileCategory.detect("wallpaper.webp", "image/webp"))
        assertEquals(FileCategory.MEDIA, FileCategory.detect("photo.png", "image/png"))
        assertEquals(FileCategory.MEDIA, FileCategory.detect("track.mp3", "audio/mpeg"))
        assertEquals(FileCategory.DOCUMENTS, FileCategory.detect("manual.pdf", "application/pdf"))
        assertEquals(FileCategory.APKS, FileCategory.detect("app.apk", "application/vnd.android.package-archive"))
    }

    @Test
    fun testDirectUrlHandling() = runBlocking {
        // Direct media URL fallback when probed
        val results = sniffer.sniff("https://example.com/test_video.mp4")
        assertNotNull(results)
        assertFalse(results.isEmpty())
        val first = results.first()
        assertTrue(first.url.contains("test_video.mp4"))
    }

    @Test
    fun testYouTubeLinkSniffing() = runBlocking {
        val results = sniffer.sniff("https://www.youtube.com/watch?v=jNQXAC9IVRw")
        assertNotNull(results)
        assertFalse(results.isEmpty())
        assertTrue(results.any { it.title.contains("YouTube", ignoreCase = true) || it.title.contains("zoo", ignoreCase = true) })
        assertTrue(results.all { it.category == FileCategory.MEDIA })
    }

    @Test
    fun testInstagramLinkSniffing() = runBlocking {
        val results = sniffer.sniff("https://www.instagram.com/reel/C-iQZ20s7sV/")
        assertNotNull(results)
        assertFalse(results.isEmpty())
        assertTrue(results.any { it.title.contains("Instagram", ignoreCase = true) })
        assertTrue(results.all { it.category == FileCategory.MEDIA })
    }
}

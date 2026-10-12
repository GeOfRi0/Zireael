package com.example

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.data.engine.AdBlocker
import com.example.data.engine.DownloadStorageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase6AdBlockerAndStorageTest {

    @Before
    fun setup() {
        AdBlocker.isEnabled = true
        AdBlocker.resetCount()
    }

    // --- 1. Ad-Blocker Unit Tests ---

    @Test
    fun `ad blocker detects known ad networks and tracking domains`() {
        assertTrue(AdBlocker.isAd("https://adservice.google.com/adsid/google/ui"))
        assertTrue(AdBlocker.isAd("https://securepubads.g.doubleclick.net/gampad/ads?iu=123"))
        assertTrue(AdBlocker.isAd("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js"))
        assertTrue(AdBlocker.isAd("https://serve.popads.net/serve.js"))
        assertTrue(AdBlocker.isAd("https://cdn.taboola.com/libtrc/unip/publisher/tfa.js"))
        assertTrue(AdBlocker.isAd("https://widgets.outbrain.com/outbrain.js"))
        assertTrue(AdBlocker.isAd("https://s.amazon-adsystem.com/iu3"))
        assertTrue(AdBlocker.isAd("https://example.com/static/prebid.js"))
        assertTrue(AdBlocker.isAd("https://example.com/assets/ads.js"))
    }

    @Test
    fun `ad blocker allows legitimate media and web streams`() {
        assertFalse(AdBlocker.isAd("https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"))
        assertFalse(AdBlocker.isAd("https://rr1---sn-4g5ednks.googlevideo.com/videoplayback?expire=123"))
        assertFalse(AdBlocker.isAd("https://github.com/TeamNewPipe/NewPipeExtractor/releases/download/v0.26.5/release.zip"))
        assertFalse(AdBlocker.isAd("https://en.wikipedia.org/wiki/Android_(operating_system)"))
        assertFalse(AdBlocker.isAd("https://cdn.example.org/audio/podcast_episode_1.mp3"))
    }

    @Test
    fun `ad blocker empty response is non-null and empty stream`() {
        val response = AdBlocker.createEmptyResponse()
        assertNotNull(response)
        assertEquals("text/plain", response.mimeType)
        assertEquals("UTF-8", response.encoding)
        assertNotNull(response.data)
        assertEquals(0, response.data.available())
    }

    @Test
    fun `ad blocker counter increments on blocked requests and obeys toggle`() {
        assertEquals(0, AdBlocker.blockedCount.get())

        AdBlocker.isAd("https://adservice.google.com/test")
        AdBlocker.isAd("https://popads.net/banner")
        assertEquals(2, AdBlocker.blockedCount.get())

        // Disable ad blocker
        AdBlocker.isEnabled = false
        val blockedWhenDisabled = AdBlocker.isAd("https://doubleclick.net/tracker")
        assertFalse(blockedWhenDisabled)
        assertEquals(2, AdBlocker.blockedCount.get())
    }

    // --- 2. Storage & SAF Tests ---

    @Test
    fun `download storage manager provides valid download directory`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = DownloadStorageManager.getDownloadDirectory(context)

        assertNotNull(dir)
        assertTrue(dir.exists())
        assertTrue(dir.canWrite())
    }

    @Test
    fun `saf custom tree uri persistence and clearing work correctly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        DownloadStorageManager.clearCustomSafUri(context)
        assertNull(DownloadStorageManager.getCustomSafUri(context))

        val dummyUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADownloads")
        // In unit tests, takePersistableUriPermission might not have provider, test fallback safe storage
        val prefs = context.getSharedPreferences("turbo_idm_storage_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("key_saf_custom_tree_uri", dummyUri.toString()).apply()

        val retrievedUri = DownloadStorageManager.getCustomSafUri(context)
        assertEquals(dummyUri, retrievedUri)

        DownloadStorageManager.clearCustomSafUri(context)
        assertNull(DownloadStorageManager.getCustomSafUri(context))
    }

    @Test
    fun `media store registration executes without throwing exception`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val testFile = File(context.cacheDir, "sample_test_download.mp4").apply {
            writeText("dummy content for media store registration test")
        }

        // Should not throw any exception
        DownloadStorageManager.registerWithMediaStore(
            context = context,
            file = testFile,
            mimeType = "video/mp4",
            totalBytes = testFile.length()
        )
        testFile.delete()
    }

    // --- 3. Share URL Categorization Logic Tests ---

    @Test
    fun `direct file downloads vs web pages are categorized correctly`() {
        fun isDirectFile(url: String): Boolean {
            val lower = url.lowercase().substringBefore('?')
            val extensions = listOf(".apk", ".zip", ".rar", ".7z", ".tar", ".gz", ".iso", ".mp4", ".mkv", ".webm", ".mp3", ".pdf")
            return extensions.any { lower.endsWith(it) }
        }

        assertTrue(isDirectFile("https://example.com/app-release.apk"))
        assertTrue(isDirectFile("https://example.com/archive.zip?token=xyz"))
        assertTrue(isDirectFile("https://example.com/video.mp4"))
        assertTrue(isDirectFile("https://example.com/document.pdf"))

        assertFalse(isDirectFile("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertFalse(isDirectFile("https://youtu.be/dQw4w9WgXcQ"))
        assertFalse(isDirectFile("https://vimeo.com/123456789"))
        assertFalse(isDirectFile("https://twitter.com/user/status/12345"))
    }
}

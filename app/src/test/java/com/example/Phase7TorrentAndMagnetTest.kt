package com.example

import com.example.data.engine.MagnetUriParser
import com.example.data.engine.TorrentDownloadEngine
import com.example.data.model.FileCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase7TorrentAndMagnetTest {

    @Test
    fun testMagnetUriParser_StandardUri() {
        val magnetUri = "magnet:?xt=urn:btih:c12fe1c06bba254a9d1b00f127b7ac661c6b939c&dn=Ubuntu+22.04+LTS&tr=udp%3A%2F%2Ftracker.openbittorrent.com%3A80&xl=3654957056"

        assertTrue(MagnetUriParser.isMagnet(magnetUri))

        val info = MagnetUriParser.parse(magnetUri)
        assertNotNull(info)
        assertEquals("c12fe1c06bba254a9d1b00f127b7ac661c6b939c", info?.infoHash)
        assertEquals("Ubuntu 22.04 LTS", info?.displayName)
        assertEquals(3654957056L, info?.exactLength)
        assertEquals(1, info?.trackers?.size)
        assertEquals("udp://tracker.openbittorrent.com:80", info?.trackers?.first())
    }

    @Test
    fun testMagnetUriParser_MultipleTrackersAndSpecialChars() {
        val magnetUri = "magnet:?xt=urn:btih:YNCKHTJD5S6C4TNDEF2KFXHM254WWU44&dn=Test%20Torrent%20%26%20Files&tr=http%3A%2F%2Ftracker1.org%2Fannounce&tr=udp%3A%2F%2Ftracker2.com%3A1337"

        val info = MagnetUriParser.parse(magnetUri)
        assertNotNull(info)
        assertEquals("YNCKHTJD5S6C4TNDEF2KFXHM254WWU44", info?.infoHash)
        assertEquals("Test Torrent & Files", info?.displayName)
        assertEquals(2, info?.trackers?.size)
        assertTrue(info?.trackers?.contains("http://tracker1.org/announce") == true)
        assertTrue(info?.trackers?.contains("udp://tracker2.com:1337") == true)
    }

    @Test
    fun testMagnetUriParser_InvalidOrEmpty() {
        assertFalse(MagnetUriParser.isMagnet("https://example.com/file.mp4"))
        assertFalse(MagnetUriParser.isMagnet(""))
        assertNull(MagnetUriParser.parse("https://example.com/file.mp4"))
        assertNull(MagnetUriParser.parse("magnet:?dn=NoHash"))
    }

    @Test
    fun testTorrentDownloadEngine_IsTorrentOrMagnet() {
        // Magnet Links
        assertTrue(TorrentDownloadEngine.isTorrentOrMagnet("magnet:?xt=urn:btih:1234567890abcdef"))
        assertTrue(TorrentDownloadEngine.isTorrentOrMagnet("MAGNET:?XT=URN:BTIH:ABCDEF"))
        assertTrue(TorrentDownloadEngine.isTorrentOrMagnet("magnet://?xt=urn:btih:abcdef"))

        // Torrent Files
        assertTrue(TorrentDownloadEngine.isTorrentOrMagnet("https://webtorrent.io/torrents/sintel.torrent"))
        assertTrue(TorrentDownloadEngine.isTorrentOrMagnet("http://example.com/archive.torrent?passkey=xyz"))
        assertTrue(TorrentDownloadEngine.isTorrentOrMagnet("/sdcard/Download/ubuntu.torrent"))

        // Regular URLs - Must NOT be treated as torrents
        assertFalse(TorrentDownloadEngine.isTorrentOrMagnet("https://example.com/movie.mp4"))
        assertFalse(TorrentDownloadEngine.isTorrentOrMagnet("https://github.com/app.apk"))
        assertFalse(TorrentDownloadEngine.isTorrentOrMagnet("https://example.com/document.pdf"))
        assertFalse(TorrentDownloadEngine.isTorrentOrMagnet(""))
        assertFalse(TorrentDownloadEngine.isTorrentOrMagnet(null))
    }

    @Test
    fun testFileCategoryDetection_TorrentAndMagnet() {
        val categoryTorrent = FileCategory.detect("archlinux.torrent", "application/x-bittorrent")
        assertEquals(FileCategory.ARCHIVES, categoryTorrent)

        val categoryMagnet = FileCategory.detect("magnet:?xt=urn:btih:12345", "")
        assertEquals(FileCategory.ARCHIVES, categoryMagnet)
    }
}

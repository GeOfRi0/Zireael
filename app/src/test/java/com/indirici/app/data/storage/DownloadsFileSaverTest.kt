package com.indirici.app.data.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadsFileSaverTest {

    @Test
    fun `sanitizeFileName removes illegal chars, path traversal, control chars and trailing dots`() {
        val result = DownloadsFileSaver.sanitizeFileName("../../../etc/passwd.txt")
        assertFalse(result.contains(".."))
        assertFalse(result.contains("/"))
        assertEquals("etc_passwd.txt", result)

        val dotsResult = DownloadsFileSaver.sanitizeFileName("my_document... ")
        assertEquals("my_document", dotsResult)

        val ctrlResult = DownloadsFileSaver.sanitizeFileName("bad\u0000file\nname\r.mp4")
        assertFalse(ctrlResult.contains("\u0000"))
        assertFalse(ctrlResult.contains("\n"))
        assertFalse(ctrlResult.contains("\r"))
        assertEquals("bad_file_name.mp4", ctrlResult)

        val parentDirResult = DownloadsFileSaver.sanitizeFileName("..")
        assertTrue(parentDirResult.startsWith("indirilen_dosya_"))
    }

    @Test
    fun `sanitizeFileName keeps the extension when the base name consists only of illegal characters`() {
        val result = DownloadsFileSaver.sanitizeFileName("***.mp4")
        assertTrue("uzantı kayboldu: $result", result.endsWith(".mp4"))
        assertTrue("dosya adı boş kaldı: $result", result.length > ".mp4".length)
    }

    @Test
    fun `guessMimeType returns correct mime types for common media containers and formats`() {
        assertEquals("video/mp4", DownloadsFileSaver.guessMimeType("video.mp4"))
        assertEquals("video/x-matroska", DownloadsFileSaver.guessMimeType("video.mkv"))
        assertEquals("audio/mpeg", DownloadsFileSaver.guessMimeType("song.mp3"))
        assertEquals("audio/flac", DownloadsFileSaver.guessMimeType("track.flac"))
        assertEquals("audio/wav", DownloadsFileSaver.guessMimeType("voice.wav"))
        assertEquals("application/octet-stream", DownloadsFileSaver.guessMimeType("data.bin"))
    }
}

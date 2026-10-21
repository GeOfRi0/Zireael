package com.indirici.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsPlaylistInspectorTest {

    private val master = """
        #EXTM3U
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="tr",URI="audio/tr.m3u8"
        #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,AUDIO="aud"
        360/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,AUDIO="aud"
        https://cdn2.example.com/1080/index.m3u8?sig=1
        #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720,AUDIO="aud"
        720/index.m3u8
    """.trimIndent()

    private val media = """
        ${'\uFEFF'}#EXTM3U
        #EXT-X-TARGETDURATION:6
        #EXT-X-MAP:URI="init.mp4"
        #EXTINF:6.0,
        seg-1.m4s
        #EXTINF:6.0,
        seg-2.m4s
        #EXTINF:3.5,
        /abs/seg-3.m4s
        #EXT-X-ENDLIST
    """.trimIndent()

    @Test
    fun `detects playlist content including BOM and leading whitespace`() {
        assertTrue(HlsPlaylistInspector.isPlaylistContent("\n  #EXTM3U\n"))
        assertTrue(HlsPlaylistInspector.isPlaylistContent(media))
        assertFalse(HlsPlaylistInspector.isPlaylistContent("<html>#EXTM3U</html>"))
        assertFalse(HlsPlaylistInspector.isPlaylistContent(null))
    }

    @Test
    fun `parses master playlist variants audio renditions and best variant`() {
        val s = HlsPlaylistInspector.parse("https://cdn.example.com/v/master.m3u8?t=9", master)
        assertTrue(s.isMaster)
        assertEquals(3, s.variantCount)
        assertEquals(1080, s.maxHeight)
        assertEquals("https://cdn2.example.com/1080/index.m3u8?sig=1", s.bestVariantUri)
        assertTrue(s.childPlaylistUris.contains("https://cdn.example.com/v/audio/tr.m3u8"))
        assertTrue(s.childPlaylistUris.contains("https://cdn.example.com/v/360/index.m3u8"))
        assertTrue(s.childPlaylistUris.contains("https://cdn.example.com/v/720/index.m3u8"))
        assertTrue(s.segmentUris.isEmpty())
    }

    @Test
    fun `parses media playlist segments duration and endlist`() {
        val s = HlsPlaylistInspector.parse("https://cdn.example.com/v/720/index.m3u8", media)
        assertFalse(s.isMaster)
        assertTrue(s.isEndList)
        assertEquals(15.5, s.totalDurationSec, 0.001)
        assertEquals(
            listOf(
                "https://cdn.example.com/v/720/init.mp4",
                "https://cdn.example.com/v/720/seg-1.m4s",
                "https://cdn.example.com/v/720/seg-2.m4s",
                "https://cdn.example.com/abs/seg-3.m4s"
            ),
            s.segmentUris
        )
    }

    @Test
    fun `directoryOf strips file name and query`() {
        assertEquals("https://a.com/x/y/", HlsPlaylistInspector.directoryOf("https://a.com/x/y/z.m3u8?k=1"))
    }
}

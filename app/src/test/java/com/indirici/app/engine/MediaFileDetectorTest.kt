package com.indirici.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaFileDetectorTest {

    @Test
    fun `classifyUrl detects audio extensions wav ogg flac opus mp3 m4a aac`() {
        val audioUrls = listOf(
            "https://cdn.example.com/music/track.wav",
            "https://cdn.example.com/music/track.ogg",
            "https://cdn.example.com/music/track.flac",
            "https://cdn.example.com/music/track.opus",
            "https://cdn.example.com/music/track.mp3",
            "https://cdn.example.com/music/track.m4a",
            "https://cdn.example.com/music/track.aac"
        )
        for (url in audioUrls) {
            assertEquals(
                "Beklenen DIRECT_AUDIO: $url",
                DetectedMediaCategory.DIRECT_AUDIO,
                MediaFileDetector.classifyUrl(url)
            )
        }
    }

    @Test
    fun `classifyUrl detects video containers mkv mov webm avi wmv mp4 and segments ts fmp4 m4s`() {
        val videoUrls = listOf(
            "https://cdn.example.com/video/movie.mkv",
            "https://cdn.example.com/video/clip.mov",
            "https://cdn.example.com/video/clip.webm",
            "https://cdn.example.com/video/old.avi",
            "https://cdn.example.com/video/win.wmv",
            "https://cdn.example.com/video/main.mp4"
        )
        for (url in videoUrls) {
            assertEquals(
                "Beklenen DIRECT_VIDEO: $url",
                DetectedMediaCategory.DIRECT_VIDEO,
                MediaFileDetector.classifyUrl(url)
            )
        }

        val segmentUrls = listOf(
            "https://cdn.example.com/hls/seg_001.ts",
            "https://cdn.example.com/dash/chunk_01.m4s",
            "https://cdn.example.com/dash/video.fmp4"
        )
        for (url in segmentUrls) {
            assertEquals(
                "Beklenen STREAM_SEGMENT: $url",
                DetectedMediaCategory.STREAM_SEGMENT,
                MediaFileDetector.classifyUrl(url)
            )
        }
    }

    @Test
    fun `inspectHlsManifest detects H265 HEVC fMP4 m4s and AAC HLS streams`() {
        val h265Fmp4Manifest = """
            #EXTM3U
            #EXT-X-VERSION:7
            #EXT-X-STREAM-INF:BANDWIDTH=4500000,RESOLUTION=1920x1080,CODECS="hvc1.1.6.L120.90,mp4a.40.2"
            #EXT-X-MAP:URI="init.mp4"
            segment_0001.m4s
        """.trimIndent()

        val info = MediaFileDetector.inspectHlsManifest(
            url = "https://cdn.example.com/master.m3u8",
            manifestContent = h265Fmp4Manifest
        )
        assertTrue(info.isHls)
        assertTrue(info.hasH265Hevc)
        assertTrue(info.usesFmp4M4sSegments)

        val aacOnlyManifest = """
            #EXTM3U
            #EXT-X-TARGETDURATION:10
            #EXT-X-STREAM-INF:BANDWIDTH=128000,CODECS="mp4a.40.2"
            audio_001.aac
        """.trimIndent()

        val aacInfo = MediaFileDetector.inspectHlsManifest(
            url = "https://cdn.example.com/audio.m3u8",
            manifestContent = aacOnlyManifest
        )
        assertTrue(aacInfo.isAacAudioStream)
        assertTrue(aacInfo.usesMpegTsSegments)
    }

    @Test
    fun `extractMediaLinksFromContent catches embedded audio video and hls stream urls`() {
        val html = """
            <html>
            <body>
                <audio src="https://media.example.com/sound/effect.flac?token=1"></audio>
                <script>
                    var stream = "https:\/\/stream.example.com\/live\/h265_master.m3u8";
                    var segment = "/chunks/seg_01.m4s";
                    var backup = "/videos/archive.mkv";
                </script>
            </body>
            </html>
        """.trimIndent()

        val links = MediaFileDetector.extractMediaLinksFromContent(
            content = html,
            baseUrl = "https://site.example.com/watch/123"
        )

        assertTrue(links.contains("https://media.example.com/sound/effect.flac?token=1"))
        assertTrue(links.contains("https://stream.example.com/live/h265_master.m3u8"))
        assertTrue(links.contains("https://site.example.com/chunks/seg_01.m4s"))
        assertTrue(links.contains("https://site.example.com/videos/archive.mkv"))
    }
}

package com.indirici.app.engine

import com.indirici.app.domain.model.VideoDownloadTask
import com.indirici.app.ui.browser.VideoSnifferWebViewClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FfmpegAndSnifferTest {

    @Test
    fun `buildFfmpegArguments returns Array of String preserving quotes spaces and special characters in cookies`() {
        val complexCookie = """cf_clearance=abc"def'123; session_token="xyz=1&b=2"; theme=dark mode"""
        val complexUa = """Mozilla/5.0 (Linux; Android 14; "Pixel 8") AppleWebKit/537.36"""
        val referer = "https://site.example.com/watch?v=123&title=a b"
        val m3u8Url = "https://cdn.example.com/hls/master.m3u8?sig=a\"b"
        val outputPath = "/storage/emulated/0/Android/data/com.indirici.app/files/Download/video 1.mp4"

        val args: Array<String> = FfmpegHlsEngine.buildFfmpegArguments(
            m3u8Url = m3u8Url,
            outputPath = outputPath,
            userAgent = complexUa,
            referer = referer,
            cookie = complexCookie
        )

        val expectedHeaders = "User-Agent: $complexUa\r\nReferer: $referer\r\n" +
            "Origin: https://site.example.com\r\nCookie: $complexCookie\r\n"
        val expectedArray = arrayOf(
            "-headers",
            expectedHeaders,
            "-reconnect", "1",
            "-reconnect_streamed", "1",
            "-reconnect_on_network_error", "1",
            "-reconnect_delay_max", "5",
            "-rw_timeout", "30000000",
            "-seg_max_retry", "5",
            "-allowed_extensions", "ALL",
            "-i", m3u8Url,
            "-sn",
            "-dn",
            "-c", "copy",
            "-max_muxing_queue_size", "4096",
            "-avoid_negative_ts", "make_zero",
            "-y", outputPath
        )

        assertArrayEquals(expectedArray, args)
        // Çerez içindeki çift tırnak ve tek tırnak karakterlerinin hiç bozulmadan korunduğunu doğrula
        assertTrue(args[1].contains("""cf_clearance=abc"def'123; session_token="xyz=1&b=2""""))
    }

    @Test
    fun `VideoSnifferWebViewClient evaluateCandidateUrl detects m3u8 and application vnd apple mpegurl`() {
        val byExtension = VideoSnifferWebViewClient.evaluateCandidateUrl(
            url = "https://stream.example.com/live/index.m3u8?token=abc"
        )
        assertNotNull(byExtension)
        assertFalse(byExtension!!.isDrmProtected)

        val byContentType = VideoSnifferWebViewClient.evaluateCandidateUrl(
            url = "https://stream.example.com/live/playlist",
            contentTypeHeader = "application/vnd.apple.mpegurl; charset=utf-8"
        )
        assertNotNull(byContentType)
        assertFalse(byContentType!!.isDrmProtected)
    }

    @Test
    fun `VideoSnifferWebViewClient evaluateCandidateUrl flags mpd as DRM protected`() {
        val mpdEval = VideoSnifferWebViewClient.evaluateCandidateUrl(
            url = "https://stream.example.com/dash/manifest.mpd"
        )
        assertNotNull(mpdEval)
        assertTrue(mpdEval!!.isDrmProtected)
    }

    @Test
    fun `ffmpeg arguments never use reconnect_at_eof or forced aac bsf`() {
        val args = FfmpegHlsEngine.buildFfmpegArguments("https://a.b/x.m3u8", "/tmp/o.mp4", "", "", "")
        assertFalse(args.contains("-reconnect_at_eof"))
        assertFalse(args.contains("-bsf:a"))
        assertFalse(args.contains("-headers"))
    }

    @Test
    fun `skipped segment log line is detected`() {
        assertTrue(FfmpegHlsEngine.isSkippedSegmentLog("Segment 12 of playlist 0 failed too many times, skipping"))
        assertFalse(FfmpegHlsEngine.isSkippedSegmentLog("Opening 'https://cdn/seg12.ts' for reading"))
    }

    @Test
    fun `sniffer treats HLS fragments as segments not standalone videos`() {
        assertTrue(VideoSnifferWebViewClient.looksLikeSegmentFileName("https://cdn.x.com/hls/720/seg-12-v1-a1.mp4?t=1"))
        assertTrue(VideoSnifferWebViewClient.looksLikeSegmentFileName("https://cdn.x.com/v/chunk_3.m4a"))
        assertTrue(VideoSnifferWebViewClient.looksLikeSegmentFileName("https://cdn.x.com/v/fragment5.ts"))
        assertFalse(VideoSnifferWebViewClient.looksLikeSegmentFileName("https://cdn.x.com/videos/my_movie.mp4"))

        val ts = VideoSnifferWebViewClient.evaluateCandidateUrl("https://cdn.x.com/hls/file1.ts")
        assertEquals(DetectedMediaCategory.STREAM_SEGMENT, ts!!.category)
        val hls = VideoSnifferWebViewClient.evaluateCandidateUrl("https://cdn.x.com/hls/master.m3u8")
        assertTrue(hls!!.isHlsManifest)
    }

    @Test
    fun `sniffer probes hidden manifest endpoints but not static assets`() {
        assertTrue(VideoSnifferWebViewClient.looksLikePotentialStreamEndpoint("https://x.com/api/playlist.php?id=5"))
        assertTrue(VideoSnifferWebViewClient.looksLikePotentialStreamEndpoint("https://x.com/hls/abc123"))
        assertFalse(VideoSnifferWebViewClient.looksLikePotentialStreamEndpoint("https://x.com/hls/player.js"))
        assertFalse(VideoSnifferWebViewClient.looksLikePotentialStreamEndpoint("https://x.com/hls/abc", isMainFrame = true))
        assertFalse(VideoSnifferWebViewClient.looksLikePotentialStreamEndpoint("https://x.com/hls/abc", method = "POST"))
    }

    @Test
    fun `VideoDownloadTask masks cookie and userAgent in toString`() {
        val task = VideoDownloadTask(
            url = "https://stream.example.com/master.m3u8",
            referer = "https://example.com/video",
            userAgent = "SecretUserAgent/1.0",
            cookie = "session_id=top_secret_cookie"
        )
        val dump = task.toString()
        assertFalse(dump.contains("top_secret_cookie"))
        assertFalse(dump.contains("SecretUserAgent"))
        assertTrue(dump.contains("[REDACTED]"))
    }

    @Test
    fun `MediaEngine parses total size and speed from yt-dlp progress line`() {
        val line = "[download]  45.2% of ~ 50.00MiB at    4.00MiB/s ETA 00:07"
        assertEquals(50L * 1024L * 1024L, MediaEngine.parseTotalBytesFromYtDlpLine(line))
        assertEquals(4L * 1024L * 1024L, MediaEngine.parseSpeedFromYtDlpLine(line))
    }
}

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

        val expectedHeaders = "User-Agent: $complexUa\r\nReferer: $referer\r\nCookie: $complexCookie\r\n"
        val expectedArray = arrayOf(
            "-headers",
            expectedHeaders,
            "-reconnect",
            "1",
            "-reconnect_at_eof",
            "1",
            "-reconnect_streamed",
            "1",
            "-reconnect_delay_max",
            "5",
            "-i",
            m3u8Url,
            "-c",
            "copy",
            "-bsf:a",
            "aac_adtstoasc",
            "-y",
            outputPath
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
}

package com.indirici.app.engine

import com.indirici.app.domain.engine.DownloadEngine
import com.indirici.app.domain.model.EngineType
import com.indirici.app.domain.model.UrlMetadata
import org.junit.Assert.assertSame
import org.junit.Test

class EngineRouterTest {

    private class FakeEngine(override val engineType: EngineType) : DownloadEngine {
        override suspend fun analyze(url: String, downloadId: Long?): UrlMetadata = error("Not implemented")
        override suspend fun executeDownload(
            taskId: Long,
            onProgress: suspend (Long, Long, Long) -> Unit
        ): String = error("Not implemented")
    }

    private val directEngine = FakeEngine(EngineType.DIRECT)
    private val mediaEngine = FakeEngine(EngineType.MEDIA)
    private val ffmpegEngine = FakeEngine(EngineType.FFMPEG_HLS)

    private val router = EngineRouter(
        directEngine = directEngine,
        mediaEngine = mediaEngine,
        ffmpegHlsEngine = ffmpegEngine
    )

    @Test
    fun `detectEngineForUrl routes m3u8 playlists to ffmpegHlsEngine`() {
        val result = router.detectEngineForUrl("https://cdn.example.com/stream/master.m3u8?token=xyz")
        assertSame(ffmpegEngine, result)
    }

    @Test
    fun `detectEngineForUrl routes direct audio and video files to directEngine`() {
        assertSame(directEngine, router.detectEngineForUrl("https://example.com/files/song.mp3"))
        assertSame(directEngine, router.detectEngineForUrl("https://example.com/files/audio.wav"))
        assertSame(directEngine, router.detectEngineForUrl("https://example.com/files/audio.flac"))
        assertSame(directEngine, router.detectEngineForUrl("https://example.com/files/video.mp4"))
        assertSame(directEngine, router.detectEngineForUrl("https://example.com/files/movie.mkv"))
        assertSame(directEngine, router.detectEngineForUrl("https://example.com/files/segment.ts"))
    }

    @Test
    fun `detectEngineForUrl routes known media site platforms to mediaEngine`() {
        assertSame(mediaEngine, router.detectEngineForUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertSame(mediaEngine, router.detectEngineForUrl("https://youtu.be/dQw4w9WgXcQ"))
        assertSame(mediaEngine, router.detectEngineForUrl("https://instagram.com/reel/C-123456"))
        assertSame(mediaEngine, router.detectEngineForUrl("https://www.tiktok.com/@user/video/7123456789"))
        assertSame(mediaEngine, router.detectEngineForUrl("https://v.redd.it/abc123xyz"))
        assertSame(mediaEngine, router.detectEngineForUrl("https://soundcloud.com/artist/track"))
    }

    @Test
    fun `detectEngineForUrl routes media platforms without scheme to mediaEngine`() {
        assertSame(mediaEngine, router.detectEngineForUrl("youtube.com/watch?v=dQw4w9WgXcQ"))
        assertSame(mediaEngine, router.detectEngineForUrl("www.tiktok.com/@creator/video/123"))
        assertSame(mediaEngine, router.detectEngineForUrl("youtu.be/abc123xyz"))
    }

    @Test
    fun `detectEngineForUrl routes media platforms with complex query parameters to mediaEngine`() {
        val complexUrl = "https://youtube.com/watch?v=123&title=test video [HD]&filter={abc}"
        assertSame(mediaEngine, router.detectEngineForUrl(complexUrl))
    }

    @Test
    fun `detectEngineForUrl routes DASH mpd manifests to mediaEngine`() {
        val dashUrl = "https://cdn.example.com/dash/manifest.mpd?auth=123"
        assertSame(mediaEngine, router.detectEngineForUrl(dashUrl))
    }

    @Test
    fun `getEngineByType returns exact corresponding engine`() {
        assertSame(directEngine, router.getEngineByType(EngineType.DIRECT))
        assertSame(mediaEngine, router.getEngineByType(EngineType.MEDIA))
        assertSame(ffmpegEngine, router.getEngineByType(EngineType.FFMPEG_HLS))
    }
}

package com.example

import com.example.data.engine.AudioStreamOption
import com.example.data.engine.ExtractedMediaBundle
import com.example.data.engine.ExtractorManager
import com.example.data.engine.MediaMuxerHelper
import com.example.data.engine.MediaSniffer
import com.example.data.engine.VideoStreamOption
import com.example.data.model.FileCategory
import com.example.data.model.MediaType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Unit tests for Phase 4 (Media Parser and Muxer):
 * 1. MediaType detection and classification.
 * 2. MediaSniffer image retention and categorization as MediaType.IMAGE.
 * 3. ExtractorManager stream bundle conversion into ExtractedMedia items.
 * 4. MediaMuxerHelper input validation.
 */
class MediaParserAndMuxerTest {

    @Test
    fun testMediaTypeDetection() {
        // Videos
        assertEquals(MediaType.VIDEO, MediaType.detect("movie.mp4", "video/mp4"))
        assertEquals(MediaType.VIDEO, MediaType.detect("stream.mkv", "video/x-matroska"))
        assertEquals(MediaType.VIDEO, MediaType.detect("clip.webm", "video/webm"))
        assertEquals(MediaType.VIDEO, MediaType.detect("playlist.m3u8", "application/vnd.apple.mpegurl"))

        // Audios
        assertEquals(MediaType.AUDIO, MediaType.detect("song.mp3", "audio/mpeg"))
        assertEquals(MediaType.AUDIO, MediaType.detect("audio.m4a", "audio/mp4"))
        assertEquals(MediaType.AUDIO, MediaType.detect("voice.aac", "audio/aac"))
        assertEquals(MediaType.AUDIO, MediaType.detect("track.flac", "audio/flac"))

        // Images
        assertEquals(MediaType.IMAGE, MediaType.detect("photo.jpg", "image/jpeg"))
        assertEquals(MediaType.IMAGE, MediaType.detect("banner.png", "image/png"))
        assertEquals(MediaType.IMAGE, MediaType.detect("wallpaper.webp", "image/webp"))
        assertEquals(MediaType.IMAGE, MediaType.detect("avatar.gif", "image/gif"))

        // Documents
        assertEquals(MediaType.DOCUMENT, MediaType.detect("manual.pdf", "application/pdf"))
        assertEquals(MediaType.DOCUMENT, MediaType.detect("sheet.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
    }

    @Test
    fun testMediaSnifferValidMediaAcceptsImages() {
        // Images are now recognized as valid media in Phase 4
        assertTrue(MediaSniffer.isValidMedia("https://example.com/banner.jpg", "image/jpeg"))
        assertTrue(MediaSniffer.isValidMedia("https://example.com/poster.png", "image/png"))
        assertTrue(MediaSniffer.isValidMedia("https://example.com/art.webp", "image/webp"))

        // Audio & Video
        assertTrue(MediaSniffer.isValidMedia("https://example.com/video.mp4", "video/mp4"))
        assertTrue(MediaSniffer.isValidMedia("https://example.com/audio.mp3", "audio/mpeg"))
    }

    @Test
    fun testExtractorManagerBundleConversion() {
        val manager = ExtractorManager()

        val mockBundle = ExtractedMediaBundle(
            serviceName = "YouTube",
            url = "https://www.youtube.com/watch?v=mock123",
            title = "Test Music Video",
            uploader = "TestArtist",
            durationSeconds = 240,
            thumbnailUrl = "https://example.com/thumb.jpg",
            videoStreams = listOf(
                VideoStreamOption(
                    url = "https://example.com/video_1080p.mp4",
                    resolution = "1080p",
                    format = "mp4",
                    quality = "1080p HD",
                    isVideoOnly = true,
                    codec = "avc1",
                    bitrate = 4500000
                ),
                VideoStreamOption(
                    url = "https://example.com/video_720p.mp4",
                    resolution = "720p",
                    format = "mp4",
                    quality = "720p",
                    isVideoOnly = false,
                    codec = "avc1",
                    bitrate = 2500000
                )
            ),
            audioStreams = listOf(
                AudioStreamOption(
                    url = "https://example.com/audio_160k.m4a",
                    format = "m4a",
                    averageBitrate = 160,
                    codec = "mp4a"
                )
            )
        )

        val mediaList = manager.toExtractedMediaList(mockBundle)
        assertEquals(4, mediaList.size) // 2 videos + 1 audio + 1 thumbnail

        val videoOnly = mediaList.first { it.title.contains("1080p") }
        assertEquals(MediaType.VIDEO, videoOnly.mediaType)
        assertEquals(FileCategory.MEDIA, videoOnly.category)

        val audio = mediaList.first { it.title.contains("160kbps") }
        assertEquals(MediaType.AUDIO, audio.mediaType)
        assertEquals(FileCategory.MEDIA, audio.category)

        val thumbnail = mediaList.first { it.title.contains("Küçük Resim") }
        assertEquals(MediaType.IMAGE, thumbnail.mediaType)
        assertEquals("image/jpeg", thumbnail.mimeType)
    }

    @Test
    fun testExtractorManagerPlatformSupportCheck() {
        val manager = ExtractorManager()
        assertTrue(manager.isSupported("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertTrue(manager.isSupported("https://soundcloud.com/artist/track"))
        assertFalse(manager.isSupported("https://unsupported-unknown-site-123.com/random"))
    }

    @Test
    fun testMediaMuxerHelperValidation() = runBlocking {
        val helper = MediaMuxerHelper()
        val nonExistentVideo = File("/invalid/path/video.mp4")
        val nonExistentAudio = File("/invalid/path/audio.m4a")
        val output = File("/tmp/output.mp4")

        val result = helper.mux(nonExistentVideo, nonExistentAudio, output)
        assertTrue(result.isFailure)
        assertNotNull(result.exceptionOrNull())
    }
}

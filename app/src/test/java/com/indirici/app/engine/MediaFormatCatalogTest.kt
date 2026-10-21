package com.indirici.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaFormatCatalogTest {

    @Test
    fun `buildFormatOptions includes all requested video containers codecs and audio formats`() {
        val rawStreams = listOf(
            MediaFormatCatalog.RawStreamSummary(
                height = 1080,
                vcodec = "hvc1.1.6.L120.90", // H.265 / HEVC
                acodec = "none",
                ext = "mp4",
                fileSizeBytes = 50_000_000L
            ),
            MediaFormatCatalog.RawStreamSummary(
                height = 720,
                vcodec = "avc1.4d401f",
                acodec = "mp4a.40.2",
                ext = "mp4",
                fileSizeBytes = 28_000_000L
            ),
            MediaFormatCatalog.RawStreamSummary(
                height = 480,
                vcodec = "avc1.4d401e",
                acodec = "none",
                ext = "mp4",
                fileSizeBytes = 12_000_000L
            ),
            MediaFormatCatalog.RawStreamSummary(
                height = 0,
                vcodec = "none",
                acodec = "mp4a.40.2",
                ext = "m4a",
                fileSizeBytes = 5_000_000L
            )
        )

        val options = MediaFormatCatalog.buildFormatOptions(rawStreams)
        val ids = options.map { it.formatId }.toSet()

        // İstenen tüm video seçenekleri mevcut olmalı
        assertTrue(MediaFormatCatalog.FORMAT_VIDEO_1080P in ids)
        assertTrue(MediaFormatCatalog.FORMAT_VIDEO_720P in ids)
        assertTrue(MediaFormatCatalog.FORMAT_VIDEO_480P in ids)
        assertTrue(MediaFormatCatalog.FORMAT_VIDEO_H265 in ids)
        assertTrue(MediaFormatCatalog.FORMAT_VIDEO_MKV in ids)
        assertTrue(MediaFormatCatalog.FORMAT_VIDEO_WEBM in ids)
        assertTrue(MediaFormatCatalog.FORMAT_VIDEO_MOV in ids)
        assertTrue(MediaFormatCatalog.FORMAT_VIDEO_AVI in ids)
        assertTrue(MediaFormatCatalog.FORMAT_VIDEO_WMV in ids)
        assertTrue(MediaFormatCatalog.FORMAT_VIDEO_TS in ids)
        assertTrue(MediaFormatCatalog.FORMAT_VIDEO_FMP4 in ids)

        // İstenen tüm ses seçenekleri (.mp3, .m4a, .aac, .wav, .ogg, .flac, .opus) mevcut olmalı
        assertTrue(MediaFormatCatalog.FORMAT_AUDIO_MP3 in ids)
        assertTrue(MediaFormatCatalog.FORMAT_AUDIO_M4A in ids)
        assertTrue(MediaFormatCatalog.FORMAT_AUDIO_AAC in ids)
        assertTrue(MediaFormatCatalog.FORMAT_AUDIO_WAV in ids)
        assertTrue(MediaFormatCatalog.FORMAT_AUDIO_OGG in ids)
        assertTrue(MediaFormatCatalog.FORMAT_AUDIO_FLAC in ids)
        assertTrue(MediaFormatCatalog.FORMAT_AUDIO_OPUS in ids)

        val optH265 = options.first { it.formatId == MediaFormatCatalog.FORMAT_VIDEO_H265 }
        assertFalse(optH265.isAudioOnly)
        assertEquals(55_000_000L, optH265.estimatedSizeBytes)

        val optFlac = options.first { it.formatId == MediaFormatCatalog.FORMAT_AUDIO_FLAC }
        assertTrue(optFlac.isAudioOnly)
        assertEquals("flac", optFlac.extension)
        assertEquals(5_000_000L, optFlac.estimatedSizeBytes)
    }

    @Test
    fun `sanitizeTitleToFileName removes illegal filesystem chars and updates extension for all formats`() {
        val sanitizedFlac = MediaFormatCatalog.sanitizeTitleToFileName(
            rawTitle = "Konser Kaydı: Bölüm 1 / 2 *HD*?|.mkv",
            formatId = MediaFormatCatalog.FORMAT_AUDIO_FLAC
        )
        assertEquals("Konser Kaydı_ Bölüm 1 _ 2 _HD___.flac", sanitizedFlac)

        val sanitizedMkv = MediaFormatCatalog.sanitizeTitleToFileName(
            rawTitle = "Film_Fragmani.webm",
            formatId = MediaFormatCatalog.FORMAT_VIDEO_MKV
        )
        assertEquals("Film_Fragmani.mkv", sanitizedMkv)
    }
}

package com.indirici.app.ui

import com.indirici.app.domain.exception.MediaEngineException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorPresentationTest {

    @Test
    fun `MediaEngineException contains short message, raw error, and yt-dlp version`() {
        val rawError = "ERROR: [generic] Unsupported URL: https://example.com/video"
        val version = "2024.08.06"
        val ex = MediaEngineException(
            message = "Bu link indirilemedi.",
            rawError = rawError,
            ytDlpVersion = version
        )

        assertEquals("Bu link indirilemedi.", ex.message)
        assertEquals(rawError, ex.rawError)
        assertEquals(version, ex.ytDlpVersion)
    }

    @Test
    fun `error detail includes installed yt-dlp version and engine raw error verbatim without DRM alteration`() {
        val drmErrorFromEngine = "ERROR: [generic] This video is DRM protected"
        val version = "2024.10.22"

        val detail = buildString {
            append("yt-dlp sürümü: ").appendLine(version)
            appendLine()
            append(drmErrorFromEngine)
        }.trim()

        assertTrue(detail.startsWith("yt-dlp sürümü: 2024.10.22"))
        assertTrue(detail.contains("ERROR: [generic] This video is DRM protected"))
    }

    @Test
    fun `AddDownloadUiState handles simplified error and errorDetail`() {
        val state = AddDownloadUiState(
            errorMessage = "Bu link indirilemedi.",
            errorDetail = "yt-dlp sürümü: 2024.10.22\n\nERROR: Private video"
        )

        assertEquals("Bu link indirilemedi.", state.errorMessage)
        assertTrue(state.errorDetail!!.contains("yt-dlp sürümü: 2024.10.22"))
        assertTrue(state.errorDetail!!.contains("ERROR: Private video"))
    }
}

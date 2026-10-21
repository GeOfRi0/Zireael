package com.indirici.app.engine

import com.indirici.app.domain.exception.DrmProtectedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DrmDetectorTest {

    @Test
    fun `assertNotDrm throws DrmProtectedException with exact Turkish message on mpd manifest`() {
        val ex = assertThrows(DrmProtectedException::class.java) {
            DrmDetector.assertNotDrm(url = "https://cdn.example.com/video/stream.mpd")
        }
        assertEquals("Bu içerik DRM korumalı, desteklenmiyor", ex.message)
    }

    @Test
    fun `assertNotDrm throws DrmProtectedException on Widevine header`() {
        val headers = mapOf("X-Widevine-License" to listOf("https://license.example.com"))
        val ex = assertThrows(DrmProtectedException::class.java) {
            DrmDetector.assertNotDrm(
                url = "https://cdn.example.com/video.mp4",
                headers = headers
            )
        }
        assertEquals("Bu içerik DRM korumalı, desteklenmiyor", ex.message)
    }

    @Test
    fun `assertNotDrmInErrorMessage throws DrmProtectedException when yt-dlp reports DRM`() {
        val ex = assertThrows(DrmProtectedException::class.java) {
            DrmDetector.assertNotDrmInErrorMessage("ERROR: [generic] This video is DRM protected")
        }
        assertEquals("Bu içerik DRM korumalı, desteklenmiyor", ex.message)
    }

    @Test
    fun `assertNotDrm allows normal direct file links`() {
        DrmDetector.assertNotDrm(
            url = "https://releases.ubuntu.com/24.04/ubuntu.iso",
            mimeType = "application/octet-stream"
        )
    }
}

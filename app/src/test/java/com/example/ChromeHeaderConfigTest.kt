package com.example

import com.example.data.model.ChromeHeaderConfig
import com.example.data.model.ChromePreset
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChromeHeaderConfigTest {

    @Test
    fun testChromeWindowsPresetHeaders() {
        val config = ChromeHeaderConfig(preset = ChromePreset.CHROME_WINDOWS)
        val request = Request.Builder().url("https://example.com/file.zip").build()
        val builder = request.newBuilder()
        config.applyTo(builder, "https://example.com/file.zip")
        val builtRequest = builder.build()

        val ua = builtRequest.header("User-Agent")
        assertNotNull(ua)
        assertTrue(ua!!.contains("Chrome"))
        assertTrue(ua.contains("Windows NT 10.0"))

        val secChUa = builtRequest.header("sec-ch-ua")
        assertNotNull(secChUa)
        assertTrue(secChUa!!.contains("Google Chrome"))

        val secChUaPlatform = builtRequest.header("sec-ch-ua-platform")
        assertEquals("\"Windows\"", secChUaPlatform)
    }

    @Test
    fun testCustomRefererAndCookieHeaders() {
        val config = ChromeHeaderConfig(
            preset = ChromePreset.CHROME_WINDOWS,
            customReferer = "https://source.example.org/downloads",
            customCookies = "session_token=abc123xyz; secure_flag=1",
            extraHeaders = mapOf("X-Download-Client" to "Zireael")
        )

        val request = Request.Builder().url("https://cdn.example.com/archive.zip").build()
        val builder = request.newBuilder()
        config.applyTo(builder, "https://cdn.example.com/archive.zip")
        val built = builder.build()

        assertEquals("https://source.example.org/downloads", built.header("Referer"))
        assertEquals("session_token=abc123xyz; secure_flag=1", built.header("Cookie"))
        assertEquals("Zireael", built.header("X-Download-Client"))
    }

    @Test
    fun testCustomUserAgentOverride() {
        val customUa = "Mozilla/5.0 (CustomBrowser/1.0; IDM)"
        val config = ChromeHeaderConfig(
            preset = ChromePreset.CUSTOM,
            customUserAgent = customUa
        )

        val request = Request.Builder().url("https://example.com/test.apk").build()
        val builder = request.newBuilder()
        config.applyTo(builder, "https://example.com/test.apk")
        val built = builder.build()

        assertEquals(customUa, built.header("User-Agent"))
    }
}

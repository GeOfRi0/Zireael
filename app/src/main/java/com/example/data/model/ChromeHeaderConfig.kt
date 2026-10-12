package com.example.data.model

import com.example.data.local.DownloadEntity
import okhttp3.Request
import org.json.JSONObject
import java.net.URI

/**
 * Modern Chrome browser presets allowing requests to accurately simulate
 * desktop and mobile Chrome browser fingerprints.
 */
enum class ChromePreset(
    val title: String,
    val userAgent: String,
    val platform: String,
    val isMobile: Boolean
) {
    CHROME_WINDOWS(
        title = "Chrome Windows (Masaüstü)",
        userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        platform = "\"Windows\"",
        isMobile = false
    ),
    CHROME_ANDROID(
        title = "Chrome Android (Mobil)",
        userAgent = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/UD1A.230803.041) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.6778.200 Mobile Safari/537.36",
        platform = "\"Android\"",
        isMobile = true
    ),
    CHROME_MAC(
        title = "Chrome macOS (Mac)",
        userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        platform = "\"macOS\"",
        isMobile = false
    ),
    CUSTOM(
        title = "Özel User-Agent",
        userAgent = "",
        platform = "\"Windows\"",
        isMobile = false
    )
}

/**
 * Flexible configuration for passing dynamic User-Agent, Referer, Cookie, and custom headers
 * to OkHttp network calls so that the download engine behaves identically to a genuine Google Chrome browser.
 */
data class ChromeHeaderConfig(
    val preset: ChromePreset = ChromePreset.CHROME_WINDOWS,
    val customUserAgent: String? = null,
    val customReferer: String? = null,
    val customCookies: String? = null,
    val extraHeaders: Map<String, String> = emptyMap()
) {
    val effectiveUserAgent: String
        get() = if (preset == ChromePreset.CUSTOM && !customUserAgent.isNullOrBlank()) {
            customUserAgent.trim()
        } else if (preset == ChromePreset.CUSTOM) {
            ChromePreset.CHROME_WINDOWS.userAgent
        } else {
            preset.userAgent
        }

    fun effectiveReferer(targetUrl: String): String {
        if (!customReferer.isNullOrBlank()) {
            return customReferer.trim()
        }
        val lower = targetUrl.lowercase()
        if (lower.contains("googlevideo.com") || lower.contains("youtube.com") || lower.contains("youtu.be")) {
            return "https://www.youtube.com/"
        }
        if (lower.contains("cdninstagram.com") || lower.contains("instagram.com")) {
            return "https://www.instagram.com/"
        }
        if (lower.contains("twimg.com") || lower.contains("twitter.com") || lower.contains("x.com")) {
            return "https://x.com/"
        }
        return try {
            val uri = URI(targetUrl)
            val scheme = uri.scheme ?: "https"
            val host = uri.host ?: ""
            if (host.isNotBlank()) "$scheme://$host/" else targetUrl
        } catch (e: Exception) {
            targetUrl
        }
    }

    /**
     * Applies full realistic Google Chrome HTTP headers and Client Hints to the OkHttp request builder.
     */
    fun applyTo(builder: Request.Builder, targetUrl: String) {
        val ua = effectiveUserAgent
        builder.header("User-Agent", ua)

        val ref = effectiveReferer(targetUrl)
        builder.header("Referer", ref)

        val lower = targetUrl.lowercase()
        if (lower.contains("googlevideo.com") || lower.contains("youtube.com")) {
            builder.header("Origin", "https://www.youtube.com")
        } else if (lower.contains("cdninstagram.com") || lower.contains("instagram.com")) {
            builder.header("Origin", "https://www.instagram.com")
        } else if (ref.isNotBlank()) {
            try {
                val originUri = URI(ref)
                val scheme = originUri.scheme ?: "https"
                val host = originUri.host
                if (!host.isNullOrBlank()) {
                    val portStr = if (originUri.port != -1 && originUri.port != 80 && originUri.port != 443) ":${originUri.port}" else ""
                    builder.header("Origin", "$scheme://$host$portStr")
                }
            } catch (e: Exception) {}
        }

        // Cookies
        if (!customCookies.isNullOrBlank()) {
            builder.header("Cookie", customCookies.trim())
        }

        // Realistic Chrome Client Hints (Sec-Ch-Ua)
        val brandVersion = "131"
        builder.header(
            "Sec-Ch-Ua",
            "\"Google Chrome\";v=\"$brandVersion\", \"Chromium\";v=\"$brandVersion\", \"Not_A Brand\";v=\"24\""
        )
        builder.header("Sec-Ch-Ua-Mobile", if (preset.isMobile) "?1" else "?0")
        builder.header("Sec-Ch-Ua-Platform", preset.platform)

        // Fetch Metadata headers tailored to media vs document
        val isMediaStream = lower.contains("videoplayback") || lower.contains(".mp4") ||
            lower.contains(".mkv") || lower.contains(".webm") || lower.contains(".m4a") ||
            lower.contains(".mp3") || lower.contains(".m3u8") || lower.contains("googlevideo.com")

        if (isMediaStream) {
            builder.header("Sec-Fetch-Dest", "video")
            builder.header("Sec-Fetch-Mode", "cors")
            builder.header("Sec-Fetch-Site", "cross-site")
        } else {
            builder.header("Sec-Fetch-Dest", "document")
            builder.header("Sec-Fetch-Mode", "navigate")
            builder.header("Sec-Fetch-Site", "same-origin")
            builder.header("Sec-Fetch-User", "?1")
            builder.header("Upgrade-Insecure-Requests", "1")
        }

        // Accept & Language headers matching genuine Chrome
        builder.header(
            "Accept",
            if (isMediaStream) "*/*" else "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,video/*,application/octet-stream,*/*;q=0.8"
        )
        builder.header("Accept-Language", "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7")

        // Prevent automatic gzip decompression on partial Range downloads so byte calculations stay exact
        builder.header("Accept-Encoding", "identity")

        // Any arbitrary user-supplied custom headers
        extraHeaders.forEach { (key, value) ->
            if (key.isNotBlank() && value.isNotBlank()) {
                builder.header(key.trim(), value.trim())
            }
        }
    }

    fun toExtraHeadersJson(): String? {
        if (extraHeaders.isEmpty()) return null
        return try {
            val json = JSONObject()
            extraHeaders.forEach { (k, v) -> json.put(k, v) }
            json.toString()
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        fun fromJson(jsonStr: String?): Map<String, String> {
            if (jsonStr.isNullOrBlank()) return emptyMap()
            return try {
                val json = JSONObject(jsonStr)
                val map = mutableMapOf<String, String>()
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    map[key] = json.optString(key)
                }
                map
            } catch (e: Exception) {
                emptyMap()
            }
        }

        fun parseHeadersText(text: String): Map<String, String> {
            val map = mutableMapOf<String, String>()
            text.lines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isNotBlank() && trimmed.contains(":")) {
                    val parts = trimmed.split(":", limit = 2)
                    if (parts.size == 2) {
                        val key = parts[0].trim()
                        val value = parts[1].trim()
                        if (key.isNotEmpty()) {
                            map[key] = value
                        }
                    }
                }
            }
            return map
        }

        fun formatHeadersText(headers: Map<String, String>): String {
            return headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        }

        fun fromEntity(entity: DownloadEntity): ChromeHeaderConfig {
            val storedUa = entity.userAgent
            val preset = when (storedUa) {
                null -> ChromePreset.CHROME_WINDOWS
                ChromePreset.CHROME_WINDOWS.userAgent -> ChromePreset.CHROME_WINDOWS
                ChromePreset.CHROME_ANDROID.userAgent -> ChromePreset.CHROME_ANDROID
                ChromePreset.CHROME_MAC.userAgent -> ChromePreset.CHROME_MAC
                else -> ChromePreset.CUSTOM
            }

            return ChromeHeaderConfig(
                preset = preset,
                customUserAgent = if (preset == ChromePreset.CUSTOM) storedUa else null,
                customReferer = entity.referer,
                customCookies = entity.cookies,
                extraHeaders = fromJson(entity.extraHeadersJson)
            )
        }
    }
}

fun DownloadEntity.toHeaderConfig(): ChromeHeaderConfig = ChromeHeaderConfig.fromEntity(this)

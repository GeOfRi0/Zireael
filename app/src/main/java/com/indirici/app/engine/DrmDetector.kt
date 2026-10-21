package com.indirici.app.engine

import com.indirici.app.domain.exception.DrmProtectedException

/**
 * URL, dosya uzantısı, MIME tipi, yanıt başlıkları veya yt-dlp hata mesajlarında
 * DRM koruması olup olmadığını denetler.
 * Tespit edildiği anda [DrmProtectedException] fırlatır ("Bu içerik DRM korumalı, desteklenmiyor").
 */
object DrmDetector {

    private val drmUrlIndicators = listOf(
        ".mpd",
        ".ism/manifest",
        ".isml/manifest",
        "widevine",
        "playready",
        "fairplay",
        "drm_token",
        "encryption=cenc"
    )

    private val drmMimeTypes = setOf(
        "application/dash+xml",
        "application/vnd.ms-sstr+xml",
        "application/x-mpegurl-drm"
    )

    private val drmHeaderKeys = listOf(
        "x-drm",
        "x-widevine",
        "x-playready",
        "content-protection"
    )

    private val drmErrorIndicators = listOf(
        "drm protected",
        "drm-protected",
        "uses drm",
        "widevine",
        "playready",
        "fairplay",
        "encrypted media"
    )

    fun assertNotDrm(
        url: String,
        mimeType: String? = null,
        headers: Map<String, List<String>> = emptyMap(),
        manifestSample: String? = null
    ) {
        val lowerUrl = url.lowercase()
        if (drmUrlIndicators.any { lowerUrl.contains(it) }) {
            throw DrmProtectedException()
        }

        val normalizedMime = mimeType?.substringBefore(';')?.trim()?.lowercase()
        if (normalizedMime != null && normalizedMime in drmMimeTypes) {
            throw DrmProtectedException()
        }

        for ((key, values) in headers) {
            val lowerKey = key.lowercase()
            if (drmHeaderKeys.any { lowerKey.contains(it) }) {
                throw DrmProtectedException()
            }
            val joinedValues = values.joinToString(" ").lowercase()
            if (joinedValues.contains("widevine") ||
                joinedValues.contains("playready") ||
                joinedValues.contains("fairplay") ||
                joinedValues.contains("cenc")
            ) {
                throw DrmProtectedException()
            }
        }

        if (manifestSample != null) {
            val lowerManifest = manifestSample.lowercase()
            if (lowerManifest.contains("#ext-x-key:method=sample-aes") ||
                lowerManifest.contains("urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed") || // Widevine UUID
                lowerManifest.contains("urn:uuid:9a04f079-9840-4286-ab92-e65be0885f95")    // PlayReady UUID
            ) {
                throw DrmProtectedException()
            }
        }
    }

    fun assertNotDrmInErrorMessage(errorMessage: String?) {
        if (errorMessage.isNullOrBlank()) return
        val lower = errorMessage.lowercase()
        if (drmErrorIndicators.any { lower.contains(it) }) {
            throw DrmProtectedException()
        }
    }
}

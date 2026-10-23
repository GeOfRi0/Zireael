package com.indirici.app.domain.model

/**
 * WebView Sniffer tarafından yakalanan video/ses akışı ve o anki oturum verilerini taşır.
 *
 * Güvenlik Kuralı:
 * Çerezler (cookie) ve User-Agent yalnızca RAM'de tutulur, loglanmaması için toString() içinde maskelenir.
 */
data class VideoDownloadTask(
    val url: String,
    val referer: String,
    val userAgent: String,
    val cookie: String,
    val mimeType: String? = null,
    val pageTitle: String? = null,
    val detectedLabel: String = "HLS Video Akışı (.m3u8)"
) {
    override fun toString(): String {
        return "VideoDownloadTask(url=$url, referer=$referer, userAgent=[REDACTED], cookie=[REDACTED], mimeType=$mimeType)"
    }
}

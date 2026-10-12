package com.example.data.engine

import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * AdBlocker:
 * High-performance ad and tracker filter for Zireael's In-App Web Browser.
 * Intercepts and drops known advertising networks, popups, and tracker requests
 * before they load in WebView.
 */
object AdBlocker {

    @Volatile
    var isEnabled: Boolean = true

    val blockedCount = AtomicInteger(0)

    // Known ad, tracking, and popup domains
    private val AD_HOSTS = hashSetOf(
        "doubleclick.net",
        "googleadservices.com",
        "googlesyndication.com",
        "adservice.google.com",
        "pagead2.googlesyndication.com",
        "amazon-adsystem.com",
        "popads.net",
        "popcash.net",
        "propellerads.com",
        "adcash.com",
        "adsterra.com",
        "outbrain.com",
        "taboola.com",
        "mgid.com",
        "revcontent.com",
        "zergnet.com",
        "adcolony.com",
        "applovin.com",
        "unityads.unity3d.com",
        "vungle.com",
        "ironsrc.com",
        "criteo.com",
        "criteo.net",
        "adroll.com",
        "rubiconproject.com",
        "pubmatic.com",
        "openx.net",
        "adnxs.com",
        "bidswitch.net",
        "scorecardresearch.com",
        "quantserve.com",
        "hotjar.com",
        "moatads.com",
        "advertising.com",
        "smartadserver.com",
        "inmobi.com",
        "exoclick.com",
        "trafficjunky.com",
        "juicyads.com",
        "ero-advertising.com",
        "zedo.com",
        "admob.com",
        "flurry.com"
    )

    // Common ad script and tracking URL path segments
    private val AD_PATH_KEYWORDS = listOf(
        "/googleads",
        "/pagead/",
        "/adservice/",
        "/adserver/",
        "/ads.js",
        "/prebid.js",
        "/popunder.",
        "/popunder/",
        "doubleclick.net",
        "googleadservices.com",
        "googlesyndication.com"
    )

    /**
     * Determines whether the given URL corresponds to an advertisement or tracker.
     */
    fun isAd(url: String?): Boolean {
        if (!isEnabled || url.isNullOrBlank()) return false

        try {
            val lowerUrl = url.lowercase()

            // 1. Check URL path keywords
            for (keyword in AD_PATH_KEYWORDS) {
                if (lowerUrl.contains(keyword)) {
                    blockedCount.incrementAndGet()
                    return true
                }
            }

            // 2. Extract and check host against known advertising domain set
            val uri = Uri.parse(url)
            val host = uri.host?.lowercase() ?: return false

            for (adHost in AD_HOSTS) {
                if (host == adHost || host.endsWith(".$adHost")) {
                    blockedCount.incrementAndGet()
                    return true
                }
            }
        } catch (e: Exception) {
            // Malformed URL or parse error
        }

        return false
    }

    /**
     * Creates an empty WebResourceResponse to cancel the request without causing errors.
     */
    fun createEmptyResponse(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "UTF-8",
            ByteArrayInputStream(ByteArray(0))
        )
    }

    fun resetCount() {
        blockedCount.set(0)
    }
}

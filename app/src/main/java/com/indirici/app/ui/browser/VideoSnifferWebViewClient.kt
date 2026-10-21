package com.indirici.app.ui.browser

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.indirici.app.domain.exception.DrmProtectedException
import com.indirici.app.domain.model.VideoDownloadTask
import com.indirici.app.engine.DetectedMediaCategory
import com.indirici.app.engine.DrmDetector
import com.indirici.app.engine.MediaFileDetector
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WebView üzerinden giden tüm HTTP/HTTPS isteklerini analiz eden özel WebViewClient.
 * - URL içinde .m3u8, .mpd geçen veya Content-Type başlığı application/vnd.apple.mpegurl olan istekleri,
 * - Ayrıca desteklenen ses (.wav, .ogg, .flac, .opus, .mp3, .m4a, .aac) ve
 *   video (.mkv, .mov, .webm, .avi, .wmv, .ts, .fmp4, .m4s, .mp4) akışlarını yakalar.
 * - Yakalanan URL ile birlikte Cookie, Referer ve User-Agent oturum verilerini VideoDownloadTask içinde
 *   UI katmanına (Callback ile) iletir.
 */
class VideoSnifferWebViewClient(
    private val onVideoDetected: (VideoDownloadTask) -> Unit,
    private val onPageStateChanged: (url: String, title: String?, isLoading: Boolean) -> Unit = { _, _, _ -> }
) : WebViewClient() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val probeExecutor = Executors.newSingleThreadExecutor()
    private val seenUrls: MutableSet<String> = Collections.synchronizedSet(HashSet())

    @Volatile
    var cachedUserAgent: String = DEFAULT_USER_AGENT

    @Volatile
    var currentPageUrl: String = ""
        private set

    @Volatile
    var currentPageTitle: String? = null
        private set

    private val headProbeClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        if (!url.isNullOrBlank()) {
            currentPageUrl = url
            seenUrls.clear()
        }
        view?.settings?.userAgentString?.takeIf { it.isNotBlank() }?.let {
            cachedUserAgent = it
        }
        onPageStateChanged(currentPageUrl, currentPageTitle, true)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        if (!url.isNullOrBlank()) {
            currentPageUrl = url
        }
        currentPageTitle = view?.title
        view?.settings?.userAgentString?.takeIf { it.isNotBlank() }?.let {
            cachedUserAgent = it
        }
        onPageStateChanged(currentPageUrl, currentPageTitle, false)
    }

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        val req = request ?: return super.shouldInterceptRequest(view, request)
        val uri = req.url ?: return super.shouldInterceptRequest(view, request)
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return super.shouldInterceptRequest(view, request)
        }

        val requestUrl = uri.toString()
        val requestHeaders = req.requestHeaders ?: emptyMap()

        val referer = requestHeaders["Referer"]
            ?: requestHeaders["referer"]
            ?: currentPageUrl

        val userAgent = requestHeaders["User-Agent"]
            ?: requestHeaders["user-agent"]
            ?: cachedUserAgent

        val evaluation = evaluateCandidateUrl(
            url = requestUrl,
            contentTypeHeader = requestHeaders["Content-Type"] ?: requestHeaders["Accept"]
        )

        if (evaluation != null) {
            emitTaskIfNew(
                url = requestUrl,
                referer = referer,
                userAgent = userAgent,
                mimeType = evaluation.mimeType,
                label = evaluation.label,
                isDrm = evaluation.isDrmProtected
            )
        } else if (looksLikePotentialStreamEndpoint(requestUrl)) {
            probeContentTypeAsync(
                url = requestUrl,
                referer = referer,
                userAgent = userAgent
            )
        }

        return super.shouldInterceptRequest(view, request)
    }

    private fun emitTaskIfNew(
        url: String,
        referer: String,
        userAgent: String,
        mimeType: String?,
        label: String,
        isDrm: Boolean
    ) {
        if (!seenUrls.add(url)) return

        val cookieManager = runCatching { CookieManager.getInstance() }.getOrNull()
        val cookieForStream = runCatching { cookieManager?.getCookie(url) }.getOrNull().orEmpty()
        val cookieForPage = if (cookieForStream.isBlank() && referer.isNotBlank()) {
            runCatching { cookieManager?.getCookie(referer) }.getOrNull().orEmpty()
        } else {
            ""
        }
        val effectiveCookie = cookieForStream.ifBlank { cookieForPage }

        val task = VideoDownloadTask(
            url = url,
            referer = referer,
            userAgent = userAgent,
            cookie = effectiveCookie,
            mimeType = mimeType,
            pageTitle = currentPageTitle,
            detectedLabel = label,
            isDrmProtected = isDrm
        )

        mainHandler.post {
            onVideoDetected(task)
        }
    }

    private fun probeContentTypeAsync(
        url: String,
        referer: String,
        userAgent: String
    ) {
        if (seenUrls.contains(url)) return
        probeExecutor.execute {
            runCatching {
                val cookie = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull().orEmpty()
                val reqBuilder = Request.Builder()
                    .url(url)
                    .head()
                    .header("User-Agent", userAgent)
                if (referer.isNotBlank()) reqBuilder.header("Referer", referer)
                if (cookie.isNotBlank()) reqBuilder.header("Cookie", cookie)

                headProbeClient.newCall(reqBuilder.build()).execute().use { response ->
                    val contentType = response.header("Content-Type")
                    val eval = evaluateCandidateUrl(url = url, contentTypeHeader = contentType)
                    if (eval != null) {
                        emitTaskIfNew(
                            url = url,
                            referer = referer,
                            userAgent = userAgent,
                            mimeType = contentType ?: eval.mimeType,
                            label = eval.label,
                            isDrm = eval.isDrmProtected
                        )
                    }
                }
            }
        }
    }

    internal data class SniffEvaluation(
        val mimeType: String?,
        val label: String,
        val isDrmProtected: Boolean
    )

    companion object {
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        fun evaluateCandidateUrl(
            url: String,
            contentTypeHeader: String? = null
        ): SniffEvaluation? {
            val lowerUrl = url.lowercase()
            val cleanMime = contentTypeHeader?.substringBefore(';')?.trim()?.lowercase()

            if (lowerUrl.contains(".mpd") || cleanMime == "application/dash+xml") {
                return SniffEvaluation(
                    mimeType = cleanMime ?: "application/dash+xml",
                    label = "DASH / MPD Akışı (DRM Korumalı)",
                    isDrmProtected = true
                )
            }

            try {
                DrmDetector.assertNotDrm(url = url, mimeType = cleanMime)
            } catch (_: DrmProtectedException) {
                return SniffEvaluation(
                    mimeType = cleanMime,
                    label = "DRM Korumalı Akış",
                    isDrmProtected = true
                )
            }

            if (lowerUrl.contains(".m3u8") ||
                cleanMime == "application/vnd.apple.mpegurl" ||
                cleanMime == "application/x-mpegurl" ||
                cleanMime == "audio/mpegurl"
            ) {
                return SniffEvaluation(
                    mimeType = cleanMime ?: "application/vnd.apple.mpegurl",
                    label = "HLS Video/Ses Akışı (.m3u8)",
                    isDrmProtected = false
                )
            }

            val ext = MediaFileDetector.extractExtensionFromUrl(url)
                ?: MediaFileDetector.resolveExtensionFromMime(cleanMime)

            return when (MediaFileDetector.classifyUrl(url, cleanMime)) {
                DetectedMediaCategory.DIRECT_AUDIO -> SniffEvaluation(
                    mimeType = cleanMime ?: MediaFileDetector.resolveMimeFromExtension(ext),
                    label = "Ses Dosyası (.${ext ?: "ses"})",
                    isDrmProtected = false
                )
                DetectedMediaCategory.DIRECT_VIDEO -> SniffEvaluation(
                    mimeType = cleanMime ?: MediaFileDetector.resolveMimeFromExtension(ext),
                    label = "Video Dosyası (.${ext ?: "video"})",
                    isDrmProtected = false
                )
                DetectedMediaCategory.STREAM_SEGMENT -> SniffEvaluation(
                    mimeType = cleanMime ?: MediaFileDetector.resolveMimeFromExtension(ext),
                    label = "Medya Akış Parçası (.${ext ?: "ts"})",
                    isDrmProtected = false
                )
                else -> null
            }
        }

        private fun looksLikePotentialStreamEndpoint(url: String): Boolean {
            val lower = url.lowercase()
            return (lower.contains("playlist") || lower.contains("manifest") || lower.contains("hls") || lower.contains("master")) &&
                !lower.endsWith(".js") &&
                !lower.endsWith(".css") &&
                !lower.endsWith(".png") &&
                !lower.endsWith(".jpg") &&
                !lower.endsWith(".svg") &&
                !lower.endsWith(".woff2")
        }
    }
}

package com.indirici.app.ui.browser

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.indirici.app.domain.exception.DrmProtectedException
import com.indirici.app.domain.model.VideoDownloadTask
import com.indirici.app.engine.DetectedMediaCategory
import com.indirici.app.engine.DrmDetector
import com.indirici.app.engine.FfmpegHlsEngine
import com.indirici.app.engine.HlsPlaylistInspector
import com.indirici.app.engine.MediaFileDetector
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * WebView üzerinden giden tüm HTTP/HTTPS isteklerini analiz eden özel WebViewClient.
 * - URL içinde .m3u8, .mpd geçen veya Content-Type başlığı application/vnd.apple.mpegurl olan istekleri,
 * - Ayrıca desteklenen ses (.wav, .ogg, .flac, .opus, .mp3, .m4a, .aac) ve
 *   video (.mkv, .mov, .webm, .avi, .wmv, .mp4) dosyalarını yakalar.
 * - HLS parçaları (.ts, .m4s, seg-N.mp4 vb.) tek başına listelenmez; bunun yerine ait oldukları
 *   m3u8 listesi sunulur (tek parça indirilip dosyanın bozuk çıkmasını önler).
 * - URL'sinde .m3u8 geçmeyen gizli manifestler, XHR/fetch yanıtlarının içeriği (#EXTM3U) JS kancası ile
 *   ve şüpheli uç noktalara yapılan küçük bir GET yoklaması ile yakalanır (iframe'ler ve Service Worker dahil).
 * - Yakalanan URL ile birlikte Cookie, Referer ve User-Agent oturum verilerini VideoDownloadTask içinde
 *   UI katmanına (Callback ile) iletir.
 */
class VideoSnifferWebViewClient(
    private val onVideoDetected: (VideoDownloadTask) -> Unit,
    private val onPageStateChanged: (url: String, title: String?, isLoading: Boolean) -> Unit = { _, _, _ -> },
    sharedHttpClient: OkHttpClient? = null
) : WebViewClient() {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Manifest analizi sıralı yapılır: ana liste, alt listelerinden önce işlenip onları "çocuk" olarak işaretler. */
    private val manifestExecutor = Executors.newSingleThreadExecutor()
    private val probeExecutor = Executors.newFixedThreadPool(2)

    private val seenUrls: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val inspectedPlaylists: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val childPlaylistUrls: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val segmentDirectories: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val probedUrls: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val probeBudget = AtomicInteger(MAX_PROBES_PER_PAGE)

    @Volatile
    private var documentStartScriptInstalled = false

    @Volatile
    var cachedUserAgent: String = DEFAULT_USER_AGENT

    @Volatile
    var currentPageUrl: String = ""
        private set

    @Volatile
    var currentPageTitle: String? = null
        private set

    private val headProbeClient: OkHttpClient = (sharedHttpClient?.newBuilder() ?: OkHttpClient.Builder())
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * WebView'a bu istemciyi, XHR/fetch manifest kancasını (tüm iframe'lerde, sayfa scriptlerinden önce)
     * ve Service Worker istek dinleyicisini bağlar. loadUrl çağrısından ÖNCE çağrılmalıdır.
     */
    fun attachTo(webView: WebView) {
        webView.addJavascriptInterface(SnifferJsBridge(), JS_BRIDGE_NAME)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            documentStartScriptInstalled = runCatching {
                WebViewCompat.addDocumentStartJavaScript(webView, MANIFEST_HOOK_SCRIPT, setOf("*"))
                true
            }.getOrDefault(false)
        }
        runCatching {
            ServiceWorkerController.getInstance().setServiceWorkerClient(object : ServiceWorkerClient() {
                override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
                    runCatching { inspectRequest(request) }
                    return null
                }
            })
        }
        webView.webViewClient = this
    }

    fun shutdown() {
        runCatching { manifestExecutor.shutdownNow() }
        runCatching { probeExecutor.shutdownNow() }
        runCatching { ServiceWorkerController.getInstance().setServiceWorkerClient(null) }
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        if (!url.isNullOrBlank()) {
            currentPageUrl = url
            resetPageState()
        }
        view?.settings?.userAgentString?.takeIf { it.isNotBlank() }?.let {
            cachedUserAgent = it
        }
        if (!documentStartScriptInstalled) {
            runCatching { view?.evaluateJavascript(MANIFEST_HOOK_SCRIPT, null) }
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
        if (!documentStartScriptInstalled) {
            runCatching { view?.evaluateJavascript(MANIFEST_HOOK_SCRIPT, null) }
        }
        onPageStateChanged(currentPageUrl, currentPageTitle, false)
    }

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        val req = request ?: return null
        runCatching { inspectRequest(req) }
        return super.shouldInterceptRequest(view, req)
    }

    private fun resetPageState() {
        seenUrls.clear()
        inspectedPlaylists.clear()
        childPlaylistUrls.clear()
        segmentDirectories.clear()
        probedUrls.clear()
        probeBudget.set(MAX_PROBES_PER_PAGE)
    }

    private fun inspectRequest(req: WebResourceRequest) {
        val uri = req.url ?: return
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return

        val requestUrl = uri.toString()
        val requestHeaders = req.requestHeaders ?: emptyMap()
        val referer = headerIgnoreCase(requestHeaders, "Referer") ?: currentPageUrl
        val userAgent = headerIgnoreCase(requestHeaders, "User-Agent") ?: cachedUserAgent

        val evaluation = evaluateCandidateUrl(
            url = requestUrl,
            contentTypeHeader = headerIgnoreCase(requestHeaders, "Content-Type")
                ?: headerIgnoreCase(requestHeaders, "Accept")
        )

        when {
            evaluation == null -> {
                if (looksLikePotentialStreamEndpoint(requestUrl, req.method, req.isForMainFrame, requestHeaders)) {
                    probeContentAsync(url = requestUrl, referer = referer, userAgent = userAgent)
                }
            }
            evaluation.isHlsManifest -> {
                scheduleManifestInspection(
                    url = requestUrl,
                    referer = referer,
                    userAgent = userAgent,
                    mimeType = evaluation.mimeType,
                    knownContent = null
                )
            }
            // .ts / .m4s gibi HLS parçaları tek başına indirilirse yalnızca birkaç saniyelik bozuk dosya çıkar.
            evaluation.category == DetectedMediaCategory.STREAM_SEGMENT -> Unit
            isLikelyHlsFragment(requestUrl) -> Unit
            else -> emitTaskIfNew(
                url = requestUrl,
                referer = referer,
                userAgent = userAgent,
                mimeType = evaluation.mimeType,
                label = evaluation.label,
                isDrm = evaluation.isDrmProtected
            )
        }
    }

    /** fMP4 HLS parçaları çoğu zaman .mp4/.m4a/.aac uzantılıdır; bilinen liste dizinlerindeki veya parça adlı dosyaları ele. */
    private fun isLikelyHlsFragment(url: String): Boolean {
        if (looksLikeSegmentFileName(url)) return true
        val dir = HlsPlaylistInspector.directoryOf(url)
        return segmentDirectories.contains(dir)
    }

    /** Kök dizin ("/") eklenmez; aksi halde sitedeki tüm .mp4 dosyaları parça sanılırdı. */
    private fun rememberSegmentDirectory(url: String) {
        val dir = HlsPlaylistInspector.directoryOf(url)
        val path = runCatching { URI(dir).path }.getOrNull() ?: dir.substringAfter("://").substringAfter('/', "")
        if (path.trim('/').isNotEmpty()) segmentDirectories.add(dir)
    }

    private fun scheduleManifestInspection(
        url: String,
        referer: String,
        userAgent: String,
        mimeType: String?,
        knownContent: String?
    ) {
        if (childPlaylistUrls.contains(url) || inspectedPlaylists.contains(url)) return
        runCatching {
            manifestExecutor.execute {
                runCatching { inspectManifest(url, referer, userAgent, mimeType, knownContent) }
            }
        }
    }

    /**
     * Manifesti indirip (veya JS kancasından gelen içeriği kullanıp) ana liste / alt liste ayrımı yapar.
     * Ana listenin varyantları ve ayrı ses listeleri kullanıcıya ayrıca gösterilmez; bunlar indirilirse
     * sessiz veya görüntüsüz dosya ortaya çıkar.
     */
    private fun inspectManifest(
        url: String,
        referer: String,
        userAgent: String,
        mimeType: String?,
        knownContent: String?
    ) {
        if (childPlaylistUrls.contains(url)) return
        if (!inspectedPlaylists.add(url)) return

        val content = knownContent ?: fetchText(url, referer, userAgent, MAX_MANIFEST_BYTES, rangeEnd = null)?.body
        var label = "HLS Video/Ses Akışı (.m3u8)"
        var isDrm = false

        if (content != null && HlsPlaylistInspector.isPlaylistContent(content)) {
            isDrm = try {
                DrmDetector.assertNotDrm(url = url, manifestSample = content)
                false
            } catch (_: DrmProtectedException) {
                true
            }
            val summary = HlsPlaylistInspector.parse(url, content)
            if (summary.isMaster) {
                childPlaylistUrls.addAll(summary.childPlaylistUris)
                summary.childPlaylistUris.forEach { rememberSegmentDirectory(it) }
                // Önceden alt liste olarak listelenmiş olabilecek varyantları UI'dan geri çekemiyoruz;
                // ama yeni gelenler engellenir.
                label = buildString {
                    append("HLS Ana Liste")
                    if (summary.variantCount > 0) append(" • ${summary.variantCount} kalite")
                    summary.maxHeight?.let { append(" • en yüksek ${it}p") }
                }
            } else {
                rememberSegmentDirectory(url)
                summary.segmentUris.firstOrNull()?.let { rememberSegmentDirectory(it) }
                label = buildString {
                    append("HLS Akışı")
                    if (summary.segmentUris.isNotEmpty()) append(" • ${summary.segmentUris.size} parça")
                    if (summary.isEndList && summary.totalDurationSec > 0) {
                        append(" • ").append(formatDuration(summary.totalDurationSec))
                    } else if (!summary.isEndList) {
                        append(" • canlı/akan liste")
                    }
                }
            }
        }

        emitTaskIfNew(
            url = url,
            referer = referer,
            userAgent = userAgent,
            mimeType = mimeType ?: "application/vnd.apple.mpegurl",
            label = label,
            isDrm = isDrm
        )
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

        val effectiveCookie = resolveCookie(url, referer)

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

    private fun resolveCookie(url: String, referer: String): String {
        val cookieManager = runCatching { CookieManager.getInstance() }.getOrNull()
        val cookieForStream = runCatching { cookieManager?.getCookie(url) }.getOrNull().orEmpty()
        if (cookieForStream.isNotBlank() || referer.isBlank()) return cookieForStream
        return runCatching { cookieManager?.getCookie(referer) }.getOrNull().orEmpty()
    }

    /**
     * URL'sinde .m3u8 geçmeyen (ör. /playlist.php?id=, /hls/abc, .txt) manifestleri yakalamak için
     * küçük bir GET (Range) yoklaması yapar. Birçok sunucu HEAD desteklemediği ve Content-Type olarak
     * text/plain veya application/octet-stream döndürdüğü için içeriğin #EXTM3U ile başlaması da kontrol edilir.
     */
    private fun probeContentAsync(
        url: String,
        referer: String,
        userAgent: String
    ) {
        if (seenUrls.contains(url) || inspectedPlaylists.contains(url)) return
        if (!probedUrls.add(url)) return
        if (probeBudget.getAndDecrement() <= 0) return
        runCatching {
            probeExecutor.execute {
                runCatching {
                    val result = fetchText(url, referer, userAgent, PROBE_BYTES, rangeEnd = PROBE_BYTES - 1) ?: return@runCatching
                    val cleanMime = result.contentType?.substringBefore(';')?.trim()?.lowercase()
                    val isHlsByMime = cleanMime == "application/vnd.apple.mpegurl" ||
                        cleanMime == "application/x-mpegurl" ||
                        cleanMime == "audio/mpegurl" ||
                        cleanMime == "audio/x-mpegurl"
                    if (isHlsByMime || HlsPlaylistInspector.isPlaylistContent(result.body)) {
                        scheduleManifestInspection(
                            url = result.finalUrl,
                            referer = referer,
                            userAgent = userAgent,
                            mimeType = "application/vnd.apple.mpegurl",
                            knownContent = null
                        )
                        return@runCatching
                    }
                    val eval = evaluateCandidateUrl(url = url, contentTypeHeader = result.contentType)
                    if (eval != null && !eval.isHlsManifest &&
                        eval.category != DetectedMediaCategory.STREAM_SEGMENT &&
                        !isLikelyHlsFragment(url)
                    ) {
                        emitTaskIfNew(
                            url = url,
                            referer = referer,
                            userAgent = userAgent,
                            mimeType = result.contentType ?: eval.mimeType,
                            label = eval.label,
                            isDrm = eval.isDrmProtected
                        )
                    }
                }
            }
        }
    }

    private data class FetchResult(val finalUrl: String, val contentType: String?, val body: String)

    private fun fetchText(
        url: String,
        referer: String,
        userAgent: String,
        maxBytes: Int,
        rangeEnd: Int?
    ): FetchResult? {
        val cookie = resolveCookie(url, referer)
        val reqBuilder = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", userAgent)
            .header("Accept", "*/*")
        if (rangeEnd != null) reqBuilder.header("Range", "bytes=0-$rangeEnd")
        if (referer.isNotBlank()) {
            reqBuilder.header("Referer", referer)
            FfmpegHlsEngine.originOf(referer)?.let { reqBuilder.header("Origin", it) }
        }
        if (cookie.isNotBlank()) reqBuilder.header("Cookie", cookie)

        return headProbeClient.newCall(reqBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val body = response.body ?: return@use null
            val bytes = readUpTo(body.byteStream(), maxBytes)
            FetchResult(
                finalUrl = response.request.url.toString(),
                contentType = response.header("Content-Type"),
                body = String(bytes, Charsets.UTF_8)
            )
        }
    }

    private fun readUpTo(input: java.io.InputStream, maxBytes: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() < maxBytes) {
            val read = input.read(buffer, 0, minOf(buffer.size, maxBytes - out.size()))
            if (read <= 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /**
     * Sayfa/iframe içindeki XHR ve fetch yanıtlarını izleyen JS kancasının köprüsü.
     * frameUrl, manifesti isteyen iframe'in adresidir; CDN'ler çoğu zaman Referer olarak bunu bekler.
     */
    inner class SnifferJsBridge {
        @JavascriptInterface
        fun onManifest(url: String?, frameUrl: String?, content: String?) {
            if (content.isNullOrEmpty() || content.length > MAX_MANIFEST_BYTES) return
            if (!HlsPlaylistInspector.isPlaylistContent(content)) return
            val referer = frameUrl?.takeIf { it.startsWith("http") } ?: currentPageUrl
            val manifestUrl = url.orEmpty()

            if (manifestUrl.startsWith("http://") || manifestUrl.startsWith("https://")) {
                scheduleManifestInspection(
                    url = manifestUrl,
                    referer = referer,
                    userAgent = cachedUserAgent,
                    mimeType = "application/vnd.apple.mpegurl",
                    knownContent = content
                )
                return
            }

            // blob: veya data: ile verilen ana listede varyant adresleri mutlak ise en iyi varyantı sun.
            // Baz olarak "about:blank" verilir: göreli adresler çözülemez ve yanlış hosta yönlenmez.
            val summary = runCatching { HlsPlaylistInspector.parse("about:blank", content) }.getOrNull() ?: return
            val best = summary.bestVariantUri ?: return
            if (best.startsWith("http://") || best.startsWith("https://")) {
                scheduleManifestInspection(
                    url = best,
                    referer = referer,
                    userAgent = cachedUserAgent,
                    mimeType = "application/vnd.apple.mpegurl",
                    knownContent = null
                )
            }
        }
    }

    data class SniffEvaluation(
        val mimeType: String?,
        val label: String,
        val isDrmProtected: Boolean,
        val category: DetectedMediaCategory = DetectedMediaCategory.UNKNOWN
    ) {
        val isHlsManifest: Boolean get() = category == DetectedMediaCategory.HLS_MANIFEST
    }

    companion object {
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

        private const val JS_BRIDGE_NAME = "__IndiriciSniffer"
        private const val MAX_PROBES_PER_PAGE = 40
        private const val PROBE_BYTES = 4096
        private const val MAX_MANIFEST_BYTES = 2_000_000

        private val STATIC_ASSET_EXTENSIONS = setOf(
            "js", "mjs", "css", "png", "jpg", "jpeg", "gif", "webp", "avif", "svg", "ico",
            "woff", "woff2", "ttf", "otf", "eot", "map", "wasm", "html", "htm", "vtt", "srt"
        )

        private val SERVER_SCRIPT_EXTENSIONS = setOf("txt", "php", "asp", "aspx", "jsp", "cgi", "m3u")

        private val STREAM_ENDPOINT_KEYWORDS = listOf(
            "playlist", "manifest", "hls", "master", "m3u", "chunklist", "stream", "/live/", "/vod/",
            "/play", "source", "video"
        )

        private val SEGMENT_NAME_REGEX = Regex(
            """(?:^|[/_\-.])(?:seg(?:ment)?|frag(?:ment)?|chunk|media_?\d|part)[-_]?\d+[^/]*\.(?:mp4|m4v|m4a|aac|mp3|webm|ts)$""",
            RegexOption.IGNORE_CASE
        )

        /**
         * Sayfa scriptlerinden önce (tüm iframe'lerde) çalışan kanca: XHR/fetch yanıtı #EXTM3U ile başlıyorsa
         * URL'yi, iframe adresini ve içeriği köprüye iletir. URL'sinde ".m3u8" geçmeyen veya
         * arraybuffer olarak indirilen gizli manifestleri de yakalar.
         */
        internal val MANIFEST_HOOK_SCRIPT: String = """
            (function () {
              try {
                if (window.__indiriciHlsHook) return;
                window.__indiriciHlsHook = true;
                var bridge = window.$JS_BRIDGE_NAME;
                if (!bridge || !bridge.onManifest) return;
                var MAX = $MAX_MANIFEST_BYTES;
                function report(url, text) {
                  try {
                    if (typeof text !== 'string' || text.length > MAX) return;
                    var head = text.slice(0, 64).replace(/^[\ufeff\s]+/, '');
                    if (head.indexOf('#EXTM3U') !== 0) return;
                    bridge.onManifest(String(url || ''), String(location.href), text);
                  } catch (e) {}
                }
                function bufToText(buf) {
                  try {
                    if (!buf || buf.byteLength < 7 || buf.byteLength > MAX) return null;
                    var h = new Uint8Array(buf, 0, Math.min(buf.byteLength, 16));
                    var s = '';
                    for (var i = 0; i < h.length; i++) s += String.fromCharCode(h[i]);
                    if (s.replace(/^[\u00ef\u00bb\u00bf\s]+/, '').indexOf('#EXTM3U') !== 0) return null;
                    return new TextDecoder('utf-8').decode(buf);
                  } catch (e) { return null; }
                }
                var X = window.XMLHttpRequest;
                if (X && X.prototype) {
                  var origOpen = X.prototype.open, origSend = X.prototype.send;
                  X.prototype.open = function (method, url) {
                    try { this.__indiriciUrl = url; } catch (e) {}
                    return origOpen.apply(this, arguments);
                  };
                  X.prototype.send = function () {
                    var xhr = this;
                    try {
                      xhr.addEventListener('load', function () {
                        try {
                          var u = xhr.responseURL || xhr.__indiriciUrl;
                          var t = xhr.responseType;
                          if (t === '' || t === 'text') report(u, xhr.responseText);
                          else if (t === 'arraybuffer') { var s = bufToText(xhr.response); if (s) report(u, s); }
                        } catch (e) {}
                      });
                    } catch (e) {}
                    return origSend.apply(this, arguments);
                  };
                }
                if (window.fetch) {
                  var origFetch = window.fetch;
                  window.fetch = function () {
                    var p = origFetch.apply(this, arguments);
                    try {
                      p.then(function (res) {
                        try {
                          var ct = (res.headers.get('content-type') || '').toLowerCase();
                          var len = parseInt(res.headers.get('content-length') || '0', 10) || 0;
                          var isHlsType = ct.indexOf('mpegurl') >= 0;
                          if (!isHlsType && (len <= 0 || len > MAX)) return;
                          if (!isHlsType && (ct.indexOf('video/') === 0 || ct.indexOf('audio/') === 0 || ct.indexOf('image/') === 0)) return;
                          res.clone().text().then(function (txt) { report(res.url, txt); }, function () {});
                        } catch (e) {}
                      }, function () {});
                    } catch (e) {}
                    return p;
                  };
                }
              } catch (e) {}
            })();
        """.trimIndent()

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
                cleanMime == "audio/mpegurl" ||
                cleanMime == "audio/x-mpegurl"
            ) {
                return SniffEvaluation(
                    mimeType = cleanMime?.takeIf { it.contains("mpegurl") } ?: "application/vnd.apple.mpegurl",
                    label = "HLS Video/Ses Akışı (.m3u8)",
                    isDrmProtected = false,
                    category = DetectedMediaCategory.HLS_MANIFEST
                )
            }

            val ext = MediaFileDetector.extractExtensionFromUrl(url)
                ?: MediaFileDetector.resolveExtensionFromMime(cleanMime)

            return when (val category = MediaFileDetector.classifyUrl(url, cleanMime)) {
                DetectedMediaCategory.DIRECT_AUDIO -> SniffEvaluation(
                    mimeType = cleanMime ?: MediaFileDetector.resolveMimeFromExtension(ext),
                    label = "Ses Dosyası (.${ext ?: "ses"})",
                    isDrmProtected = false,
                    category = category
                )
                DetectedMediaCategory.DIRECT_VIDEO -> SniffEvaluation(
                    mimeType = cleanMime ?: MediaFileDetector.resolveMimeFromExtension(ext),
                    label = "Video Dosyası (.${ext ?: "video"})",
                    isDrmProtected = false,
                    category = category
                )
                DetectedMediaCategory.STREAM_SEGMENT -> SniffEvaluation(
                    mimeType = cleanMime ?: MediaFileDetector.resolveMimeFromExtension(ext),
                    label = "Medya Akış Parçası (.${ext ?: "ts"})",
                    isDrmProtected = false,
                    category = category
                )
                DetectedMediaCategory.HLS_MANIFEST -> SniffEvaluation(
                    mimeType = cleanMime ?: "application/vnd.apple.mpegurl",
                    label = "HLS Video/Ses Akışı (.m3u8)",
                    isDrmProtected = false,
                    category = category
                )
                else -> null
            }
        }

        /** seg-12.mp4, chunk_3.m4a, frag5.ts gibi HLS/DASH parça adlarını tanır. */
        fun looksLikeSegmentFileName(url: String): Boolean {
            val path = url.substringBefore('#').substringBefore('?')
            return SEGMENT_NAME_REGEX.containsMatchIn(path)
        }

        internal fun looksLikePotentialStreamEndpoint(
            url: String,
            method: String? = "GET",
            isMainFrame: Boolean = false,
            requestHeaders: Map<String, String> = emptyMap()
        ): Boolean {
            if (isMainFrame) return false
            if (method != null && !method.equals("GET", ignoreCase = true)) return false
            val accept = headerIgnoreCase(requestHeaders, "Accept")?.lowercase().orEmpty()
            if (accept.startsWith("image/") || accept.startsWith("text/css")) return false

            val ext = MediaFileDetector.extractExtensionFromUrl(url)
            if (ext != null && ext in STATIC_ASSET_EXTENSIONS) return false
            if (ext != null && ext in SERVER_SCRIPT_EXTENSIONS) return true

            val lower = url.lowercase().substringBefore('#')
            return STREAM_ENDPOINT_KEYWORDS.any { lower.contains(it) }
        }

        private fun headerIgnoreCase(headers: Map<String, String>, name: String): String? {
            headers[name]?.let { return it }
            return headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
        }

        private fun formatDuration(seconds: Double): String {
            val total = seconds.toLong()
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
        }
    }
}

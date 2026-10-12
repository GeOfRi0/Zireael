package com.example.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Build
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Hd
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.ui.window.Dialog
import com.example.ui.components.VideoQualitySelectorDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.engine.AdBlocker
import com.example.data.engine.MediaSniffer
import com.example.data.engine.OkHttpProvider
import com.example.data.model.ChromeHeaderConfig
import com.example.data.model.ChromePreset
import com.example.data.model.ExtractedMedia
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.Request
import com.example.ui.theme.AccentGreen
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.DarkCard
import com.example.ui.theme.DarkCardBorder
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.viewmodel.DownloadViewModel

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebSnifferBrowserScreen(
    viewModel: DownloadViewModel
) {
    val context = LocalContext.current
    val browserUrl by viewModel.browserUrl.collectAsStateWithLifecycle()
    val capturedMediaList by viewModel.capturedMediaList.collectAsStateWithLifecycle()
    val showQualitySelector by viewModel.showQualitySelector.collectAsStateWithLifecycle()
    val qualityOptions by viewModel.qualityOptions.collectAsStateWithLifecycle()
    val selectedThreads by viewModel.selectedThreads.collectAsStateWithLifecycle()
    val isAnalyzingResolutions by viewModel.isAnalyzingResolutions.collectAsStateWithLifecycle()

    var urlInputText by remember { mutableStateOf(browserUrl) }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var pageTitle by remember { mutableStateOf("") }
    var isDesktopMode by remember { mutableStateOf(true) }
    var showCapturedListSheet by remember { mutableStateOf(false) }

    LaunchedEffect(browserUrl) {
        if (browserUrl.isNotBlank() && browserUrl != urlInputText) {
            urlInputText = browserUrl
            webViewInstance?.loadUrl(browserUrl)
        }
    }

    BackHandler(enabled = canGoBack) {
        webViewInstance?.goBack()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF070B14))
    ) {
        // 1. Navigation & Address Bar
        Surface(
            color = DarkSurface,
            tonalElevation = 6.dp,
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { webViewInstance?.goBack() },
                        enabled = canGoBack,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Geri",
                            tint = if (canGoBack) TextPrimary else TextMuted
                        )
                    }

                    IconButton(
                        onClick = { webViewInstance?.goForward() },
                        enabled = canGoForward,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "İleri",
                            tint = if (canGoForward) TextPrimary else TextMuted
                        )
                    }

                    IconButton(
                        onClick = { webViewInstance?.reload() },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Yenile",
                            tint = CyberCyan
                        )
                    }

                    // URL Input Field
                    OutlinedTextField(
                        value = urlInputText,
                        onValueChange = { urlInputText = it },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("browser_address_bar"),
                        placeholder = {
                            Text("URL veya site ara...", fontSize = 12.sp, color = TextMuted)
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(
                            onGo = {
                                val target = normalizeBrowserUrl(urlInputText)
                                urlInputText = target
                                webViewInstance?.loadUrl(target)
                            }
                        ),
                        shape = RoundedCornerShape(24.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = DarkSurfaceVariant,
                            unfocusedContainerColor = DarkSurfaceVariant,
                            focusedBorderColor = CyberCyan,
                            unfocusedBorderColor = DarkCardBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        trailingIcon = {
                            if (urlInputText.isNotBlank()) {
                                IconButton(
                                    onClick = { urlInputText = "" },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Temizle",
                                        tint = TextMuted,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    )

                    Spacer(modifier = Modifier.width(4.dp))

                    // Desktop mode toggle
                    IconButton(
                        onClick = {
                            isDesktopMode = !isDesktopMode
                            webViewInstance?.let { wv ->
                                wv.settings.userAgentString = if (isDesktopMode) {
                                    DESKTOP_CHROME_USER_AGENT
                                } else {
                                    ChromePreset.CHROME_ANDROID.userAgent
                                }
                                wv.settings.mediaPlaybackRequiresUserGesture = false
                                wv.reload()
                            }
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = if (isDesktopMode) Icons.Default.Computer else Icons.Default.Smartphone,
                            contentDescription = "Masaüstü Modu",
                            tint = if (isDesktopMode) ElectricBlue else TextSecondary
                        )
                    }
                }

                // Quick Site Shortcuts
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val presets = listOf(
                        "YouTube" to "https://m.youtube.com",
                        "Instagram" to "https://www.instagram.com",
                        "X / Twitter" to "https://x.com",
                        "TikTok" to "https://www.tiktok.com",
                        "Google" to "https://www.google.com"
                    )

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = CyberCyan.copy(alpha = 0.18f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, CyberCyan),
                        modifier = Modifier.clickable {
                            webViewInstance?.evaluateJavascript(
                                "(function(){ if (window.triggerMediaSniffing) window.triggerMediaSniffing(); })();",
                                null
                            )
                            android.widget.Toast.makeText(context, "Sayfadaki video akışları ve oynatıcılar otomatik tetiklendi ⚡", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = null,
                                tint = CyberCyan,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = "Videoları Tara",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = CyberCyan
                            )
                        }
                    }

                    presets.forEach { (name, target) ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = DarkSurfaceVariant,
                            border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder),
                            modifier = Modifier.clickable {
                                urlInputText = target
                                webViewInstance?.loadUrl(target)
                            }
                        ) {
                            Text(
                                text = name,
                                fontSize = 11.sp,
                                color = TextSecondary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                if (isLoading) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .height(2.dp),
                        color = CyberCyan,
                        trackColor = Color.Transparent
                    )
                }
            }
        }

        // 2. Main WebView Area with Floating Sniffer Badge
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )

                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            mediaPlaybackRequiresUserGesture = false
                            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                safeBrowsingEnabled = true
                            }
                            userAgentString = DESKTOP_CHROME_USER_AGENT
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            setSupportZoom(true)
                            builtInZoomControls = true
                            displayZoomControls = false
                            allowFileAccess = true
                            allowContentAccess = true
                            javaScriptCanOpenWindowsAutomatically = true
                            setSupportMultipleWindows(false)
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun getDefaultVideoPoster(): Bitmap? {
                                return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                            }
                        }

                        // Intercept direct file downloads (APK, ZIP, MP4, Images, etc.)
                        setDownloadListener { downloadUrl, userAgent, contentDisposition, mimetype, _ ->
                            val cookieManager = CookieManager.getInstance()
                            val cookies = try {
                                cookieManager.getCookie(downloadUrl) ?: cookieManager.getCookie(url)
                            } catch (e: Exception) {
                                null
                            }
                            val referer = url ?: urlInputText
                            val effectiveUa = if (!userAgent.isNullOrBlank()) {
                                userAgent
                            } else {
                                settings.userAgentString.ifBlank {
                                    if (isDesktopMode) DESKTOP_CHROME_USER_AGENT else ChromePreset.CHROME_ANDROID.userAgent
                                }
                            }
                            val headerConfig = ChromeHeaderConfig(
                                preset = ChromePreset.CUSTOM,
                                customUserAgent = effectiveUa,
                                customReferer = referer,
                                customCookies = cookies
                            )
                            val guessedName = URLUtil.guessFileName(downloadUrl, contentDisposition, mimetype)
                            val title = guessedName.ifBlank { pageTitle.ifBlank { "İndirilebilir Dosya" } }
                            val mime = mimetype?.ifBlank { null } ?: when {
                                downloadUrl.contains(".apk", ignoreCase = true) -> "application/vnd.android.package-archive"
                                downloadUrl.contains(".zip", ignoreCase = true) -> "application/zip"
                                downloadUrl.contains(".mp4", ignoreCase = true) -> "video/mp4"
                                MediaSniffer.isImage(downloadUrl, mimetype.orEmpty()) -> "image/jpeg"
                                else -> "application/octet-stream"
                            }

                            post {
                                viewModel.addCapturedMedia(
                                    url = downloadUrl,
                                    title = title,
                                    mimeType = mime,
                                    headerConfig = headerConfig
                                )
                                android.widget.Toast.makeText(
                                    context,
                                    "İndirme Yakalandı: $title 📥",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        }

                        // JavaScript Interface to catch HTML5 video events (play, loadeddata, src changes), XHR/fetch streams, and download links
                        addJavascriptInterface(
                            object {
                                @JavascriptInterface
                                fun onMediaDetected(url: String, title: String) {
                                    onMediaDetectedWithDetails(url, title, "")
                                }

                                @JavascriptInterface
                                fun onMediaDetectedWithDetails(url: String, title: String, mimeType: String) {
                                    handleDetectedMedia(url, title, mimeType)
                                }

                                @JavascriptInterface
                                fun onVideoPlayed(url: String, title: String, mimeType: String) {
                                    handleDetectedMedia(url, title, mimeType)
                                }

                                private val ALLOWED_JS_URL_REGEX = Regex("""^(https?://[^\s<>"]+|blob:[^\s<>"]+)""", RegexOption.IGNORE_CASE)

                                private fun handleDetectedMedia(url: String, title: String, mimeType: String) {
                                    val cleanUrl = url.trim()
                                    // Security check: strictly validate URL scheme using regex (only http, https, blob permitted)
                                    if (!ALLOWED_JS_URL_REGEX.matches(cleanUrl)) return
                                    if (cleanUrl.isBlank() || cleanUrl.startsWith("data:", ignoreCase = true)) return
                                    if (MediaSniffer.isImage(cleanUrl, mimeType)) return
                                    if (!MediaSniffer.isValidMedia(cleanUrl, mimeType) && !cleanUrl.contains(".apk", true) && !cleanUrl.contains(".zip", true)) return

                                    post {
                                        val cookieManager = CookieManager.getInstance()
                                        val currentWvUrl = this@apply.url
                                        val cookie = try {
                                            cookieManager.getCookie(cleanUrl) ?: cookieManager.getCookie(currentWvUrl)
                                        } catch (e: Exception) {
                                            null
                                        }
                                        val referer = currentWvUrl ?: urlInputText
                                        val ua = this@apply.settings.userAgentString.ifBlank {
                                            if (isDesktopMode) DESKTOP_CHROME_USER_AGENT else ChromePreset.CHROME_ANDROID.userAgent
                                        }
                                        val origin = try {
                                            val refUri = java.net.URI(referer)
                                            val scheme = refUri.scheme ?: "https"
                                            val host = refUri.host
                                            if (!host.isNullOrBlank()) {
                                                val portStr = if (refUri.port != -1 && refUri.port != 80 && refUri.port != 443) ":${refUri.port}" else ""
                                                "$scheme://$host$portStr"
                                            } else null
                                        } catch (_: Exception) {
                                            null
                                        }

                                        val extraHeaders = mutableMapOf<String, String>()
                                        origin?.let { extraHeaders["Origin"] = it }

                                        val headerConfig = ChromeHeaderConfig(
                                            preset = ChromePreset.CUSTOM,
                                            customUserAgent = ua,
                                            customReferer = referer,
                                            customCookies = cookie,
                                            extraHeaders = extraHeaders
                                        )

                                        val resolvedMime = when {
                                            mimeType.isNotBlank() -> mimeType
                                            url.contains(".m3u8", ignoreCase = true) -> "application/vnd.apple.mpegurl"
                                            url.contains(".mpd", ignoreCase = true) -> "application/dash+xml"
                                            url.contains(".apk", ignoreCase = true) -> "application/vnd.android.package-archive"
                                            url.contains(".zip", ignoreCase = true) -> "application/zip"
                                            url.contains(".webm", ignoreCase = true) -> "video/webm"
                                            url.contains(".mp3", ignoreCase = true) -> "audio/mpeg"
                                            url.contains(".m4a", ignoreCase = true) -> "audio/mp4"
                                            else -> "video/mp4"
                                        }

                                        val resolvedTitle = title.ifBlank {
                                            pageTitle.ifBlank {
                                                when {
                                                    url.contains(".apk", ignoreCase = true) -> "APK Paketi"
                                                    url.contains(".zip", ignoreCase = true) -> "ZIP Arşivi"
                                                    url.contains(".mpd", ignoreCase = true) -> "DASH Medya Akışı"
                                                    url.contains(".m3u8", ignoreCase = true) -> "HLS Medya Akışı"
                                                    else -> "Web Medyası"
                                                }
                                            }
                                        }

                                        viewModel.addCapturedMedia(
                                            url = url,
                                            title = resolvedTitle,
                                            mimeType = resolvedMime,
                                            headerConfig = headerConfig
                                        )
                                    }
                                }
                            },
                            "AndroidMediaSniffer"
                        )

                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val targetUrl = request?.url?.toString() ?: return false
                                return handleBrowserUrlNavigation(targetUrl)
                            }

                            @Deprecated("Deprecated in Java")
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                url: String?
                            ): Boolean {
                                val targetUrl = url ?: return false
                                return handleBrowserUrlNavigation(targetUrl)
                            }

                            private fun handleBrowserUrlNavigation(targetUrl: String): Boolean {
                                if (com.example.data.engine.TorrentDownloadEngine.isTorrentOrMagnet(targetUrl)) {
                                    post {
                                        viewModel.startTorrentDownload(targetUrl)
                                        viewModel.setCapturedUrlBanner("Magnet/Torrent bağlantısı yakalandı ve Torrent motoru başlatıldı 🧲")
                                        viewModel.selectTab(0)
                                    }
                                    return true
                                }
                                return false
                            }

                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                isLoading = true
                                url?.let {
                                    urlInputText = it
                                    canGoBack = view?.canGoBack() == true
                                    canGoForward = view?.canGoForward() == true
                                }
                                // Inject media sniffer early so initial play and load events are captured
                                view?.evaluateJavascript(INJECTED_MEDIA_SNIFFER_JS, null)
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                isLoading = false
                                pageTitle = view?.title.orEmpty()
                                canGoBack = view?.canGoBack() == true
                                canGoForward = view?.canGoForward() == true

                                // Inject advanced video auto-detector, iframe inspector and play button clicker
                                view?.evaluateJavascript(INJECTED_MEDIA_SNIFFER_JS, null)
                            }

                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                if (request == null) return null
                                val reqUrl = request.url?.toString() ?: return null

                                // 0. Ad-Blocker: Filter known advertising servers and trackers (Goal 1)
                                if (AdBlocker.isAd(reqUrl)) {
                                    return AdBlocker.createEmptyResponse()
                                }

                                val isForMainFrame = request.isForMainFrame
                                val headers = request.requestHeaders ?: emptyMap()
                                val lower = reqUrl.lowercase()

                                // Exclude all image requests (Requirement 1)
                                if (MediaSniffer.isImage(reqUrl)) {
                                    return super.shouldInterceptRequest(view, request)
                                }

                                // 1. HLS (.m3u8) & DASH (.mpd) Manifest Interceptor with complete headers (Requirement 3)
                                val manifestMedia = MediaSniffer.interceptManifestRequest(
                                    request = request,
                                    currentWebViewUrl = view?.url ?: urlInputText,
                                    userAgentString = view?.settings?.userAgentString,
                                    pageTitle = pageTitle
                                )
                                if (manifestMedia != null) {
                                    post {
                                        viewModel.addCapturedMedia(
                                            url = manifestMedia.url,
                                            title = manifestMedia.title,
                                            mimeType = manifestMedia.mimeType,
                                            headerConfig = manifestMedia.headerConfig
                                        )
                                    }
                                    return super.shouldInterceptRequest(view, request)
                                }

                                // Exclude static assets
                                val isStaticAsset = (lower.endsWith(".css") || lower.endsWith(".js") || lower.endsWith(".woff") ||
                                    lower.endsWith(".woff2") || lower.endsWith(".ttf") || lower.endsWith(".ico") ||
                                    lower.contains("/analytics") || lower.contains("/pixel") || lower.contains("/telemetry") ||
                                    lower.contains("google-analytics.com") || lower.contains("googletagmanager.com")) &&
                                    !lower.contains("videoplayback") && !lower.contains(".mp4") &&
                                    !lower.contains(".apk") && !lower.contains(".zip")

                                if (isStaticAsset) {
                                    return super.shouldInterceptRequest(view, request)
                                }

                                val secDest = headers["Sec-Fetch-Dest"]?.lowercase() ?: ""
                                val accept = headers["Accept"]?.lowercase() ?: ""
                                val isMediaHeader = secDest == "video" || secDest == "audio" ||
                                    accept.contains("video/") || accept.contains("audio/") ||
                                    accept.contains("mpegurl") || accept.contains("dash+xml")

                                val isApkOrZip = lower.endsWith(".apk") || lower.contains(".apk?") ||
                                    lower.endsWith(".xapk") || lower.contains(".xapk?") ||
                                    lower.endsWith(".apks") || lower.contains(".apks?") ||
                                    lower.endsWith(".zip") || lower.contains(".zip?") ||
                                    lower.endsWith(".rar") || lower.contains(".rar?") ||
                                    lower.endsWith(".7z") || lower.contains(".7z?") ||
                                    lower.endsWith(".tar") || lower.contains(".tar?") ||
                                    lower.endsWith(".gz") || lower.contains(".gz?") ||
                                    accept.contains("vnd.android.package-archive") ||
                                    accept.contains("application/zip") ||
                                    accept.contains("application/x-zip")

                                // Direct video/media stream detection (applies to both main frame and iframe requests)
                                val isDirectVideoStream = (lower.contains(".mp4") || lower.contains(".webm") ||
                                    lower.contains(".ts") || lower.contains(".m4s") || lower.contains(".m4v") ||
                                    lower.contains(".mov") || lower.contains(".flv") || lower.contains(".f4v") ||
                                    lower.contains(".mkv") || lower.contains(".mp3") || lower.contains(".m4a") ||
                                    lower.contains(".aac") || lower.contains(".wav") || lower.contains(".ogg") ||
                                    lower.contains(".flac") || lower.contains(".opus") ||
                                    lower.contains("videoplayback") || (lower.contains("cdninstagram.com") && lower.contains("t50.")) ||
                                    lower.contains("mime=video") || lower.contains("type=video") || isMediaHeader)

                                // Subframe / iframe embed request detection
                                val isIframeRequest = !isForMainFrame || secDest == "iframe" || secDest == "frame"
                                val isIframeEmbed = isIframeRequest && (
                                    lower.contains("youtube.com/embed") || lower.contains("youtu.be/") ||
                                    lower.contains("player.vimeo.com") || lower.contains("dailymotion.com/embed") ||
                                    lower.contains("/embed/") || lower.contains("/player/") || lower.contains("/iframe/") ||
                                    lower.contains("/video/") || lower.contains("/e/") || lower.contains("/v/") ||
                                    lower.contains("embed.php") || lower.contains("player.php") ||
                                    lower.contains("streamtape.com") || lower.contains("dood.") || lower.contains("vidmoly.") ||
                                    lower.contains("streamwish.") || lower.contains("filemoon.") || lower.contains("mixdrop.") ||
                                    lower.contains("mp4upload.")
                                )

                                // Extract comprehensive Chrome headers (Cookie, Referer, User-Agent, Origin)
                                val cookieManager = CookieManager.getInstance()
                                val cookieFromManager = try {
                                    cookieManager.getCookie(reqUrl) ?: view?.url?.let { cookieManager.getCookie(it) }
                                } catch (e: Exception) {
                                    null
                                }
                                val cookieFromHeaders = headers["Cookie"] ?: headers["cookie"]
                                val effectiveCookie = listOfNotNull(cookieFromManager, cookieFromHeaders).distinct().joinToString("; ").ifBlank { null }
                                val effectiveReferer = headers["Referer"] ?: headers["referer"] ?: view?.url ?: urlInputText
                                val effectiveUserAgent = headers["User-Agent"] ?: headers["user-agent"] ?: view?.settings?.userAgentString ?: if (isDesktopMode) DESKTOP_CHROME_USER_AGENT else ChromePreset.CHROME_ANDROID.userAgent

                                val originFromHeaders = headers["Origin"] ?: headers["origin"]
                                val effectiveOrigin = originFromHeaders ?: try {
                                    val refUri = java.net.URI(effectiveReferer)
                                    val scheme = refUri.scheme ?: "https"
                                    val host = refUri.host
                                    if (!host.isNullOrBlank()) {
                                        val portStr = if (refUri.port != -1 && refUri.port != 80 && refUri.port != 443) ":${refUri.port}" else ""
                                        "$scheme://$host$portStr"
                                    } else null
                                } catch (_: Exception) {
                                    null
                                }

                                val extraHeaders = mutableMapOf<String, String>()
                                effectiveOrigin?.let { extraHeaders["Origin"] = it }
                                headers["Sec-Fetch-Dest"]?.let { extraHeaders["Sec-Fetch-Dest"] = it }
                                headers["Sec-Fetch-Mode"]?.let { extraHeaders["Sec-Fetch-Mode"] = it }
                                headers["Sec-Fetch-Site"]?.let { extraHeaders["Sec-Fetch-Site"] = it }

                                val capturedHeaderConfig = ChromeHeaderConfig(
                                    preset = ChromePreset.CUSTOM,
                                    customUserAgent = effectiveUserAgent,
                                    customReferer = effectiveReferer,
                                    customCookies = effectiveCookie,
                                    extraHeaders = extraHeaders
                                )

                                if (isDirectVideoStream || isApkOrZip || isIframeEmbed) {
                                    val isYouTube = lower.contains("videoplayback") || lower.contains("googlevideo.com")
                                    val cleanStreamUrl = if (isYouTube && reqUrl.contains("&range=")) {
                                        reqUrl.replace(Regex("""&range=\d+-\d+"""), "")
                                    } else {
                                        reqUrl
                                    }

                                    if (MediaSniffer.isImage(cleanStreamUrl)) {
                                        return super.shouldInterceptRequest(view, request)
                                    }

                                    val mime = when {
                                        isApkOrZip && (lower.contains(".apk") || accept.contains("package")) -> "application/vnd.android.package-archive"
                                        isApkOrZip -> "application/zip"
                                        lower.contains(".webm") -> "video/webm"
                                        lower.contains(".mp3") -> "audio/mpeg"
                                        lower.contains(".m4a") || lower.contains("itag=140") || secDest == "audio" -> "audio/mp4"
                                        isIframeEmbed -> "video/mp4"
                                        else -> "video/mp4"
                                    }

                                    val streamTitle = when {
                                        isYouTube -> {
                                            val quality = when {
                                                lower.contains("itag=18") -> "360p"
                                                lower.contains("itag=22") -> "720p HD"
                                                lower.contains("itag=137") -> "1080p HD"
                                                lower.contains("itag=140") -> "Ses (M4A)"
                                                else -> "MP4"
                                            }
                                            "YouTube Video ($quality) - ${pageTitle.ifBlank { "Akış" }}"
                                        }
                                        lower.contains(".apk") -> {
                                            val name = reqUrl.substringAfterLast('/').substringBefore('?').ifBlank { "Uygulama.apk" }
                                            "APK Paketi ($name) - ${pageTitle.ifBlank { "İndirme" }}"
                                        }
                                        lower.contains(".zip") || lower.contains(".rar") || lower.contains(".7z") -> {
                                            val name = reqUrl.substringAfterLast('/').substringBefore('?').ifBlank { "Dosya.zip" }
                                            "Arşiv Dosyası ($name) - ${pageTitle.ifBlank { "İndirme" }}"
                                        }
                                        isIframeEmbed -> {
                                            "Gömülü İframe Video - ${pageTitle.ifBlank { "Player" }}"
                                        }
                                        lower.contains("instagram.com") || lower.contains("cdninstagram.com") -> {
                                            "Instagram Video - ${pageTitle.ifBlank { "Reel" }}"
                                        }
                                        else -> pageTitle.ifBlank { "Web Medya Akışı" }
                                    }

                                    post {
                                        viewModel.addCapturedMedia(
                                            url = cleanStreamUrl,
                                            title = streamTitle,
                                            mimeType = mime,
                                            headerConfig = capturedHeaderConfig
                                        )
                                    }

                                    // Deep sniff direct stream URLs inside iframe embed asynchronously
                                    if (isIframeEmbed && !isDirectVideoStream) {
                                        viewModel.sniffSubframeMedia(reqUrl, pageTitle, capturedHeaderConfig)
                                    }
                                } else if (request.method.equals("GET", ignoreCase = true) && !isForMainFrame &&
                                    (lower.contains("stream") || lower.contains("video") || lower.contains("audio") ||
                                     lower.contains("media") || lower.contains("playlist") || lower.contains("manifest") ||
                                     lower.contains("download") || lower.contains("getfile"))) {
                                    // Probe dynamic streaming/download endpoints without extension
                                    CoroutineScope(Dispatchers.IO).launch {
                                        try {
                                            val probeBuilder = Request.Builder().url(reqUrl).head()
                                            capturedHeaderConfig.applyTo(probeBuilder, reqUrl)
                                            val resp = OkHttpProvider.client.newCall(probeBuilder.build()).execute()
                                            val cType = resp.header("Content-Type").orEmpty().lowercase()
                                            resp.close()
                                            if (!MediaSniffer.isImage(reqUrl, cType) && (
                                                cType.contains("video/") || cType.contains("audio/") ||
                                                cType.contains("mpegurl") || cType.contains("dash+xml") ||
                                                cType.contains("vnd.android.package-archive") || cType.contains("zip"))) {
                                                post {
                                                    viewModel.addCapturedMedia(
                                                        url = reqUrl,
                                                        title = pageTitle.ifBlank { "Dinamik Akış" },
                                                        mimeType = cType,
                                                        headerConfig = capturedHeaderConfig
                                                    )
                                                }
                                            }
                                        } catch (ignored: Exception) {}
                                    }
                                }

                                return super.shouldInterceptRequest(view, request)
                            }

                            @Deprecated("Deprecated in Java")
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                url: String?
                            ): WebResourceResponse? {
                                val reqUrl = url ?: return null

                                // 0. Ad-Blocker: Filter known advertising servers and trackers (Goal 1)
                                if (AdBlocker.isAd(reqUrl)) {
                                    return AdBlocker.createEmptyResponse()
                                }

                                if (MediaSniffer.isImage(reqUrl)) return super.shouldInterceptRequest(view, url)

                                val manifestMedia = MediaSniffer.interceptManifestUrl(
                                    reqUrl = reqUrl,
                                    currentWebViewUrl = view?.url ?: urlInputText,
                                    userAgentString = view?.settings?.userAgentString,
                                    pageTitle = pageTitle
                                )
                                if (manifestMedia != null) {
                                    post {
                                        viewModel.addCapturedMedia(
                                            url = manifestMedia.url,
                                            title = manifestMedia.title,
                                            mimeType = manifestMedia.mimeType,
                                            headerConfig = manifestMedia.headerConfig
                                        )
                                    }
                                    return super.shouldInterceptRequest(view, url)
                                }

                                val lower = reqUrl.lowercase()
                                if (lower.contains(".mp4") || lower.contains(".apk") || lower.contains(".zip") ||
                                    lower.contains("videoplayback") || lower.contains(".webm")) {
                                    val cookieManager = CookieManager.getInstance()
                                    val cookie = try { cookieManager.getCookie(reqUrl) ?: view?.url?.let { cookieManager.getCookie(it) } } catch (e: Exception) { null }
                                    val referer = view?.url ?: urlInputText
                                    val ua = view?.settings?.userAgentString ?: if (isDesktopMode) DESKTOP_CHROME_USER_AGENT else ChromePreset.CHROME_ANDROID.userAgent
                                    val headerConfig = ChromeHeaderConfig(
                                        preset = ChromePreset.CUSTOM,
                                        customUserAgent = ua,
                                        customReferer = referer,
                                        customCookies = cookie
                                    )
                                    val mime = when {
                                        lower.contains(".apk") -> "application/vnd.android.package-archive"
                                        lower.contains(".zip") -> "application/zip"
                                        lower.contains(".webm") -> "video/webm"
                                        else -> "video/mp4"
                                    }
                                    post {
                                        viewModel.addCapturedMedia(
                                            url = reqUrl,
                                            title = pageTitle.ifBlank { "Web Medya Akışı" },
                                            mimeType = mime,
                                            headerConfig = headerConfig
                                        )
                                    }
                                }
                                return super.shouldInterceptRequest(view, url)
                            }
                        }

                        val start = normalizeBrowserUrl(browserUrl)
                        loadUrl(start)
                        webViewInstance = this
                    }
                },
                update = { wv ->
                    webViewInstance = wv
                    wv.settings.mediaPlaybackRequiresUserGesture = false
                },
                modifier = Modifier.fillMaxSize()
            )

            // 3. Floating "Yakalanan Medyalar" (Captured Media Pill)
            androidx.compose.animation.AnimatedVisibility(
                visible = capturedMediaList.isNotEmpty(),
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
            ) {
                Surface(
                    onClick = { showCapturedListSheet = true },
                    shape = RoundedCornerShape(28.dp),
                    color = Color(0xFF0F1E36),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, CyberCyan),
                    shadowElevation = 8.dp,
                    modifier = Modifier.testTag("floating_captured_media_pill")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(CyberCyan),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = null,
                                tint = Color(0xFF070B14),
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Video Yakalandı!",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(AccentGreen)
                                        .padding(horizontal = 6.dp, vertical = 1.dp)
                                ) {
                                    Text(
                                        text = "${capturedMediaList.size}",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color.Black
                                    )
                                }
                            }
                            Text(
                                text = "Çözünürlük Seç (1080p, 720p...) 🎬",
                                fontSize = 11.sp,
                                color = CyberCyan
                            )
                        }
                    }
                }
            }

            // Non-blocking analyzing resolutions bottom pill
            androidx.compose.animation.AnimatedVisibility(
                visible = isAnalyzingResolutions,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 76.dp, start = 20.dp, end = 20.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = DarkSurface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, CyberCyan.copy(alpha = 0.5f)),
                    shadowElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            color = CyberCyan,
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.5.dp
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text(
                                text = "Çözünürlükler Analiz Ediliyor...",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = "1080p FHD, 720p HD akışları taranıyor",
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }
        }
    }

    // Modal Sheet showing all intercepted streams
    if (showCapturedListSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

        ModalBottomSheet(
            onDismissRequest = { showCapturedListSheet = false },
            sheetState = sheetState,
            containerColor = DarkSurface,
            scrimColor = Color.Black.copy(alpha = 0.7f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "⚡ Yakalanan Medya Akışları",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "${capturedMediaList.size} medya tespit edildi",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
                    }

                    TextButton(onClick = { viewModel.clearCapturedMedia() }) {
                        Text("Temizle", color = Color(0xFFFF5252), fontSize = 13.sp)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Category Filter Tabs (Tümü, Video, Ses, Resim)
                var snifferTab by remember { mutableStateOf("Tümü") }
                val videoCount = capturedMediaList.count { it.mediaType == com.example.data.model.MediaType.VIDEO || it.mimeType.startsWith("video/") }
                val audioCount = capturedMediaList.count { it.mediaType == com.example.data.model.MediaType.AUDIO || it.mimeType.startsWith("audio/") }
                val imageCount = capturedMediaList.count { it.mediaType == com.example.data.model.MediaType.IMAGE || it.mimeType.startsWith("image/") }

                val filteredCapturedList = remember(capturedMediaList, snifferTab) {
                    when (snifferTab) {
                        "Video" -> capturedMediaList.filter { it.mediaType == com.example.data.model.MediaType.VIDEO || it.mimeType.startsWith("video/") }
                        "Ses" -> capturedMediaList.filter { it.mediaType == com.example.data.model.MediaType.AUDIO || it.mimeType.startsWith("audio/") }
                        "Resim" -> capturedMediaList.filter { it.mediaType == com.example.data.model.MediaType.IMAGE || it.mimeType.startsWith("image/") }
                        else -> capturedMediaList
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = 12.dp)
                ) {
                    listOf(
                        "Tümü" to capturedMediaList.size,
                        "Video" to videoCount,
                        "Ses" to audioCount,
                        "Resim" to imageCount
                    ).forEach { (label, count) ->
                        val isSelected = snifferTab == label
                        Surface(
                            onClick = { snifferTab = label },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) CyberCyan else DarkSurfaceVariant,
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isSelected) CyberCyan else DarkCardBorder.copy(alpha = 0.5f)
                            ),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp)
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) Color(0xFF070B14) else TextPrimary
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(if (isSelected) Color(0xFF070B14).copy(alpha = 0.2f) else DarkCardBorder)
                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                ) {
                                    Text(
                                        text = "$count",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) Color(0xFF070B14) else TextSecondary
                                    )
                                }
                            }
                        }
                    }
                }

                // Quality Analyzer banner for all captured streams
                Surface(
                    onClick = {
                        val firstMedia = filteredCapturedList.firstOrNull() ?: capturedMediaList.firstOrNull()
                        if (firstMedia != null) {
                            showCapturedListSheet = false
                            viewModel.analyzeAndSelectResolution(firstMedia, urlInputText)
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    color = CyberCyan.copy(alpha = 0.12f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, CyberCyan.copy(alpha = 0.4f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .testTag("analyze_resolutions_banner")
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(CyberCyan.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.HighQuality,
                                contentDescription = null,
                                tint = CyberCyan,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "🎬 Video Çözünürlüklerini Analiz Et",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = CyberCyan
                            )
                            Text(
                                text = "1080p FHD, 720p HD, 480p SD seçeneklerini keşfet",
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = null,
                            tint = CyberCyan,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                filteredCapturedList.forEachIndexed { index, media ->
                    Surface(
                        onClick = {
                            showCapturedListSheet = false
                            viewModel.analyzeAndSelectResolution(media, urlInputText)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .testTag("captured_media_card_$index"),
                        shape = RoundedCornerShape(14.dp),
                        color = DarkCard,
                        border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(CyberCyan.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayCircle,
                                    contentDescription = null,
                                    tint = CyberCyan,
                                    modifier = Modifier.size(26.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(10.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = media.title,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = media.url,
                                    fontSize = 10.sp,
                                    color = TextMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Row(
                                    modifier = Modifier.padding(top = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = media.mimeType,
                                        fontSize = 10.sp,
                                        color = ElectricBlue,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "• Dokun: Çözünürlük Seç",
                                        fontSize = 10.sp,
                                        color = AccentGreen
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(6.dp))

                            // Action 1: "Çözünürlük Seç" (1080p, 720p, 480p)
                            Button(
                                onClick = {
                                    showCapturedListSheet = false
                                    viewModel.analyzeAndSelectResolution(media, urlInputText)
                                },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = CyberCyan,
                                    contentColor = Color(0xFF070B14)
                                ),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                modifier = Modifier.testTag("select_resolution_btn_$index")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.HighQuality,
                                    contentDescription = "Kalite Seç",
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Çözünürlük", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }

                            Spacer(modifier = Modifier.width(4.dp))

                            // Action 2: Direct Quick Download
                            IconButton(
                                onClick = {
                                    showCapturedListSheet = false
                                    viewModel.startDirectDownload(
                                        url = media.url,
                                        headerConfig = media.headerConfig,
                                        customFileName = media.title
                                    )
                                },
                                modifier = Modifier
                                    .size(36.dp)
                                    .testTag("direct_download_btn_$index")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = "Doğrudan İndir",
                                    tint = AccentGreen,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // Video Quality Selector Dialog (1080p, 720p, 480p, 360p, Ses)
    if (showQualitySelector && qualityOptions.isNotEmpty()) {
        VideoQualitySelectorDialog(
            options = qualityOptions,
            selectedThreads = selectedThreads,
            onSelectAndDownload = viewModel::selectAndDownloadQuality,
            onDismiss = viewModel::dismissQualitySelector
        )
    }
}

private fun normalizeBrowserUrl(input: String): String {
    val trimmed = input.trim()
    if (trimmed.isBlank()) return "https://www.google.com"
    if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
        return trimmed
    }
    // Check if it looks like a domain name
    if (trimmed.contains('.') && !trimmed.contains(' ')) {
        return "https://$trimmed"
    }
    // Search query fallback
    return "https://www.google.com/search?q=" + java.net.URLEncoder.encode(trimmed, "UTF-8")
}

private const val DESKTOP_CHROME_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

private val INJECTED_MEDIA_SNIFFER_JS = """
(function() {
    function getSniffer(win) {
        try {
            if (win && win.AndroidMediaSniffer) return win.AndroidMediaSniffer;
            if (window.AndroidMediaSniffer) return window.AndroidMediaSniffer;
            if (win && win.parent && win.parent.AndroidMediaSniffer) return win.parent.AndroidMediaSniffer;
            if (window.top && window.top.AndroidMediaSniffer) return window.top.AndroidMediaSniffer;
        } catch(e) {}
        return null;
    }

    function isImageCandidate(url) {
        if (!url || typeof url !== 'string') return true;
        var clean = url.split('?')[0].split('#')[0].toLowerCase();
        return clean.endsWith('.jpg') || clean.endsWith('.jpeg') || clean.endsWith('.png') ||
               clean.endsWith('.webp') || clean.endsWith('.gif') || clean.endsWith('.ico') ||
               clean.endsWith('.svg');
    }

    function notifyMediaCandidate(src, title, mime, win) {
        if (!src || typeof src !== 'string') return;
        src = src.trim();
        if (src.startsWith('//')) {
            src = window.location.protocol + src;
        } else if (src.startsWith('/')) {
            src = window.location.origin + src;
        }
        if (!src.startsWith('http://') && !src.startsWith('https://')) return;
        if (src.startsWith('blob:') || src.startsWith('data:')) return;
        if (isImageCandidate(src)) return;
        if (src.includes('analytics') || src.includes('pixel') || src.includes('favicon')) return;

        var sniffer = getSniffer(win || window);
        if (sniffer) {
            try {
                var cleanTitle = title || document.title || 'Video';
                var cleanMime = mime || 'video/mp4';
                if (sniffer.onVideoPlayed) {
                    sniffer.onVideoPlayed(src, cleanTitle, cleanMime);
                } else if (sniffer.onMediaDetectedWithDetails) {
                    sniffer.onMediaDetectedWithDetails(src, cleanTitle, cleanMime);
                } else if (sniffer.onMediaDetected) {
                    sniffer.onMediaDetected(src, cleanTitle);
                }
            } catch(e) {}
        }
    }

    // Resolves real video/audio URL from DOM element (handling non-blob, sources, data attributes)
    function extractRealMediaUrl(mediaEl) {
        if (!mediaEl) return null;

        var candidates = [
            mediaEl.currentSrc,
            mediaEl.src,
            mediaEl.getAttribute('src'),
            mediaEl.getAttribute('data-src'),
            mediaEl.getAttribute('data-url'),
            mediaEl.getAttribute('data-video'),
            mediaEl.getAttribute('data-video-src'),
            mediaEl.getAttribute('data-original')
        ];

        var sources = mediaEl.querySelectorAll ? mediaEl.querySelectorAll('source') : [];
        for (var i = 0; i < sources.length; i++) {
            var s = sources[i];
            candidates.push(s.src);
            candidates.push(s.getAttribute('src'));
            candidates.push(s.getAttribute('data-src'));
            candidates.push(s.getAttribute('data-url'));
        }

        for (var j = 0; j < candidates.length; j++) {
            var url = candidates[j];
            if (url && typeof url === 'string') {
                url = url.trim();
                if (url.startsWith('//')) {
                    url = window.location.protocol + url;
                } else if (url.startsWith('/')) {
                    url = window.location.origin + url;
                }
                if (url.startsWith('http://') || url.startsWith('https://')) {
                    if (!url.startsWith('blob:') && !url.startsWith('data:') && !isImageCandidate(url)) {
                        return url;
                    }
                }
            }
        }
        return null;
    }

    function handleMediaElement(mediaEl, sourceEvent) {
        if (!mediaEl) return;
        var realUrl = extractRealMediaUrl(mediaEl);
        if (realUrl) {
            var title = mediaEl.title || mediaEl.getAttribute('aria-label') || document.title || 'Video';
            var mime = (mediaEl.tagName === 'AUDIO') ? 'audio/mp4' : 'video/mp4';
            notifyMediaCandidate(realUrl, title, mime, window);
        }
    }

    function attachMediaElementListeners(mediaEl) {
        if (!mediaEl || mediaEl.__turboListenersAttached) return;
        mediaEl.__turboListenersAttached = true;

        // 1. Listen to 'play' event
        mediaEl.addEventListener('play', function() {
            handleMediaElement(mediaEl, 'play');
        }, true);

        // 2. Listen to 'loadeddata' event
        mediaEl.addEventListener('loadeddata', function() {
            handleMediaElement(mediaEl, 'loadeddata');
        }, true);

        // 3. Listen to playing & canplay
        mediaEl.addEventListener('playing', function() {
            handleMediaElement(mediaEl, 'playing');
        }, true);

        mediaEl.addEventListener('canplay', function() {
            handleMediaElement(mediaEl, 'canplay');
        }, true);
    }

    // Global document listeners for 'play' and 'loadeddata' events (captures bubbling & capturing phases)
    try {
        if (!window.__turboGlobalPlayAttached) {
            window.__turboGlobalPlayAttached = true;
            document.addEventListener('play', function(e) {
                if (e.target && (e.target.tagName === 'VIDEO' || e.target.tagName === 'AUDIO')) {
                    attachMediaElementListeners(e.target);
                    handleMediaElement(e.target, 'global_play');
                }
            }, true);

            document.addEventListener('loadeddata', function(e) {
                if (e.target && (e.target.tagName === 'VIDEO' || e.target.tagName === 'AUDIO')) {
                    attachMediaElementListeners(e.target);
                    handleMediaElement(e.target, 'global_loadeddata');
                }
            }, true);
        }
    } catch(e) {}

    // Hook HTMLMediaElement.prototype.src descriptor to catch dynamic 'src' changes
    try {
        if (!window.__turboSrcHookAttached) {
            window.__turboSrcHookAttached = true;
            var srcDesc = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'src');
            if (srcDesc && srcDesc.set) {
                var origSet = srcDesc.set;
                Object.defineProperty(HTMLMediaElement.prototype, 'src', {
                    set: function(val) {
                        try {
                            if (val && typeof val === 'string' && !val.startsWith('blob:') && !val.startsWith('data:') && !isImageCandidate(val)) {
                                var mime = (this.tagName === 'AUDIO') ? 'audio/mp4' : 'video/mp4';
                                notifyMediaCandidate(val, this.title || document.title, mime, window);
                            }
                        } catch(e) {}
                        return origSet.call(this, val);
                    },
                    get: srcDesc.get,
                    configurable: true,
                    enumerable: true
                });
            }
        }
    } catch(e) {}

    // MutationObserver to listen for 'src', 'data-src' and DOM insertions of video/audio/source
    try {
        if (!window.__turboMutationObserverAttached) {
            window.__turboMutationObserverAttached = true;
            var observer = new MutationObserver(function(mutations) {
                mutations.forEach(function(mut) {
                    if (mut.type === 'attributes') {
                        var target = mut.target;
                        if (target && (target.tagName === 'VIDEO' || target.tagName === 'AUDIO')) {
                            attachMediaElementListeners(target);
                            handleMediaElement(target, 'attr_' + mut.attributeName);
                        } else if (target && target.tagName === 'SOURCE' && target.parentElement) {
                            var parentMedia = target.parentElement;
                            if (parentMedia.tagName === 'VIDEO' || parentMedia.tagName === 'AUDIO') {
                                attachMediaElementListeners(parentMedia);
                                handleMediaElement(parentMedia, 'source_attr_' + mut.attributeName);
                            }
                        }
                    } else if (mut.type === 'childList') {
                        mut.addedNodes.forEach(function(node) {
                            if (node.nodeType === 1) {
                                if (node.tagName === 'VIDEO' || node.tagName === 'AUDIO') {
                                    attachMediaElementListeners(node);
                                    handleMediaElement(node, 'node_added');
                                } else if (node.querySelectorAll) {
                                    var mediaList = node.querySelectorAll('video, audio');
                                    mediaList.forEach(function(m) {
                                        attachMediaElementListeners(m);
                                        handleMediaElement(m, 'subtree_added');
                                    });
                                }
                            }
                        });
                    }
                });
            });
            observer.observe(document.documentElement || document.body, {
                childList: true,
                subtree: true,
                attributes: true,
                attributeFilter: ['src', 'data-src', 'data-url', 'data-video', 'data-original']
            });
        }
    } catch(e) {}

    // Hook fetch & XMLHttpRequest to intercept direct media and HLS/DASH manifest streams
    try {
        if (window.fetch && !window.__fetchSnifferAttached) {
            window.__fetchSnifferAttached = true;
            var originalFetch = window.fetch;
            window.fetch = function() {
                var args = arguments;
                var fetchUrl = '';
                if (typeof args[0] === 'string') {
                    fetchUrl = args[0];
                } else if (args[0] && args[0].url) {
                    fetchUrl = args[0].url;
                }
                return originalFetch.apply(this, args).then(function(response) {
                    try {
                        if (response && response.url) {
                            var rUrl = response.url;
                            var cType = (response.headers && response.headers.get('content-type')) || '';
                            var lowerUrl = rUrl.toLowerCase();
                            var lowerType = cType.toLowerCase();
                            if (!lowerType.includes('image/') && !isImageCandidate(rUrl)) {
                                if (lowerType.includes('video/') || lowerType.includes('audio/') ||
                                    lowerType.includes('mpegurl') || lowerType.includes('dash+xml') ||
                                    lowerUrl.includes('.m3u8') || lowerUrl.includes('.mpd') || lowerUrl.includes('.mp4') ||
                                    lowerUrl.includes('.webm') || lowerUrl.includes('.mkv')) {
                                    notifyMediaCandidate(rUrl, document.title || 'Medya Akışı', cType, window);
                                }
                            }
                        }
                    } catch(e) {}
                    return response;
                });
            };
        }
    } catch(e) {}

    try {
        if (window.XMLHttpRequest && !window.__xhrSnifferAttached) {
            window.__xhrSnifferAttached = true;
            var originalOpen = XMLHttpRequest.prototype.open;
            var originalSend = XMLHttpRequest.prototype.send;
            XMLHttpRequest.prototype.open = function(method, url) {
                this.__sniffUrl = url;
                return originalOpen.apply(this, arguments);
            };
            XMLHttpRequest.prototype.send = function() {
                var xhr = this;
                xhr.addEventListener('load', function() {
                    try {
                        var sUrl = xhr.responseURL || xhr.__sniffUrl;
                        if (sUrl) {
                            var cType = xhr.getResponseHeader('Content-Type') || '';
                            var lowerUrl = sUrl.toLowerCase();
                            var lowerType = cType.toLowerCase();
                            if (!lowerType.includes('image/') && !isImageCandidate(sUrl)) {
                                if (lowerType.includes('video/') || lowerType.includes('audio/') ||
                                    lowerType.includes('mpegurl') || lowerType.includes('dash+xml') ||
                                    lowerUrl.includes('.m3u8') || lowerUrl.includes('.mpd') || lowerUrl.includes('.mp4') ||
                                    lowerUrl.includes('.webm') || lowerUrl.includes('.mkv')) {
                                    notifyMediaCandidate(sUrl, document.title || 'Medya Akışı', cType, window);
                                }
                            }
                        }
                    } catch(e) {}
                });
                return originalSend.apply(this, arguments);
            };
        }
    } catch(e) {}

    var PLAY_SELECTORS = [
        '.vjs-big-play-button',
        '.vjs-play-control',
        '.jw-icon-playback',
        '.jw-display-icon-container',
        '.jw-preview',
        '.plyr__control--overlaid',
        'button[data-plyr="play"]',
        '.ytp-large-play-button',
        '.ytp-play-button',
        '.fp-play',
        '.fp-ui',
        '.play-button',
        '.play-btn',
        '.playBtn',
        '.btn-play',
        '.play_button',
        '.play-icon',
        '.player-play-button',
        '.clappr-play-button',
        '.media-play-button',
        'button[aria-label*="Play" i]',
        'button[aria-label*="Oynat" i]',
        'button[aria-label*="play" i]',
        'button[title*="Play" i]',
        'button[title*="Oynat" i]',
        'a[aria-label*="Play" i]',
        'a[aria-label*="Oynat" i]',
        '[data-action="play"]',
        '[data-testid*="play" i]',
        '[data-control="play"]',
        'button[class*="play" i]',
        'div[class*="play-btn" i]',
        'div[class*="play_btn" i]',
        'div[class*="playButton" i]',
        'div[class*="play-button" i]',
        'span[class*="play-icon" i]',
        'span[class*="play" i]',
        '.video-overlay',
        '.player-overlay',
        '.video-play-layer',
        '.click-to-play'
    ];

    function triggerClickOnElement(el, win) {
        if (!el) return;
        try {
            if (el.offsetParent !== null || el.getClientRects().length > 0) {
                el.click();
                var mouseOpts = { bubbles: true, cancelable: true, view: win || window };
                el.dispatchEvent(new MouseEvent('mousedown', mouseOpts));
                el.dispatchEvent(new MouseEvent('mouseup', mouseOpts));
                el.dispatchEvent(new MouseEvent('click', mouseOpts));
                try {
                    el.dispatchEvent(new PointerEvent('pointerdown', mouseOpts));
                    el.dispatchEvent(new PointerEvent('pointerup', mouseOpts));
                } catch(e) {}
            }
        } catch(e) {}
    }

    function processDocument(doc, win, parentTitle, depth) {
        if (!doc || depth > 4) return;

        // 1. Scan <video> and <audio> tags
        try {
            var mediaEls = doc.querySelectorAll('video, audio');
            mediaEls.forEach(function(m) {
                attachMediaElementListeners(m);
                handleMediaElement(m, 'doc_scan');

                // If paused, simulate clicks on play buttons or containers
                if (m.paused) {
                    triggerClickOnElement(m, win);
                    if (m.parentElement) triggerClickOnElement(m.parentElement, win);
                }
            });
        } catch(e) {}

        // 2. Click play buttons in this document
        try {
            PLAY_SELECTORS.forEach(function(selector) {
                var buttons = doc.querySelectorAll(selector);
                buttons.forEach(function(btn) {
                    triggerClickOnElement(btn, win);
                });
            });
        } catch(e) {}

        // 3. Scan iframes recursively inside this document
        try {
            var iframes = doc.querySelectorAll('iframe, frame');
            iframes.forEach(function(ifr) {
                processIframe(ifr, doc, win, depth + 1);
            });
        } catch(e) {}
    }

    function processIframe(ifr, parentDoc, parentWin, depth) {
        if (!ifr || depth > 4) return;

        try {
            ifr.setAttribute('allow', 'autoplay *; fullscreen *; picture-in-picture *');
            ifr.setAttribute('allowfullscreen', 'true');
        } catch(e) {}

        var iSrc = ifr.src || ifr.getAttribute('data-src');
        if (iSrc && (iSrc.includes('youtube.com') || iSrc.includes('youtu.be') || iSrc.includes('vimeo.com') || iSrc.includes('embed') || iSrc.includes('player'))) {
            notifyMediaCandidate(iSrc, 'Gömülü Video - ' + (ifr.title || (parentDoc && parentDoc.title) || 'İframe'), 'video/mp4', parentWin);
        }

        var ifrDoc = null;
        var ifrWin = null;
        try {
            ifrDoc = ifr.contentDocument || (ifr.contentWindow && ifr.contentWindow.document);
            ifrWin = ifr.contentWindow;
        } catch(e) {}

        if (ifrDoc) {
            processDocument(ifrDoc, ifrWin || parentWin, (ifr.title || 'İframe Video'), depth);
        } else {
            try {
                var cw = ifr.contentWindow;
                if (cw) {
                    cw.postMessage('{"event":"command","func":"playVideo","args":""}', '*');
                    cw.postMessage('{"method":"play"}', '*');
                    cw.postMessage('{"action":"play"}', '*');
                    cw.postMessage('{"type":"play"}', '*');
                    cw.postMessage('play', '*');
                }
            } catch(e) {}

            try {
                var rect = ifr.getBoundingClientRect();
                if (rect.width > 20 && rect.height > 20) {
                    var cx = rect.left + rect.width / 2;
                    var cy = rect.top + rect.height / 2;
                    var clickOpts = {
                        bubbles: true,
                        cancelable: true,
                        view: parentWin || window,
                        clientX: cx,
                        clientY: cy
                    };
                    ifr.dispatchEvent(new MouseEvent('mousedown', clickOpts));
                    ifr.dispatchEvent(new MouseEvent('mouseup', clickOpts));
                    ifr.dispatchEvent(new MouseEvent('click', clickOpts));
                }
            } catch(e) {}
        }

        try {
            if (!ifr.__loadListenerAttached) {
                ifr.__loadListenerAttached = true;
                ifr.addEventListener('load', function() {
                    setTimeout(function() {
                        processIframe(ifr, parentDoc, parentWin, depth);
                    }, 250);
                });
            }
        } catch(e) {}
    }

    function scanAll() {
        processDocument(document, window, document.title, 0);

        // Scan OpenGraph video tags
        try {
            var ogV = document.querySelector('meta[property="og:video"], meta[property="og:video:url"], meta[property="og:video:secure_url"]');
            if (ogV && ogV.content) {
                notifyMediaCandidate(ogV.content, document.title, 'video/mp4', window);
            }
        } catch(e) {}
    }

    window.__scanAllFrames = scanAll;
    window.triggerMediaSniffing = scanAll;

    scanAll();

    [400, 1200, 2500, 5000].forEach(function(delay) {
        setTimeout(scanAll, delay);
    });

    document.addEventListener('click', function() {
        setTimeout(scanAll, 300);
    }, true);
})();
""".trimIndent()

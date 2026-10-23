package com.indirici.app.ui.browser

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import com.indirici.app.ui.theme.AppIcons
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.indirici.app.domain.model.VideoDownloadTask

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MiniBrowserDialog(
    initialUrl: String = "https://",
    onDownloadDetectedTask: (VideoDownloadTask) -> Unit,
    onCloudflareSessionCaptured: ((targetUrl: String, userAgent: String, cookie: String, referer: String) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    var addressInput by remember { mutableStateOf(initialUrl) }
    var currentPageUrl by remember { mutableStateOf(initialUrl) }
    var currentPageTitle by remember { mutableStateOf<String?>(null) }
    var currentUserAgent by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var snifferClientRef by remember { mutableStateOf<VideoSnifferWebViewClient?>(null) }
    val detectedTasks = remember { mutableStateListOf<VideoDownloadTask>() }

    fun navigateToInput() {
        val raw = addressInput.trim()
        if (raw.isEmpty()) return
        val finalUrl = if (raw.startsWith("http://") || raw.startsWith("https://")) {
            raw
        } else {
            "https://$raw"
        }
        addressInput = finalUrl
        detectedTasks.clear()
        webViewRef?.loadUrl(finalUrl)
    }

    fun captureCurrentPageSessionAndClose() {
        val targetUrl = currentPageUrl.takeIf { it.startsWith("http") } ?: addressInput.trim()
        if (!targetUrl.startsWith("http")) {
            onDismiss()
            return
        }
        val cookie = runCatching { CookieManager.getInstance().getCookie(targetUrl) }.getOrNull().orEmpty()
        val ua = currentUserAgent.ifBlank { webViewRef?.settings?.userAgentString.orEmpty() }

        if (onCloudflareSessionCaptured != null) {
            onCloudflareSessionCaptured(targetUrl, ua, cookie, targetUrl)
        } else {
            onDownloadDetectedTask(
                VideoDownloadTask(
                    url = targetUrl,
                    referer = targetUrl,
                    userAgent = ua,
                    cookie = cookie,
                    pageTitle = currentPageTitle,
                    detectedLabel = "Doğrulanmış Sayfa Oturumu"
                )
            )
        }
        onDismiss()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Scaffold(
                topBar = {
                    Column {
                        TopAppBar(
                            title = {
                                OutlinedTextField(
                                    value = addressInput,
                                    onValueChange = { addressInput = it },
                                    singleLine = true,
                                    placeholder = { Text("Site adresini girin (https://...)") },
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                                    keyboardActions = KeyboardActions(onGo = { navigateToInput() }),
                                    modifier = Modifier.fillMaxWidth(),
                                    trailingIcon = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (addressInput.isNotEmpty() && addressInput != "https://") {
                                                IconButton(onClick = { addressInput = "" }) {
                                                    Icon(
                                                        imageVector = Icons.Default.Close,
                                                        contentDescription = "Temizle"
                                                    )
                                                }
                                            }
                                            IconButton(onClick = { navigateToInput() }) {
                                                Icon(
                                                    imageVector = Icons.Default.Search,
                                                    contentDescription = "Git"
                                                )
                                            }
                                        }
                                    }
                                )
                            },
                            navigationIcon = {
                                IconButton(
                                    onClick = {
                                        val wv = webViewRef
                                        if (wv != null && wv.canGoBack()) {
                                            wv.goBack()
                                        } else {
                                            onDismiss()
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Geri"
                                    )
                                }
                            },
                            actions = {
                                IconButton(onClick = { webViewRef?.reload() }) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "Yenile"
                                    )
                                }
                                IconButton(onClick = onDismiss) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Kapat"
                                    )
                                }
                            }
                        )
                        if (isLoading) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                },
                bottomBar = {
                    DetectedVideoBottomSheetPanel(
                        tasks = detectedTasks,
                        hasValidPageUrl = currentPageUrl.startsWith("http://") || currentPageUrl.startsWith("https://"),
                        onVerifyCloudflareClick = { captureCurrentPageSessionAndClose() },
                        onDownloadClick = { task ->
                            onDownloadDetectedTask(task)
                            onDismiss()
                        }
                    )
                }
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.mediaPlaybackRequiresUserGesture = false
                                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

                                currentUserAgent = settings.userAgentString

                                val cookieManager = CookieManager.getInstance()
                                cookieManager.setAcceptCookie(true)
                                cookieManager.setAcceptThirdPartyCookies(this, true)

                                val sharedClient = (ctx.applicationContext as? com.indirici.app.IndiriciApplication)?.sharedOkHttpClient
                                val snifferClient = VideoSnifferWebViewClient(
                                    onVideoDetected = { task ->
                                        if (detectedTasks.none { it.url == task.url }) {
                                            detectedTasks.add(0, task)
                                        }
                                    },
                                    onPageStateChanged = { url, title, loading ->
                                        if (url.isNotBlank()) {
                                            addressInput = url
                                            currentPageUrl = url
                                        }
                                        currentPageTitle = title
                                        isLoading = loading
                                    },
                                    sharedHttpClient = sharedClient
                                )
                                snifferClient.cachedUserAgent = settings.userAgentString
                                snifferClientRef = snifferClient
                                // webViewClient + XHR/fetch manifest kancası (iframe'ler dahil) + Service Worker dinleyicisi
                                snifferClient.attachTo(this)
                                webChromeClient = WebChromeClient()

                                // Tarayıcı içinde doğrudan dosya indirme tetiklenirse (Cloudflare sonrası zip/apk/mp4 vb.) yakala
                                setDownloadListener { downloadUrl, userAgent, _, mimetype, _ ->
                                    if (downloadUrl.isNullOrBlank()) return@setDownloadListener
                                    val cookie = runCatching { cookieManager.getCookie(downloadUrl) }.getOrNull().orEmpty()
                                    val task = VideoDownloadTask(
                                        url = downloadUrl,
                                        referer = currentPageUrl,
                                        userAgent = userAgent?.takeIf { it.isNotBlank() } ?: currentUserAgent,
                                        cookie = cookie,
                                        mimeType = mimetype,
                                        pageTitle = currentPageTitle,
                                        detectedLabel = "Doğrudan Dosya (${mimetype ?: "dosya"})"
                                    )
                                    if (detectedTasks.none { it.url == task.url }) {
                                        detectedTasks.add(0, task)
                                    }
                                }

                                if (initialUrl.startsWith("http://") || initialUrl.startsWith("https://")) {
                                    if (initialUrl.length > 8) {
                                        loadUrl(initialUrl)
                                    }
                                }
                                webViewRef = this
                            }
                        }
                    )
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // V3 Güvenlik Kuralı: Mini tarayıcı kapandığında WebView'ın diske çerez bırakmasını engelle.
            // Yakalanan çerez zaten yalnızca ilgili indirme için EphemeralSessionStore'da (RAM'de) tutulur.
            runCatching {
                val cm = CookieManager.getInstance()
                cm.removeAllCookies(null)
                cm.flush()
                WebStorage.getInstance().deleteAllData()
            }
            snifferClientRef?.shutdown()
            snifferClientRef = null
            webViewRef?.stopLoading()
            webViewRef?.clearCache(true)
            webViewRef?.clearHistory()
            webViewRef?.destroy()
            webViewRef = null
        }
    }
}

@Composable
private fun DetectedVideoBottomSheetPanel(
    tasks: List<VideoDownloadTask>,
    hasValidPageUrl: Boolean,
    onVerifyCloudflareClick: () -> Unit,
    onDownloadClick: (VideoDownloadTask) -> Unit
) {
    Surface(
        tonalElevation = 8.dp,
        shadowElevation = 12.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (tasks.isNotEmpty()) {
                            "Yakalanan Medya Akışları (${tasks.size})"
                        } else {
                            "Cloudflare & Medya Yakalayıcı"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Çerezler diske yazılmaz, loglanmaz; yalnızca bu site ve bu indirme boyunca RAM'de tutulur.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (hasValidPageUrl) {
                    Spacer(modifier = Modifier.width(8.dp))
                    FilledTonalButton(onClick = onVerifyCloudflareClick) {
                        Icon(
                            imageVector = AppIcons.VerifiedUser,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Oturumu Aktar")
                    }
                }
            }

            if (tasks.isNotEmpty()) {
                HorizontalDivider()

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(tasks, key = { it.url }) { task ->
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = task.pageTitle?.takeIf { it.isNotBlank() } ?: task.detectedLabel,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${task.detectedLabel} • ${task.url}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Button(onClick = { onDownloadClick(task) }) {
                                        Icon(
                                            imageVector = AppIcons.Download,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Videoyu İndir")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

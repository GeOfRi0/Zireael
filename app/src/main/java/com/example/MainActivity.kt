package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.example.ui.screens.DashboardScreen
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.DownloadViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: DownloadViewModel by viewModels()

    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            // Notification permission granted or denied
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        handleIncomingIntent(intent)

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DarkBackground
                ) {
                    DashboardScreen(viewModel = viewModel)
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Save live in-memory progress to Room when user backgrounds the app
        viewModel.flushActiveDownloadsToDatabase()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return

        when (intent.action) {
            Intent.ACTION_SEND -> {
                if (intent.type == "text/plain") {
                    val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                    extractUrlFromText(sharedText)?.let { url ->
                        routeIncomingUrl(url)
                    }
                }
            }
            Intent.ACTION_VIEW -> {
                val url = intent.dataString ?: intent.data?.toString()
                if (!url.isNullOrBlank()) {
                    routeIncomingUrl(url)

                    // Extract referrer if provided by browser / system
                    val referrerUri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_REFERRER, android.net.Uri::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_REFERRER)
                    }
                    val referrer = referrerUri?.toString()
                        ?: intent.getStringExtra("android.intent.extra.REFERRER_NAME")
                    if (!referrer.isNullOrBlank()) {
                        viewModel.setReferer(referrer)
                    }

                    // Extract headers bundle if provided by Chrome / Android browser
                    val headersBundle = intent.getBundleExtra("com.android.browser.headers")
                    headersBundle?.let { bundle ->
                        bundle.getString("Referer")?.let { viewModel.setReferer(it) }
                        bundle.getString("Cookie")?.let { viewModel.setCookies(it) }
                        bundle.getString("User-Agent")?.let {
                            viewModel.setCustomUserAgent(it)
                            viewModel.setUserAgentPreset(com.example.data.model.ChromePreset.CUSTOM)
                        }
                    }
                }
            }
        }
    }

    private fun extractUrlFromText(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://") ||
            trimmed.startsWith("magnet:", ignoreCase = true) || trimmed.startsWith("magnet://", ignoreCase = true)) {
            return trimmed
        }
        val magnetMatch = Regex("""magnet:\?[^\s]+""", RegexOption.IGNORE_CASE).find(text)
        if (magnetMatch != null) return magnetMatch.value
        val match = Regex("""https?://[^\s]+""").find(text)
        return match?.value
    }

    private fun routeIncomingUrl(url: String) {
        val cleanUrl = url.trim()

        // 0. Automatic Torrent / Magnet routing
        if (com.example.data.engine.TorrentDownloadEngine.isTorrentOrMagnet(cleanUrl)) {
            viewModel.setUrlInput(cleanUrl)
            viewModel.selectTab(0)
            viewModel.startTorrentDownload(cleanUrl)
            viewModel.setCapturedUrlBanner("Magnet/Torrent bağlantısı yakalandı: Torrent motoru başlatıldı 🧲")
            return
        }

        val lower = cleanUrl.lowercase().substringBefore('?')

        val isDirectFile = lower.endsWith(".apk") || lower.endsWith(".xapk") || lower.endsWith(".apks") ||
            lower.endsWith(".zip") || lower.endsWith(".rar") || lower.endsWith(".7z") || lower.endsWith(".tar") ||
            lower.endsWith(".gz") || lower.endsWith(".iso") || lower.endsWith(".dmg") || lower.endsWith(".exe") ||
            lower.endsWith(".pdf") || lower.endsWith(".epub") || lower.endsWith(".doc") || lower.endsWith(".docx") ||
            lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm") || lower.endsWith(".mp3") ||
            lower.endsWith(".flac") || lower.endsWith(".wav") || lower.endsWith(".m4a")

        viewModel.setUrlInput(cleanUrl)

        if (isDirectFile) {
            // Direct downloadable file -> Route to download engine (Tab 0)
            viewModel.selectTab(0)
            val fileName = cleanUrl.substringAfterLast("/").substringBefore("?").ifBlank { "İndirme Dosyası" }
            viewModel.setCapturedUrlBanner("İndirme bağlantısı motora aktarıldı: $fileName")
        } else {
            // Web page, streaming or video site (e.g. YouTube, Chrome share) -> Route to WebSnifferBrowser (Tab 3)
            viewModel.openInBrowser(cleanUrl)
            val host = try { android.net.Uri.parse(cleanUrl).host ?: cleanUrl } catch (e: Exception) { cleanUrl }
            viewModel.setCapturedUrlBanner("Paylaşılan bağlantı tarayıcıda açıldı ($host)")
        }
    }
}

package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.engine.ArchiveHelper
import com.example.data.engine.DownloadQueueManager
import com.example.data.engine.MediaSniffer
import com.example.data.engine.TurboDownloadEngine
import com.example.data.engine.TurboDownloadManager
import com.example.ui.player.TurboPlayerActivity
import com.example.data.local.AppDatabase
import com.example.data.local.DownloadEntity
import com.example.data.model.ChromeHeaderConfig
import com.example.data.model.ChromePreset
import com.example.data.model.DownloadStatus
import com.example.data.model.ExtractedMedia
import com.example.data.model.FileCategory
import com.example.data.model.SegmentProgress
import com.example.data.repository.DownloadRepository
import com.example.ui.components.checkBatteryOptimization
import com.example.ui.components.checkStoragePermission
import com.example.util.throttleLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SampleUrl(
    val title: String,
    val subtitle: String,
    val url: String,
    val category: FileCategory
)

class DownloadViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: DownloadRepository

    private val networkMonitor = com.example.data.engine.NetworkConnectivityMonitor(application)

    init {
        val database = AppDatabase.getInstance(application)
        val downloadEngine = TurboDownloadManager.getEngine(application)
        val mediaSniffer = MediaSniffer()
        val queueManager = TurboDownloadManager.getQueueManager(application)
        val torrentEngine = TurboDownloadManager.getTorrentEngine(application)
        val vaultManager = TurboDownloadManager.getVaultManager(application)
        repository = DownloadRepository(database.downloadDao(), downloadEngine, mediaSniffer, queueManager, torrentEngine, vaultManager)

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val activeList = database.downloadDao().getDownloadsByStatusList(DownloadStatus.DOWNLOADING.name)
            activeList.forEach { database.downloadDao().updateStatus(it.id, DownloadStatus.PAUSED.name) }
            val connectingList = database.downloadDao().getDownloadsByStatusList(DownloadStatus.CONNECTING.name)
            connectingList.forEach { database.downloadDao().updateStatus(it.id, DownloadStatus.PAUSED.name) }
            queueManager.processQueue()
        }
    }

    // UI Navigation Tab
    private val _selectedTab = MutableStateFlow(0)
    val selectedTab: StateFlow<Int> = _selectedTab.asStateFlow()

    // URL Bar input
    val urlInput = MutableStateFlow("")

    // Selected threads (1, 2, 4, 8)
    val selectedThreads = MutableStateFlow(4)

    // Chrome Headers & Browser Simulation State
    val selectedUserAgentPreset = MutableStateFlow(ChromePreset.CHROME_WINDOWS)
    val customUserAgentInput = MutableStateFlow("")
    val refererInput = MutableStateFlow("")
    val cookieInput = MutableStateFlow("")
    val customHeadersInput = MutableStateFlow("")
    val isHeadersExpanded = MutableStateFlow(false)

    // Sniffer State
    private val _isSniffing = MutableStateFlow(false)
    val isSniffing: StateFlow<Boolean> = _isSniffing.asStateFlow()

    private val _sniffedList = MutableStateFlow<List<ExtractedMedia>>(emptyMap<String, String>().let { emptyList() })
    val sniffedList: StateFlow<List<ExtractedMedia>> = _sniffedList.asStateFlow()

    private val _showSnifferSheet = MutableStateFlow(false)
    val showSnifferSheet: StateFlow<Boolean> = _showSnifferSheet.asStateFlow()

    // Library filtering
    val selectedCategoryFilter = MutableStateFlow(FileCategory.ALL)
    val searchQuery = MutableStateFlow("")

    // In-App Web Browser State
    val browserUrl = MutableStateFlow("https://www.youtube.com")
    private val _capturedMediaList = MutableStateFlow<List<ExtractedMedia>>(emptyList())
    val capturedMediaList: StateFlow<List<ExtractedMedia>> = _capturedMediaList.asStateFlow()

    // Video Quality Selector Dialog State
    private val _showQualitySelector = MutableStateFlow(false)
    val showQualitySelector: StateFlow<Boolean> = _showQualitySelector.asStateFlow()

    private val _qualityOptions = MutableStateFlow<List<ExtractedMedia>>(emptyList())
    val qualityOptions: StateFlow<List<ExtractedMedia>> = _qualityOptions.asStateFlow()

    private val _isAnalyzingResolutions = MutableStateFlow(false)
    val isAnalyzingResolutions: StateFlow<Boolean> = _isAnalyzingResolutions.asStateFlow()

    fun openInBrowser(url: String = urlInput.value) {
        val clean = normalizeUrl(url)
        if (clean.isNotBlank()) {
            browserUrl.value = clean
        }
        _selectedTab.value = 1
    }

    fun addCapturedMedia(
        url: String,
        title: String,
        mimeType: String,
        approxSize: Long = -1L,
        thumbnail: String? = null,
        headerConfig: ChromeHeaderConfig? = null
    ) {
        val current = _capturedMediaList.value.toMutableList()
        if (current.none { it.url == url }) {
            val category = FileCategory.detect(title, mimeType)
            val item = ExtractedMedia(
                url = url,
                title = title,
                category = category,
                mimeType = mimeType,
                approxSize = approxSize,
                thumbnailUrl = thumbnail,
                headerConfig = headerConfig
            )
            current.add(0, item)
            _capturedMediaList.value = current
            val typeDesc = when (category) {
                FileCategory.APKS -> "Uygulama (APK)"
                FileCategory.ARCHIVES -> "Arşiv (ZIP)"
                FileCategory.MEDIA -> if (mimeType.startsWith("audio/")) "Ses Parçası" else "Video Akışı"
                else -> "Dosya"
            }
            Toast.makeText(getApplication(), "⚡ $typeDesc Yakalandı: $title", Toast.LENGTH_SHORT).show()
        }
    }

    fun clearCapturedMedia() {
        _capturedMediaList.value = emptyList()
    }

    fun sniffSubframeMedia(iframeUrl: String, parentTitle: String = "", parentHeaderConfig: ChromeHeaderConfig? = null) {
        viewModelScope.launch {
            try {
                val headerConfig = parentHeaderConfig ?: buildCurrentHeaderConfig()
                val results = repository.sniffMedia(iframeUrl, headerConfig)
                val mediaResults = results.filter { it.category == FileCategory.MEDIA && it.url != iframeUrl }
                for (media in mediaResults) {
                    addCapturedMedia(
                        url = media.url,
                        title = if (media.title.isNotBlank() && media.title != "Video") media.title else "İframe Akışı - ${parentTitle.ifBlank { "Video" }}",
                        mimeType = media.mimeType,
                        approxSize = media.approxSize,
                        thumbnail = media.thumbnailUrl,
                        headerConfig = media.headerConfig ?: headerConfig
                    )
                }
            } catch (_: Exception) {}
        }
    }

    fun openQualitySelector(url: String = urlInput.value) {
        val cleanUrl = normalizeUrl(url)
        if (cleanUrl.isBlank()) {
            Toast.makeText(getApplication(), "Lütfen geçerli bir video bağlantısı girin", Toast.LENGTH_SHORT).show()
            return
        }

        viewModelScope.launch {
            _isSniffing.value = true
            Toast.makeText(getApplication(), "Video çözünürlükleri taranıyor...", Toast.LENGTH_SHORT).show()
            try {
                val headerConfig = buildCurrentHeaderConfig()
                val results = repository.sniffMedia(cleanUrl, headerConfig)
                val mediaResults = results.filter { it.category == FileCategory.MEDIA || it.category == FileCategory.ARCHIVES }
                if (mediaResults.isNotEmpty()) {
                    _qualityOptions.value = mediaResults
                    _showQualitySelector.value = true
                } else {
                    Toast.makeText(getApplication(), "Bu video için doğrudan akış bulunamadı. Lütfen 'Web Tarayıcı' sekmesinde açıp videoyu oynatın.", Toast.LENGTH_LONG).show()
                    openInBrowser(cleanUrl)
                }
            } catch (e: Exception) {
                Toast.makeText(getApplication(), "Hata: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                _isSniffing.value = false
            }
        }
    }

    fun dismissQualitySelector() {
        _showQualitySelector.value = false
    }

    fun analyzeAndSelectResolution(media: ExtractedMedia, currentWebUrl: String? = null) {
        viewModelScope.launch {
            _isAnalyzingResolutions.value = true
            Toast.makeText(getApplication(), "🔍 Video çözünürlükleri taranıyor (1080p, 720p, 480p...)", Toast.LENGTH_SHORT).show()
            try {
                val headerConfig = media.headerConfig ?: buildCurrentHeaderConfig()
                val resolutions = repository.analyzeVideoResolutions(media, currentWebUrl, headerConfig)
                val resolutionsWithHeaders = resolutions.map { res ->
                    if (res.headerConfig == null) res.copy(headerConfig = headerConfig) else res
                }
                if (resolutionsWithHeaders.isNotEmpty()) {
                    _qualityOptions.value = resolutionsWithHeaders
                    _showQualitySelector.value = true
                } else {
                    _qualityOptions.value = listOf(media.copy(headerConfig = headerConfig))
                    _showQualitySelector.value = true
                }
            } catch (e: Exception) {
                Toast.makeText(getApplication(), "Çözünürlük analiz hatası: ${e.message}", Toast.LENGTH_SHORT).show()
                val fallbackConfig = media.headerConfig ?: buildCurrentHeaderConfig()
                _qualityOptions.value = listOf(media.copy(headerConfig = fallbackConfig))
                _showQualitySelector.value = true
            } finally {
                _isAnalyzingResolutions.value = false
            }
        }
    }

    fun selectAndDownloadQuality(media: ExtractedMedia, threadCount: Int = selectedThreads.value) {
        _showQualitySelector.value = false
        val headerConfig = media.headerConfig ?: buildCurrentHeaderConfig()
        viewModelScope.launch {
            repository.enqueueDownload(
                url = media.url,
                customFileName = media.title,
                threadCount = threadCount,
                headerConfig = headerConfig
            )
        }
        Toast.makeText(getApplication(), "İndirme başlatıldı: ${media.title} 🚀", Toast.LENGTH_SHORT).show()
    }

    // In-app preview media
    private val _previewEntity = MutableStateFlow<DownloadEntity?>(null)
    val previewEntity: StateFlow<DownloadEntity?> = _previewEntity.asStateFlow()

    // External link captured banner
    private val _capturedUrlBanner = MutableStateFlow<String?>(null)
    val capturedUrlBanner: StateFlow<String?> = _capturedUrlBanner.asStateFlow()

    fun setCapturedUrlBanner(message: String?) {
        _capturedUrlBanner.value = message
    }

    fun dismissCapturedUrlBanner() {
        _capturedUrlBanner.value = null
    }

    // All downloads from DB (throttled to 500ms to eliminate UI churn)
    val allDownloads = repository.allDownloads
        .throttleLatest(500L)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            emptyList()
        )

    // Active downloads
    val activeDownloads = allDownloads.map { list ->
        list.filter { it.status == DownloadStatus.DOWNLOADING.name || it.status == DownloadStatus.CONNECTING.name || it.status == DownloadStatus.QUEUED.name }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Completed downloads
    val completedDownloads = allDownloads.map { list ->
        list.filter { it.status == DownloadStatus.COMPLETED.name }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Filtered downloads for Library tab
    val filteredLibraryDownloads = combine(
        allDownloads,
        selectedCategoryFilter,
        searchQuery
    ) { downloads, category, query ->
        downloads.filter { entity ->
            val matchesCategory = if (category == FileCategory.ALL) true else entity.category == category.name
            val matchesQuery = if (query.isBlank()) true else entity.fileName.contains(query, ignoreCase = true)
            matchesCategory && matchesQuery
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Live segment progress & speed maps (throttled to 500ms)
    val segmentProgressMap: StateFlow<Map<String, List<SegmentProgress>>> = repository.segmentProgressMap
        .throttleLatest(500L)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            repository.segmentProgressMap.value
        )

    val speedMap: StateFlow<Map<String, Long>> = repository.speedMap
        .throttleLatest(500L)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            repository.speedMap.value
        )

    // Queue properties
    val maxConcurrentDownloads: StateFlow<Int> = repository.maxConcurrentDownloads
    val isQueuePaused: StateFlow<Boolean> = repository.isQueuePaused
    val onlyWifiMode: StateFlow<Boolean> = repository.onlyWifiMode
    val isNetworkConnected: StateFlow<Boolean> = networkMonitor.isConnected
    val isWifiConnected: StateFlow<Boolean> = networkMonitor.isWifi
    val maxSpeedLimitBytesPerSec: StateFlow<Long> = repository.maxSpeedLimitBytesPerSec

    fun setOnlyWifiMode(enabled: Boolean) {
        repository.setOnlyWifiMode(enabled)
    }

    fun setMaxSpeedLimit(limitBytesPerSec: Long) {
        repository.setMaxSpeedLimit(limitBytesPerSec)
    }

    val queuedDownloads = allDownloads.map { list ->
        list.filter { it.status == DownloadStatus.QUEUED.name }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val runningDownloads = allDownloads.map { list ->
        list.filter { it.status == DownloadStatus.DOWNLOADING.name || it.status == DownloadStatus.CONNECTING.name }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pausedDownloads = allDownloads.map { list ->
        list.filter { it.status == DownloadStatus.PAUSED.name }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Permissions & Battery Optimization onboarding modal
    val showPermissionsOnboarding = MutableStateFlow(
        !checkStoragePermission(application) || !checkBatteryOptimization(application)
    )

    fun dismissPermissionsOnboarding() {
        showPermissionsOnboarding.value = false
    }

    fun openPermissionsOnboarding() {
        showPermissionsOnboarding.value = true
    }

    fun setMaxConcurrentDownloads(limit: Int) {
        repository.setMaxConcurrentDownloads(limit)
    }

    fun pauseQueue() {
        repository.pauseQueue()
    }

    fun resumeQueue() {
        repository.resumeQueue()
    }

    fun startAllQueue() {
        viewModelScope.launch {
            repository.startAllQueuedAndPaused()
        }
    }

    fun prioritizeQueueItem(id: String) {
        viewModelScope.launch {
            repository.prioritizeDownload(id)
        }
    }

    fun requeueDownload(id: String) {
        viewModelScope.launch {
            repository.retryDownload(id)
        }
    }

    // Overall current download speed (bytes/sec, throttled to 500ms)
    val totalDownloadSpeed: StateFlow<Long> = speedMap.map { map ->
        map.values.sum()
    }.throttleLatest(500L).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    // Quick Sample URLs for instant 1-tap testing
    val sampleUrls = listOf(
        SampleUrl(
            title = "Big Buck Bunny HD Video",
            subtitle = "MP4 Video (10.5 MB)",
            url = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
            category = FileCategory.MEDIA
        ),
        SampleUrl(
            title = "Yüksek Kalite Doğa Fotoğrafı",
            subtitle = "JPG Yüksek Çözünürlük (2.1 MB)",
            url = "https://images.unsplash.com/photo-1506744038136-46273834b3fb?w=1920&q=80",
            category = FileCategory.MEDIA
        ),
        SampleUrl(
            title = "Örnek PDF Dokümanı",
            subtitle = "W3C PDF Belgesi (1.2 MB)",
            url = "https://www.w3.org/WAI/ER/tests/xhtml/testfiles/resources/pdf/dummy.pdf",
            category = FileCategory.DOCUMENTS
        ),
        SampleUrl(
            title = "Akustik Müzik Parçası",
            subtitle = "MP3 Ses Dosyası (3.4 MB)",
            url = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3",
            category = FileCategory.MEDIA
        ),
        SampleUrl(
            title = "Örnek Zip Arşivi",
            subtitle = "ZIP Sıkıştırılmış Dosya",
            url = "https://github.com/octocat/Hello-World/archive/refs/heads/master.zip",
            category = FileCategory.ARCHIVES
        )
    )

    fun selectTab(tab: Int) {
        _selectedTab.value = tab
    }

    fun setUrlInput(url: String) {
        urlInput.value = url
    }

    private fun normalizeUrl(url: String): String {
        var trimmed = url.trim()
        if (trimmed.isBlank()) return ""
        if (trimmed.startsWith("magnet:", ignoreCase = true) || trimmed.startsWith("magnet://", ignoreCase = true)) {
            return trimmed
        }
        if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) {
            trimmed = "https://$trimmed"
        }
        return trimmed
    }

    fun setUserAgentPreset(preset: ChromePreset) {
        selectedUserAgentPreset.value = preset
    }

    fun setCustomUserAgent(ua: String) {
        customUserAgentInput.value = ua
    }

    fun setReferer(ref: String) {
        refererInput.value = ref
    }

    fun setCookies(cookies: String) {
        cookieInput.value = cookies
    }

    fun setCustomHeaders(headers: String) {
        customHeadersInput.value = headers
    }

    fun toggleHeadersExpanded() {
        isHeadersExpanded.value = !isHeadersExpanded.value
    }

    fun resetHeadersToDefault() {
        selectedUserAgentPreset.value = ChromePreset.CHROME_WINDOWS
        customUserAgentInput.value = ""
        refererInput.value = ""
        cookieInput.value = ""
        customHeadersInput.value = ""
    }

    fun buildCurrentHeaderConfig(): ChromeHeaderConfig {
        return ChromeHeaderConfig(
            preset = selectedUserAgentPreset.value,
            customUserAgent = customUserAgentInput.value.takeIf { it.isNotBlank() },
            customReferer = refererInput.value.takeIf { it.isNotBlank() },
            customCookies = cookieInput.value.takeIf { it.isNotBlank() },
            extraHeaders = ChromeHeaderConfig.parseHeadersText(customHeadersInput.value)
        )
    }

    fun startTorrentDownload(url: String, customTitle: String? = null) {
        val cleanUrl = if (url.trim().startsWith("magnet:", ignoreCase = true) || url.trim().startsWith("magnet://", ignoreCase = true)) {
            url.trim()
        } else {
            normalizeUrl(url)
        }
        if (cleanUrl.isBlank()) {
            Toast.makeText(getApplication(), "Lütfen geçerli bir torrent veya magnet adresi girin", Toast.LENGTH_SHORT).show()
            return
        }

        viewModelScope.launch {
            try {
                repository.startTorrentDownload(cleanUrl, customTitle)
                urlInput.value = ""
                selectTab(0)
                Toast.makeText(getApplication(), "Torrent/Magnet indirmesi başlatıldı 🧲⚡", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(getApplication(), "Torrent başlatılamadı: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun startDirectDownload(
        url: String = urlInput.value,
        headerConfig: ChromeHeaderConfig? = null,
        customFileName: String? = null
    ) {
        val cleanUrl = normalizeUrl(url)
        if (cleanUrl.isBlank()) {
            Toast.makeText(getApplication(), "Lütfen geçerli bir bağlantı adresi girin", Toast.LENGTH_SHORT).show()
            return
        }

        // Automatic routing: If link is a magnet or .torrent file, route to TorrentDownloadEngine
        if (repository.isTorrentOrMagnet(cleanUrl)) {
            startTorrentDownload(cleanUrl, customFileName)
            return
        }

        viewModelScope.launch {
            try {
                val effectiveHeaders = headerConfig ?: buildCurrentHeaderConfig()

                // If URL is a video site (YouTube, Instagram, TikTok, etc.) or web page, resolve media first instead of saving HTML as .bin
                if (isLikelyWebPage(cleanUrl)) {
                    _isSniffing.value = true
                    Toast.makeText(getApplication(), "Medya akışları ve kaliteler çözümleniyor...", Toast.LENGTH_SHORT).show()
                    val results = repository.sniffMedia(cleanUrl, effectiveHeaders)
                    _isSniffing.value = false

                    val isInstagram = cleanUrl.contains("instagram.com")
                    if (isInstagram) {
                        val videoStreams = results.filter { it.mimeType.startsWith("video/") }
                        if (videoStreams.isNotEmpty()) {
                            val media = videoStreams.first()
                            repository.enqueueDownload(
                                url = media.url,
                                customFileName = media.title,
                                threadCount = selectedThreads.value,
                                headerConfig = media.headerConfig ?: effectiveHeaders
                            )
                            urlInput.value = ""
                            Toast.makeText(getApplication(), "Instagram videosu indiriliyor: ${media.title} 🚀", Toast.LENGTH_SHORT).show()
                            return@launch
                        } else {
                            Toast.makeText(getApplication(), "Instagram video akışı giriş korumalı. 'Web Tarayıcı'da açılıyor, videoyu oynatınca otomatik yakalanacaktır ⚡", Toast.LENGTH_LONG).show()
                            openInBrowser(cleanUrl)
                            return@launch
                        }
                    }

                    val directMedia = results.filter { it.url != cleanUrl && (it.category == FileCategory.MEDIA || it.category == FileCategory.APKS || it.category == FileCategory.ARCHIVES || it.category == FileCategory.DOCUMENTS) }
                    if (directMedia.size > 1) {
                        _qualityOptions.value = directMedia
                        _showQualitySelector.value = true
                        return@launch
                    } else if (directMedia.size == 1) {
                        val media = directMedia.first()
                        repository.enqueueDownload(
                            url = media.url,
                            customFileName = media.title,
                            threadCount = selectedThreads.value,
                            headerConfig = media.headerConfig ?: effectiveHeaders
                        )
                        urlInput.value = ""
                        Toast.makeText(getApplication(), "Medya çözümlendi: ${media.title} indiriliyor 🚀", Toast.LENGTH_SHORT).show()
                        return@launch
                    } else {
                        val isSocial = cleanUrl.contains("youtube.com") || cleanUrl.contains("youtu.be") || cleanUrl.contains("tiktok.com")
                        val msg = if (isSocial) {
                            "Bu video doğrudan taranamadı. 'Web Tarayıcı' sekmesinde açarak videoyu oynatın; akış anında yakalanacaktır."
                        } else {
                            "Bu sayfada doğrudan medya bulunamadı. Web Tarayıcıda açarak videoyu başlatın."
                        }
                        Toast.makeText(getApplication(), msg, Toast.LENGTH_LONG).show()
                        openInBrowser(cleanUrl)
                        return@launch
                    }
                }

                repository.enqueueDownload(
                    url = cleanUrl,
                    customFileName = customFileName,
                    threadCount = selectedThreads.value,
                    headerConfig = effectiveHeaders
                )
                urlInput.value = ""
                Toast.makeText(getApplication(), "İndirme başlatıldı 🚀 (Chrome Kimliği Aktif)", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(getApplication(), "Hata: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                _isSniffing.value = false
            }
        }
    }

    private fun isLikelyWebPage(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.contains("youtube.com") || lower.contains("youtu.be") ||
            lower.contains("instagram.com") || lower.contains("tiktok.com") ||
            lower.contains("twitter.com") || lower.contains("x.com") ||
            lower.contains("facebook.com") || lower.contains("vimeo.com") ||
            lower.contains("dailymotion.com") || lower.contains("reddit.com") ||
            lower.contains("threads.net") || lower.contains("pinterest.com") ||
            lower.contains("twitch.tv") || lower.contains("bilibili.com")
        ) {
            return true
        }

        val clean = url.substringBefore('?').substringBefore('#').lowercase()

        // Explicit web page script extensions
        val webExtensions = listOf(".html", ".htm", ".php", ".aspx", ".asp", ".jsp", ".do", ".action", ".cgi", ".cfm")
        if (webExtensions.any { clean.endsWith(it) }) {
            return true
        }

        // Direct downloadable media/file extensions
        val directExtensions = listOf(
            ".mp4", ".mkv", ".webm", ".avi", ".mov", ".flv", ".3gp", ".ts", ".m4v",
            ".mp3", ".wav", ".flac", ".aac", ".ogg", ".m4a", ".opus",
            ".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp", ".svg",
            ".pdf", ".epub", ".docx", ".xlsx", ".pptx", ".txt",
            ".zip", ".rar", ".7z", ".tar", ".gz", ".apk", ".iso", ".exe", ".dmg"
        )
        if (directExtensions.any { clean.endsWith(it) }) {
            return false
        }

        val lastSeg = clean.substringAfterLast('/', "")
        if (!lastSeg.contains('.') || lastSeg.isBlank()) {
            return true
        }

        val ext = ".${lastSeg.substringAfterLast('.')}"
        return !directExtensions.contains(ext)
    }

    fun startSniffing(url: String = urlInput.value) {
        val cleanUrl = normalizeUrl(url)
        if (cleanUrl.isBlank()) {
            Toast.makeText(getApplication(), "Lütfen taranacak bir web sayfası veya bağlantı girin", Toast.LENGTH_SHORT).show()
            return
        }

        viewModelScope.launch {
            _isSniffing.value = true
            Toast.makeText(getApplication(), "Sayfa taranıyor, medyalar aranıyor...", Toast.LENGTH_SHORT).show()
            try {
                val headerConfig = buildCurrentHeaderConfig()
                val results = repository.sniffMedia(cleanUrl, headerConfig)
                if (results.isEmpty()) {
                    Toast.makeText(getApplication(), "Sayfada indirilebilir medya bulunamadı", Toast.LENGTH_SHORT).show()
                } else {
                    _sniffedList.value = results.mapIndexed { idx, item ->
                        item.copy(isSelected = idx == 0)
                    }
                    _showSnifferSheet.value = true
                    Toast.makeText(getApplication(), "${results.size} adet indirilebilir medya ve dosya bulundu!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(getApplication(), "Tarama hatası: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                _isSniffing.value = false
            }
        }
    }

    fun toggleSniffedItem(url: String) {
        val current = _sniffedList.value
        _sniffedList.value = current.map { item ->
            if (item.url == url) item.copy(isSelected = !item.isSelected) else item
        }
    }

    fun selectAllSniffedItems(select: Boolean) {
        _sniffedList.value = _sniffedList.value.map { it.copy(isSelected = select) }
    }

    fun downloadSelectedSniffedItems() {
        val selected = _sniffedList.value.filter { it.isSelected }
        if (selected.isEmpty()) {
            Toast.makeText(getApplication(), "Lütfen en az bir öğe seçin", Toast.LENGTH_SHORT).show()
            return
        }

        viewModelScope.launch {
            val baseHeaderConfig = buildCurrentHeaderConfig()
            for (item in selected) {
                val effectiveHeader = item.headerConfig ?: baseHeaderConfig
                repository.enqueueDownload(
                    url = item.url,
                    customFileName = item.title,
                    threadCount = selectedThreads.value,
                    headerConfig = effectiveHeader
                )
            }
            _showSnifferSheet.value = false
            _sniffedList.value = emptyList()
            urlInput.value = ""
            Toast.makeText(getApplication(), "${selected.size} öğe indirme kuyruğuna eklendi!", Toast.LENGTH_SHORT).show()
        }
    }

    fun dismissSnifferSheet() {
        _showSnifferSheet.value = false
    }

    fun pauseDownload(id: String) {
        repository.pauseDownload(id)
    }

    fun resumeDownload(id: String) {
        repository.resumeDownload(id)
    }

    fun cancelDownload(id: String) {
        repository.cancelDownload(id)
    }

    fun retryDownload(id: String) {
        viewModelScope.launch {
            repository.retryDownload(id)
        }
    }

    fun deleteDownload(id: String, deleteFile: Boolean = true) {
        viewModelScope.launch {
            repository.deleteDownload(id, deleteFile)
            Toast.makeText(getApplication(), "Öğe silindi", Toast.LENGTH_SHORT).show()
        }
    }

    fun flushActiveDownloadsToDatabase() {
        viewModelScope.launch {
            repository.flushAllActiveDownloadsToDatabase()
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            repository.clearHistory()
            Toast.makeText(getApplication(), "Geçmiş temizlendi", Toast.LENGTH_SHORT).show()
        }
    }

    fun showPreview(entity: DownloadEntity) {
        _previewEntity.value = entity
    }

    fun dismissPreview() {
        _previewEntity.value = null
    }

    fun openDownloadedFile(context: Context, entity: DownloadEntity) {
        val file = File(entity.filePath)
        if (!file.exists()) {
            Toast.makeText(context, "Dosya bulunamadı: ${entity.fileName}", Toast.LENGTH_SHORT).show()
            return
        }

        val category = FileCategory.detect(entity.fileName, entity.mimeType)
        val isMedia = category == FileCategory.MEDIA ||
                entity.mimeType.startsWith("video/") ||
                entity.mimeType.startsWith("audio/") ||
                isMediaExtension(entity.fileName)

        val isArchive = category == FileCategory.ARCHIVES || ArchiveHelper.isArchive(entity.fileName)

        when {
            isMedia -> {
                TurboPlayerActivity.start(
                    context = context,
                    filePath = entity.filePath,
                    title = entity.fileName,
                    mimeType = entity.mimeType,
                    isVault = false
                )
            }
            isArchive -> {
                extractArchive(context, entity)
            }
            else -> {
                try {
                    val uri: Uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.provider",
                        file
                    )

                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, entity.mimeType.ifBlank { "*/*" })
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(Intent.createChooser(intent, "Dosyayı aç"))
                } catch (e: Exception) {
                    Toast.makeText(context, "Dosyayı açacak uygun bir uygulama bulunamadı", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Extracts an archive file (.zip, .rar, .7z, .tar, .gz) in the background with ArchiveHelper.
     */
    fun extractArchive(context: Context, entity: DownloadEntity) {
        val file = File(entity.filePath)
        if (!file.exists()) {
            Toast.makeText(context, "Arşiv dosyası bulunamadı: ${entity.fileName}", Toast.LENGTH_SHORT).show()
            return
        }

        viewModelScope.launch {
            Toast.makeText(context, "Arşiv çıkarılıyor: ${entity.fileName}...", Toast.LENGTH_SHORT).show()
            setCapturedUrlBanner("Arşiv çıkarılıyor: ${entity.fileName}")

            val result = ArchiveHelper.extractArchive(file)
            result.fold(
                onSuccess = { res ->
                    val msg = "Arşiv çıkarıldı: ${res.outputDirectory.name} (${res.extractedCount} dosya)"
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    setCapturedUrlBanner(msg)
                },
                onFailure = { err ->
                    val msg = "Arşiv çıkarma başarısız: ${err.message}"
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    setCapturedUrlBanner(msg)
                }
            )
        }
    }

    /**
     * Opens an Encrypted Vault media file in the secure TurboPlayerActivity without external leakage.
     */
    fun openVaultMediaFile(context: Context, vaultFile: File) {
        val originalName = vaultFile.name.removeSuffix(".tvault")
        TurboPlayerActivity.start(
            context = context,
            filePath = vaultFile.absolutePath,
            title = originalName,
            isVault = true
        )
    }

    private fun isMediaExtension(fileName: String): Boolean {
        val lower = fileName.lowercase().substringBefore('?')
        return lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm") ||
                lower.endsWith(".avi") || lower.endsWith(".mov") || lower.endsWith(".mp3") ||
                lower.endsWith(".m4a") || lower.endsWith(".flac") || lower.endsWith(".wav") ||
                lower.endsWith(".aac")
    }

    fun shareDownloadedFile(context: Context, entity: DownloadEntity) {
        val file = File(entity.filePath)
        if (!file.exists()) {
            Toast.makeText(context, "Paylaşılacak dosya bulunamadı", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                file
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = entity.mimeType.ifBlank { "*/*" }
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Dosyayı Paylaş"))
        } catch (e: Exception) {
            Toast.makeText(context, "Paylaşım başlatılamadı", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        fun formatSpeed(bytesPerSec: Long): String {
            if (bytesPerSec <= 0) return "0 KB/s"
            val df = DecimalFormat("#.##")
            return when {
                bytesPerSec >= 1024 * 1024 * 1024 -> "${df.format(bytesPerSec / (1024.0 * 1024.0 * 1024.0))} GB/s"
                bytesPerSec >= 1024 * 1024 -> "${df.format(bytesPerSec / (1024.0 * 1024.0))} MB/s"
                bytesPerSec >= 1024 -> "${df.format(bytesPerSec / 1024.0)} KB/s"
                else -> "$bytesPerSec B/s"
            }
        }

        fun formatBytes(bytes: Long): String {
            if (bytes <= 0) return "Bilinmiyor"
            val df = DecimalFormat("#.#")
            return when {
                bytes >= 1024L * 1024L * 1024L -> "${df.format(bytes / (1024.0 * 1024.0 * 1024.0))} GB"
                bytes >= 1024L * 1024L -> "${df.format(bytes / (1024.0 * 1024.0))} MB"
                bytes >= 1024L -> "${df.format(bytes / 1024.0)} KB"
                else -> "$bytes B"
            }
        }

        fun formatDate(timestamp: Long): String {
            if (timestamp <= 0) return ""
            val sdf = SimpleDateFormat("dd MMM, HH:mm", Locale.forLanguageTag("tr"))
            return sdf.format(Date(timestamp))
        }

        fun calculateEta(downloaded: Long, total: Long, speed: Long): String {
            if (speed <= 0 || total <= 0 || downloaded >= total) return "--:--"
            val remainingBytes = total - downloaded
            val remainingSec = remainingBytes / speed
            val min = remainingSec / 60
            val sec = remainingSec % 60
            return String.format(Locale.getDefault(), "%02d:%02d", min, sec)
        }

        suspend fun computeFileHash(file: File, algorithm: String = "SHA-256"): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (!file.exists() || !file.canRead()) return@withContext ""
            try {
                val digest = java.security.MessageDigest.getInstance(algorithm)
                file.inputStream().use { input ->
                    val buffer = ByteArray(8 * 1024)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        digest.update(buffer, 0, bytesRead)
                    }
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            } catch (e: Exception) {
                ""
            }
        }
    }

    // Vault (Gizli Kasa) Operations
    val isVaultUnlocked: StateFlow<Boolean> = repository.isVaultUnlocked ?: MutableStateFlow(false)
    val vaultFiles = MutableStateFlow<List<com.example.data.engine.VaultFileItem>>(emptyList())

    fun refreshVaultFiles() {
        vaultFiles.value = repository.listVaultFiles()
    }

    fun moveFileToVault(entity: DownloadEntity) {
        val file = File(entity.filePath)
        if (!file.exists()) {
            Toast.makeText(getApplication(), "Dosya bulunamadı", Toast.LENGTH_SHORT).show()
            return
        }
        viewModelScope.launch {
            val result = repository.encryptFileToVault(file, deleteSource = true)
            if (result.isSuccess) {
                repository.deleteDownload(entity.id, deleteFileFromStorage = false)
                refreshVaultFiles()
                Toast.makeText(getApplication(), "${entity.fileName} gizli kasaya kilitlendi 🔒", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(getApplication(), "Kasaya taşıma başarısız: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun restoreFileFromVault(vaultFile: File) {
        viewModelScope.launch {
            val targetDir = com.example.data.engine.DownloadStorageManager.getDownloadDirectory(getApplication())
            val result = repository.decryptFileFromVault(vaultFile, targetDir)
            if (result.isSuccess) {
                repository.deleteVaultFile(vaultFile)
                refreshVaultFiles()
                Toast.makeText(getApplication(), "Dosya kasadan çıkarıldı ve galeriye aktarıldı 📥", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(getApplication(), "Kasadan çıkarma hatası: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun unlockVaultWithPin(pin: String): Boolean {
        val verified = repository.verifyVaultPin(pin)
        if (verified) {
            repository.unlockVault()
            refreshVaultFiles()
        }
        return verified
    }

    fun lockVault() {
        repository.lockVault()
    }
}

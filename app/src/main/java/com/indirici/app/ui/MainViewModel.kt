package com.indirici.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.indirici.app.data.settings.SettingsRepository
import com.indirici.app.domain.exception.MediaEngineException
import com.indirici.app.domain.model.AppSettings
import com.indirici.app.domain.model.DownloadItem
import com.indirici.app.domain.model.EngineType
import com.indirici.app.domain.model.UrlMetadata
import com.indirici.app.domain.model.VideoDownloadTask
import com.indirici.app.engine.EngineRouter
import com.indirici.app.engine.MediaEngine
import com.indirici.app.engine.MediaFormatCatalog
import com.indirici.app.service.DownloadQueueCoordinator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AddDownloadUiState(
    val isAnalyzing: Boolean = false,
    val metadata: UrlMetadata? = null,
    val selectedFormatId: String = MediaFormatCatalog.DEFAULT_FORMAT_ID,
    val hasCloudflareSession: Boolean = false,
    val errorMessage: String? = null,
    val errorDetail: String? = null
)

class MainViewModel(
    private val queueCoordinator: DownloadQueueCoordinator,
    private val settingsRepository: SettingsRepository,
    private val engineRouter: EngineRouter
) : ViewModel() {

    val downloads: StateFlow<List<DownloadItem>> = queueCoordinator.downloadsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val settings: StateFlow<AppSettings> = settingsRepository.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    private val _addDialogState = MutableStateFlow(AddDownloadUiState())
    val addDialogState: StateFlow<AddDownloadUiState> = _addDialogState.asStateFlow()

    fun resetAddDialogState() {
        queueCoordinator.clearPreEnqueueSession()
        _addDialogState.value = AddDownloadUiState()
    }

    fun selectFormat(formatId: String) {
        _addDialogState.update { it.copy(selectedFormatId = formatId) }
    }

    /**
     * V3: Kullanıcı mini tarayıcıda Cloudflare doğrulamasını geçtiğinde oturumu RAM'e alır
     * ve ilgili linki doğrulanmış oturumla yeniden analiz eder.
     */
    fun onCloudflareSessionCaptured(
        targetUrl: String,
        userAgent: String,
        cookie: String,
        referer: String
    ) {
        queueCoordinator.storePreEnqueueSession(
            targetUrl = targetUrl,
            userAgent = userAgent,
            cookie = cookie,
            referer = referer
        )
        _addDialogState.update {
            it.copy(
                hasCloudflareSession = true,
                errorMessage = null,
                errorDetail = null
            )
        }
        analyzeUrl(targetUrl)
    }

    fun analyzeUrl(url: String) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) {
            _addDialogState.update {
                it.copy(
                    errorMessage = "Lütfen geçerli bir link girin.",
                    errorDetail = null
                )
            }
            return
        }
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            _addDialogState.update {
                it.copy(
                    errorMessage = "Link http:// veya https:// ile başlamalıdır.",
                    errorDetail = null
                )
            }
            return
        }

        viewModelScope.launch {
            _addDialogState.update {
                it.copy(
                    isAnalyzing = true,
                    errorMessage = null,
                    errorDetail = null,
                    metadata = null
                )
            }
            try {
                val meta = queueCoordinator.analyzeUrl(trimmed)
                val initialFormat = meta.availableFormats.firstOrNull {
                    it.formatId == MediaFormatCatalog.DEFAULT_FORMAT_ID
                }?.formatId ?: meta.availableFormats.firstOrNull()?.formatId ?: MediaFormatCatalog.DEFAULT_FORMAT_ID

                _addDialogState.update {
                    it.copy(
                        isAnalyzing = false,
                        metadata = meta,
                        selectedFormatId = initialFormat,
                        errorMessage = null,
                        errorDetail = null
                    )
                }
            } catch (e: Exception) {
                val mediaEx = (e as? MediaEngineException)
                    ?: (e.cause as? MediaEngineException)
                val rawError = mediaEx?.rawError
                    ?: e.message?.trim()?.takeIf { it.isNotBlank() }
                    ?: "Bilinmeyen hata"
                val version = mediaEx?.ytDlpVersion
                    ?: (engineRouter.mediaEngine as? MediaEngine)?.getYtDlpVersion()
                    ?: "Bilinmiyor"
                val detail = buildString {
                    append("yt-dlp sürümü: ").appendLine(version)
                    appendLine()
                    append(rawError)
                }.trim()

                _addDialogState.update {
                    it.copy(
                        isAnalyzing = false,
                        metadata = null,
                        errorMessage = "Bu link indirilemedi.",
                        errorDetail = detail
                    )
                }
            }
        }
    }

    fun startDownload(
        url: String,
        customFileName: String?,
        chunkCount: Int,
        onSuccess: () -> Unit
    ) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) {
            _addDialogState.update {
                it.copy(
                    errorMessage = "Lütfen bir indirme linki yapıştırın.",
                    errorDetail = null
                )
            }
            return
        }
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            _addDialogState.update {
                it.copy(
                    errorMessage = "Link http:// veya https:// ile başlamalıdır.",
                    errorDetail = null
                )
            }
            return
        }

        viewModelScope.launch {
            try {
                val currentState = _addDialogState.value
                val detectedEngine = engineRouter.detectEngineForUrl(trimmed)

                if (detectedEngine.engineType == EngineType.MEDIA && currentState.metadata == null) {
                    analyzeUrl(trimmed)
                    return@launch
                }

                val meta = currentState.metadata
                val targetUrl = meta?.resolvedUrl?.takeIf { it.isNotBlank() } ?: trimmed
                val engineType = meta?.engineType ?: detectedEngine.engineType
                val chosenFormatId = if (engineType == EngineType.MEDIA) {
                    currentState.selectedFormatId
                } else {
                    null
                }

                val chosenFormatSize = meta?.availableFormats
                    ?.firstOrNull { it.formatId == chosenFormatId }
                    ?.estimatedSizeBytes
                    ?.takeIf { it > 0L }
                    ?: meta?.totalBytes
                    ?: 0L

                queueCoordinator.enqueueDownload(
                    url = targetUrl,
                    customFileName = customFileName,
                    customChunkCount = chunkCount,
                    selectedFormatId = chosenFormatId,
                    explicitEngineType = engineType,
                    estimatedTotalBytes = chosenFormatSize
                )
                resetAddDialogState()
                onSuccess()
            } catch (e: Exception) {
                val mediaEx = (e as? MediaEngineException)
                    ?: (e.cause as? MediaEngineException)
                val rawError = mediaEx?.rawError
                    ?: e.message?.trim()?.takeIf { it.isNotBlank() }
                    ?: "Bilinmeyen hata"
                val version = mediaEx?.ytDlpVersion
                    ?: (engineRouter.mediaEngine as? MediaEngine)?.getYtDlpVersion()
                    ?: "Bilinmiyor"
                val detail = buildString {
                    append("yt-dlp sürümü: ").appendLine(version)
                    appendLine()
                    append(rawError)
                }.trim()

                _addDialogState.update {
                    it.copy(
                        errorMessage = "Bu link indirilemedi.",
                        errorDetail = detail
                    )
                }
            }
        }
    }

    fun startSniffedVideoDownload(task: VideoDownloadTask) {
        viewModelScope.launch {
            runCatching {
                queueCoordinator.enqueueSniffedVideoTask(task)
            }
        }
    }

    fun pauseDownload(id: Long) = queueCoordinator.pauseDownload(id)
    fun resumeDownload(id: Long) = queueCoordinator.resumeDownload(id)
    fun cancelDownload(id: Long) = queueCoordinator.cancelDownload(id)
    fun deleteDownload(id: Long) = queueCoordinator.deleteDownload(id)

    fun saveSettings(defaultChunks: Int, maxConcurrent: Int) {
        viewModelScope.launch {
            settingsRepository.updateSettings(
                defaultChunkCount = defaultChunks,
                maxConcurrentDownloads = maxConcurrent
            )
        }
    }

    class Factory(
        private val queueCoordinator: DownloadQueueCoordinator,
        private val settingsRepository: SettingsRepository,
        private val engineRouter: EngineRouter
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MainViewModel(queueCoordinator, settingsRepository, engineRouter) as T
        }
    }
}

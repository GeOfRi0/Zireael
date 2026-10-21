package com.indirici.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.indirici.app.domain.model.DownloadItem
import com.indirici.app.domain.model.DownloadStatus
import com.indirici.app.engine.MediaFormatCatalog
import com.indirici.app.ui.browser.MiniBrowserDialog
import com.indirici.app.ui.components.AddDownloadDialog
import com.indirici.app.ui.components.SettingsDialog
import com.indirici.app.ui.util.FormatUtils

private enum class FilterTab(val title: String) {
    ALL("Tümü"),
    ACTIVE("Aktif & Kuyruk"),
    COMPLETED("Tamamlanan"),
    FAILED("Hatalı / İptal")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val addDialogState by viewModel.addDialogState.collectAsStateWithLifecycle()

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showMiniBrowser by remember { mutableStateOf(false) }
    var miniBrowserInitialUrl by remember { mutableStateOf("https://") }
    var isCloudflareForAddDialog by remember { mutableStateOf(false) }

    val tabs = FilterTab.entries
    val selectedTab = tabs[selectedTabIndex]

    val filteredDownloads = remember(downloads, selectedTab) {
        when (selectedTab) {
            FilterTab.ALL -> downloads
            FilterTab.ACTIVE -> downloads.filter {
                it.status == DownloadStatus.DOWNLOADING ||
                    it.status == DownloadStatus.QUEUED ||
                    it.status == DownloadStatus.PAUSED
            }
            FilterTab.COMPLETED -> downloads.filter { it.status == DownloadStatus.COMPLETED }
            FilterTab.FAILED -> downloads.filter {
                it.status == DownloadStatus.FAILED || it.status == DownloadStatus.CANCELED
            }
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = "Zireael — İndirme Yöneticisi",
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Varsayılan: ${settings.defaultChunkCount} parça • Eşzamanlı: ${settings.maxConcurrentDownloads} dosya",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { showMiniBrowser = true }) {
                            Icon(
                                imageVector = Icons.Default.Language,
                                contentDescription = "Mini Tarayıcı (Video Yakalayıcı)"
                            )
                        }
                        IconButton(onClick = { showSettingsDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Ayarlar"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )

                ScrollableTabRow(
                    selectedTabIndex = selectedTabIndex,
                    edgePadding = 16.dp
                ) {
                    tabs.forEachIndexed { index, tab ->
                        Tab(
                            selected = selectedTabIndex == index,
                            onClick = { selectedTabIndex = index },
                            text = { Text(tab.title) }
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ExtendedFloatingActionButton(
                    onClick = { showMiniBrowser = true },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    icon = { Icon(Icons.Default.Language, contentDescription = null) },
                    text = { Text("Tarayıcı ile Yakala") }
                )
                ExtendedFloatingActionButton(
                    onClick = {
                        viewModel.resetAddDialogState()
                        showAddDialog = true
                    },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("Link Ekle") }
                )
            }
        }
    ) { innerPadding ->
        if (filteredDownloads.isEmpty()) {
            EmptyStateView(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(filteredDownloads, key = { it.id }) { item ->
                    DownloadItemCard(
                        item = item,
                        onPause = { viewModel.pauseDownload(item.id) },
                        onResume = { viewModel.resumeDownload(item.id) },
                        onCancel = { viewModel.cancelDownload(item.id) },
                        onDelete = { viewModel.deleteDownload(item.id) }
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddDownloadDialog(
            defaultChunkCount = settings.defaultChunkCount,
            uiState = addDialogState,
            onAnalyzeClick = { url -> viewModel.analyzeUrl(url) },
            onOpenCloudflareBrowser = { currentUrl ->
                miniBrowserInitialUrl = currentUrl.ifBlank { "https://" }
                isCloudflareForAddDialog = true
                showMiniBrowser = true
            },
            onSelectFormat = { formatId -> viewModel.selectFormat(formatId) },
            onStartDownload = { url, customFileName, chunkCount ->
                viewModel.startDownload(
                    url = url,
                    customFileName = customFileName,
                    chunkCount = chunkCount,
                    onSuccess = { showAddDialog = false }
                )
            },
            onDismiss = {
                viewModel.resetAddDialogState()
                showAddDialog = false
            }
        )
    }

    if (showSettingsDialog) {
        SettingsDialog(
            currentSettings = settings,
            onSave = { chunks, maxConcurrent ->
                viewModel.saveSettings(chunks, maxConcurrent)
            },
            onDismiss = { showSettingsDialog = false }
        )
    }

    if (showMiniBrowser) {
        MiniBrowserDialog(
            initialUrl = miniBrowserInitialUrl,
            onDownloadDetectedTask = { videoTask ->
                viewModel.startSniffedVideoDownload(videoTask)
                showAddDialog = false
            },
            onCloudflareSessionCaptured = if (isCloudflareForAddDialog) {
                { targetUrl, ua, cookie, referer ->
                    viewModel.onCloudflareSessionCaptured(targetUrl, ua, cookie, referer)
                }
            } else {
                null
            },
            onDismiss = {
                showMiniBrowser = false
                isCloudflareForAddDialog = false
                miniBrowserInitialUrl = "https://"
            }
        )
    }
}

@Composable
private fun EmptyStateView(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Download,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
            )
            Text(
                text = "Henüz indirme yok",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "'Link Ekle' ile doğrudan dosya/video indirebilir veya 'Tarayıcı ile Yakala' butonuna dokunarak web sayfalarındaki HLS (.m3u8) ve medya akışlarını yakalayabilirsiniz.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DownloadItemCard(
    item: DownloadItem,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
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
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.fileName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = item.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                val formatBadge = MediaFormatCatalog.getShortBadgeLabel(item.selectedFormatId)
                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            text = if (formatBadge != null) {
                                "$formatBadge • ${statusLabel(item.status)}"
                            } else {
                                statusLabel(item.status)
                            },
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                )
            }

            if (item.status == DownloadStatus.DOWNLOADING ||
                item.status == DownloadStatus.PAUSED ||
                item.status == DownloadStatus.QUEUED
            ) {
                if (item.totalBytes > 0L) {
                    LinearProgressIndicator(
                        progress = { (item.progressPercent / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                    )
                } else if (item.status == DownloadStatus.DOWNLOADING) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val sizeInfo = if (item.totalBytes > 0L) {
                    "${FormatUtils.formatBytes(item.downloadedBytes)} / ${FormatUtils.formatBytes(item.totalBytes)} (%${item.progressPercent})"
                } else {
                    FormatUtils.formatBytes(item.downloadedBytes)
                }

                val speedAndChunks = if (item.status == DownloadStatus.DOWNLOADING) {
                    "${FormatUtils.formatSpeed(item.speedBytesPerSec)} • ${item.chunkCount} parça"
                } else {
                    "${item.chunkCount} parça"
                }

                Text(
                    text = "$sizeInfo • $speedAndChunks",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    when (item.status) {
                        DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED -> {
                            FilledTonalIconButton(
                                onClick = onPause,
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Pause,
                                    contentDescription = "Duraklat",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            FilledTonalIconButton(
                                onClick = onCancel,
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "İptal Et",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        DownloadStatus.PAUSED -> {
                            FilledTonalIconButton(
                                onClick = onResume,
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Devam Et",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            FilledTonalIconButton(
                                onClick = onCancel,
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "İptal Et",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        DownloadStatus.FAILED, DownloadStatus.CANCELED -> {
                            FilledTonalIconButton(
                                onClick = onResume,
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Yeniden Dene",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            FilledTonalIconButton(
                                onClick = onDelete,
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Sil",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        DownloadStatus.COMPLETED -> {
                            FilledTonalIconButton(
                                onClick = onDelete,
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Listeden Kaldır",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }

            if (!item.errorMessage.isNullOrBlank()) {
                Text(
                    text = item.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

private fun statusLabel(status: DownloadStatus): String = when (status) {
    DownloadStatus.QUEUED -> "Sırada"
    DownloadStatus.DOWNLOADING -> "İndiriliyor"
    DownloadStatus.PAUSED -> "Duraklatıldı"
    DownloadStatus.COMPLETED -> "Tamamlandı"
    DownloadStatus.FAILED -> "Hata"
    DownloadStatus.CANCELED -> "İptal Edildi"
}

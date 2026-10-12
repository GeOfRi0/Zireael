package com.example.ui.screens

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import com.example.data.model.ChromePreset
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.example.ui.components.DownloadNotificationCard
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.DownloadEntity
import com.example.data.model.DownloadStatus
import com.example.data.model.FileCategory
import com.example.ui.theme.AccentGreen
import com.example.ui.theme.AccentOrange
import com.example.ui.theme.AccentRed
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkCardBorder
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.viewmodel.DownloadViewModel
import com.example.ui.viewmodel.SampleUrl

@Composable
fun ActiveDownloadsScreen(
    urlInput: String,
    onUrlInputChange: (String) -> Unit,
    selectedThreads: Int,
    onThreadsChange: (Int) -> Unit,
    isSniffing: Boolean,
    onStartDownload: () -> Unit,
    onStartSniffing: () -> Unit,
    sampleUrls: List<SampleUrl>,
    runningDownloads: List<DownloadEntity>,
    queuedDownloads: List<DownloadEntity>,
    pausedDownloads: List<DownloadEntity>,
    maxConcurrentDownloads: Int,
    isQueuePaused: Boolean,
    onSetMaxConcurrent: (Int) -> Unit,
    onPauseQueue: () -> Unit,
    onResumeQueue: () -> Unit,
    onStartAllQueue: () -> Unit,
    onPrioritizeQueueItem: (String) -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
    hasStoragePermission: Boolean = true,
    isIgnoringBattery: Boolean = true,
    onOpenPermissions: () -> Unit = {},
    selectedUserAgentPreset: ChromePreset = ChromePreset.CHROME_WINDOWS,
    onSelectUserAgentPreset: (ChromePreset) -> Unit = {},
    customUserAgent: String = "",
    onCustomUserAgentChange: (String) -> Unit = {},
    refererInput: String = "",
    onRefererInputChange: (String) -> Unit = {},
    cookieInput: String = "",
    onCookieInputChange: (String) -> Unit = {},
    customHeadersInput: String = "",
    onCustomHeadersInputChange: (String) -> Unit = {},
    isHeadersExpanded: Boolean = false,
    onToggleHeadersExpanded: () -> Unit = {},
    onResetHeaders: () -> Unit = {},
    capturedUrlBanner: String? = null,
    onDismissBanner: () -> Unit = {},
    onOpenQualitySelector: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var useNotificationCardStyle by remember { mutableStateOf(true) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 0. Permission & Battery Notice Banner (If not yet granted)
        if (!hasStoragePermission || !isIgnoringBattery) {
            item {
                Surface(
                    onClick = onOpenPermissions,
                    shape = RoundedCornerShape(14.dp),
                    color = AccentOrange.copy(alpha = 0.12f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AccentOrange.copy(alpha = 0.45f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("permissions_active_screen_banner")
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(AccentOrange.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = null,
                                tint = AccentOrange,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (!hasStoragePermission) "⚠️ Dosya Erişim İzni Gerekli" else "🔋 Pil Optimizasyonu İstisnası Gerekli",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = AccentOrange
                            )
                            Text(
                                text = "Arka planda ve ekran kapalıyken indirmelerin durmaması için dokunun.",
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = AccentOrange,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp))
                        ) {
                            Text(
                                text = "Ayarla",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF0A0F1D),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
        }

        // 0.2 Captured External URL Banner (Intent-Filter / System Link Click)
        if (!capturedUrlBanner.isNullOrBlank()) {
            item {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = CyberCyan.copy(alpha = 0.12f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, CyberCyan.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("captured_url_banner")
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(CyberCyan.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = null,
                                tint = CyberCyan,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "⚡ İndirme Bağlantısı Yakalandı!",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = CyberCyan
                            )
                            Text(
                                text = capturedUrlBanner,
                                fontSize = 11.sp,
                                color = TextPrimary
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        IconButton(onClick = onDismissBanner) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Kapat",
                                tint = TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }

        // 1. URL Entry & IDM Acceleration Card
        item {
            UrlEntryCard(
                urlInput = urlInput,
                onUrlInputChange = onUrlInputChange,
                selectedThreads = selectedThreads,
                onThreadsChange = onThreadsChange,
                isSniffing = isSniffing,
                onStartDownload = onStartDownload,
                onStartSniffing = onStartSniffing,
                onOpenQualitySelector = onOpenQualitySelector,
                onPasteClipboard = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    if (clipboard?.hasPrimaryClip() == true &&
                        clipboard.primaryClipDescription?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) == true
                    ) {
                        val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                        if (text.isNotBlank()) onUrlInputChange(text.trim())
                    }
                },
                selectedUserAgentPreset = selectedUserAgentPreset,
                onSelectUserAgentPreset = onSelectUserAgentPreset,
                customUserAgent = customUserAgent,
                onCustomUserAgentChange = onCustomUserAgentChange,
                refererInput = refererInput,
                onRefererInputChange = onRefererInputChange,
                cookieInput = cookieInput,
                onCookieInputChange = onCookieInputChange,
                customHeadersInput = customHeadersInput,
                onCustomHeadersInputChange = onCustomHeadersInputChange,
                isHeadersExpanded = isHeadersExpanded,
                onToggleHeadersExpanded = onToggleHeadersExpanded,
                onResetHeaders = onResetHeaders
            )
        }

        // 2. Quick Test Links Carousel
        item {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "⚡ Hızlı Test Bağlantıları",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = "1-Tık İndirme",
                        fontSize = 12.sp,
                        color = CyberCyan
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(sampleUrls) { sample ->
                        SampleUrlCard(
                            sample = sample,
                            onClick = {
                                onUrlInputChange(sample.url)
                                onStartDownload()
                            }
                        )
                    }
                }
            }
        }

        // 3. Queue Management & Concurrency Control Panel
        item {
            QueueControlPanel(
                maxConcurrentDownloads = maxConcurrentDownloads,
                isQueuePaused = isQueuePaused,
                runningCount = runningDownloads.size,
                queuedCount = queuedDownloads.size,
                pausedCount = pausedDownloads.size,
                onSetMaxConcurrent = onSetMaxConcurrent,
                onPauseQueue = onPauseQueue,
                onResumeQueue = onResumeQueue,
                onStartAllQueue = onStartAllQueue
            )
        }

        // 4. Currently Running / Active Downloads
        if (runningDownloads.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Aktif İndirilenler (${runningDownloads.size})",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "Yüzde tabanlı ilerleme & anlık hız",
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }

                    Surface(
                        onClick = { useNotificationCardStyle = !useNotificationCardStyle },
                        shape = RoundedCornerShape(8.dp),
                        color = if (useNotificationCardStyle) CyberCyan.copy(alpha = 0.15f) else DarkSurfaceVariant,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (useNotificationCardStyle) CyberCyan else DarkCardBorder
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (useNotificationCardStyle) "🔔 Bildirim Kartı" else "📄 Standart Kart",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (useNotificationCardStyle) CyberCyan else TextSecondary
                            )
                        }
                    }
                }
            }

            items(runningDownloads, key = { it.id }) { item ->
                if (useNotificationCardStyle) {
                    DownloadNotificationCard(
                        item = item,
                        onPause = onPause,
                        onResume = onResume,
                        onCancel = onCancel,
                        onRetry = onRetry
                    )
                } else {
                    ActiveDownloadItemCard(
                        item = item,
                        onPause = { onPause(item.id) },
                        onResume = { onResume(item.id) },
                        onCancel = { onCancel(item.id) },
                        onRetry = { onRetry(item.id) }
                    )
                }
            }
        }

        // 5. Download Queue Section (Sırada Bekleyenler)
        if (queuedDownloads.isNotEmpty()) {
            item {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(AccentOrange)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "İndirme Kuyruğu (${queuedDownloads.size} Dosya Sırada)",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        }

                        Text(
                            text = "Otomatik Sıralı İndirme",
                            fontSize = 11.sp,
                            color = AccentOrange
                        )
                    }
                    Text(
                        text = "Aktif slot boşaldıkça sıradaki indirme otomatik başlar.",
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                }
            }

            itemsIndexed(queuedDownloads, key = { _, item -> item.id }) { index, item ->
                QueuedDownloadItemCard(
                    item = item,
                    queuePosition = index + 1,
                    onPrioritize = { onPrioritizeQueueItem(item.id) },
                    onPause = { onPause(item.id) },
                    onCancel = { onCancel(item.id) }
                )
            }
        }

        // 6. Paused Downloads Section (Duraklatılanlar)
        if (pausedDownloads.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Duraklatılanlar (${pausedDownloads.size})",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = "Kaldığı Yerden Devam Eder",
                        fontSize = 11.sp,
                        color = TextMuted
                    )
                }
            }

            items(pausedDownloads, key = { it.id }) { item ->
                PausedDownloadItemCard(
                    item = item,
                    onResume = { onResume(item.id) },
                    onCancel = { onCancel(item.id) }
                )
            }
        }

        // 7. Empty state when no downloads at all
        if (runningDownloads.isEmpty() && queuedDownloads.isEmpty() && pausedDownloads.isEmpty()) {
            item {
                EmptyActiveDownloadsCard()
            }
        }
    }
}

@Composable
fun QueueControlPanel(
    maxConcurrentDownloads: Int,
    isQueuePaused: Boolean,
    runningCount: Int,
    queuedCount: Int,
    pausedCount: Int,
    onSetMaxConcurrent: (Int) -> Unit,
    onPauseQueue: () -> Unit,
    onResumeQueue: () -> Unit,
    onStartAllQueue: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("queue_control_panel")
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(CyberCyan.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = null,
                            tint = CyberCyan,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Kuyruk & Eşzamanlılık",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "Otomatik kuyruk yönetimi",
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }
                }

                // Concurrency Limit Selector Chips (1, 2, 3, 4)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Limit:",
                        fontSize = 11.sp,
                        color = TextMuted,
                        fontWeight = FontWeight.SemiBold
                    )
                    listOf(1, 2, 3, 4).forEach { limit ->
                        val isSelected = limit == maxConcurrentDownloads
                        Surface(
                            onClick = { onSetMaxConcurrent(limit) },
                            shape = RoundedCornerShape(6.dp),
                            color = if (isSelected) CyberCyan else DarkSurfaceVariant,
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isSelected) CyberCyan else DarkCardBorder
                            ),
                            modifier = Modifier.testTag("max_concurrent_button_$limit")
                        ) {
                            Text(
                                text = "$limit",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) Color(0xFF0A0F1D) else TextSecondary,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Status counters row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(DarkBackground)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Running count
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (runningCount > 0) AccentGreen else TextMuted)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "$runningCount Aktif",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (runningCount > 0) AccentGreen else TextSecondary
                    )
                }

                // Queued count
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (queuedCount > 0) AccentOrange else TextMuted)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "$queuedCount Kuyrukta",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (queuedCount > 0) AccentOrange else TextSecondary
                    )
                }

                // Paused count
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (pausedCount > 0) ElectricBlue else TextMuted)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "$pausedCount Duraklatıldı",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Action buttons row: Resume/Pause queue, Start all
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Pause / Resume queue
                OutlinedButton(
                    onClick = if (isQueuePaused) onResumeQueue else onPauseQueue,
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (isQueuePaused) AccentGreen else AccentOrange
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .testTag("toggle_queue_pause_button")
                ) {
                    Icon(
                        imageVector = if (isQueuePaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = null,
                        tint = if (isQueuePaused) AccentGreen else AccentOrange,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isQueuePaused) "Kuyruğu Sürdür" else "Kuyruğu Duraklat",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isQueuePaused) AccentGreen else AccentOrange
                    )
                }

                // Start all
                Button(
                    onClick = onStartAllQueue,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CyberCyan,
                        contentColor = Color(0xFF0A0F1D)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .testTag("start_all_queue_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Bolt,
                        contentDescription = null,
                        tint = Color(0xFF0A0F1D),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Tümünü Başlat",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun QueuedDownloadItemCard(
    item: DownloadEntity,
    queuePosition: Int,
    onPrioritize: () -> Unit,
    onPause: () -> Unit,
    onCancel: () -> Unit
) {
    val category = FileCategory.detect(item.fileName, item.mimeType)

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("queued_download_card_${item.id}")
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Queue rank badge
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = AccentOrange.copy(alpha = 0.15f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AccentOrange.copy(alpha = 0.5f))
                ) {
                    Text(
                        text = "#$queuePosition",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = AccentOrange,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Category icon
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(DarkSurfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when (category) {
                            FileCategory.APKS -> Icons.Default.Bolt
                            FileCategory.MEDIA -> Icons.Default.Movie
                            FileCategory.ARCHIVES -> Icons.Default.Description
                            FileCategory.DOCUMENTS -> Icons.Default.Description
                            else -> Icons.Default.Description
                        },
                        contentDescription = null,
                        tint = CyberCyan,
                        modifier = Modifier.size(16.dp)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                // Title & info
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.fileName,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Sırada bekliyor • ${item.threadCount} Kanal",
                        fontSize = 11.sp,
                        color = AccentOrange
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Action buttons: Cancel, Pause, Prioritize
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .size(30.dp)
                        .testTag("cancel_queued_button_${item.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = "Kuyruktan Çıkar",
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                OutlinedButton(
                    onClick = onPause,
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder),
                    modifier = Modifier
                        .height(30.dp)
                        .testTag("pause_queued_button_${item.id}")
                ) {
                    Text(
                        text = "Duraklat",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextSecondary
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                Button(
                    onClick = onPrioritize,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CyberCyan.copy(alpha = 0.2f),
                        contentColor = CyberCyan
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, CyberCyan),
                    modifier = Modifier
                        .height(30.dp)
                        .testTag("prioritize_queued_button_${item.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Bolt,
                        contentDescription = null,
                        tint = CyberCyan,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Hemen İndir",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = CyberCyan
                    )
                }
            }
        }
    }
}

@Composable
fun PausedDownloadItemCard(
    item: DownloadEntity,
    onResume: () -> Unit,
    onCancel: () -> Unit
) {
    val category = FileCategory.detect(item.fileName, item.mimeType)
    val currentDownloadedBytes by rememberUpdatedState(item.downloadedBytes)
    val currentTotalBytes by rememberUpdatedState(item.totalBytes)

    val progressFraction by remember {
        derivedStateOf {
            if (currentTotalBytes > 0) {
                (currentDownloadedBytes.toFloat() / currentTotalBytes).coerceIn(0f, 1f)
            } else 0f
        }
    }
    val percentInt by remember {
        derivedStateOf {
            (progressFraction * 100).toInt()
        }
    }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("paused_download_card_${item.id}")
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(DarkSurfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when (category) {
                            FileCategory.APKS -> Icons.Default.Bolt
                            FileCategory.MEDIA -> Icons.Default.Movie
                            FileCategory.ARCHIVES -> Icons.Default.Description
                            FileCategory.DOCUMENTS -> Icons.Default.Description
                            else -> Icons.Default.Description
                        },
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.fileName,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Duraklatıldı • %$percentInt tamamlandı",
                        fontSize = 11.sp,
                        color = TextMuted
                    )
                }

                IconButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .size(30.dp)
                        .testTag("delete_paused_button_${item.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = "Sil",
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                Button(
                    onClick = onResume,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CyberCyan,
                        contentColor = Color(0xFF0A0F1D)
                    ),
                    modifier = Modifier
                        .height(30.dp)
                        .testTag("resume_paused_button_${item.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color(0xFF0A0F1D),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Devam Et",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun UrlEntryCard(
    urlInput: String,
    onUrlInputChange: (String) -> Unit,
    selectedThreads: Int,
    onThreadsChange: (Int) -> Unit,
    isSniffing: Boolean,
    onStartDownload: () -> Unit,
    onStartSniffing: () -> Unit,
    onPasteClipboard: () -> Unit,
    selectedUserAgentPreset: ChromePreset = ChromePreset.CHROME_WINDOWS,
    onSelectUserAgentPreset: (ChromePreset) -> Unit = {},
    customUserAgent: String = "",
    onCustomUserAgentChange: (String) -> Unit = {},
    refererInput: String = "",
    onRefererInputChange: (String) -> Unit = {},
    cookieInput: String = "",
    onCookieInputChange: (String) -> Unit = {},
    customHeadersInput: String = "",
    onCustomHeadersInputChange: (String) -> Unit = {},
    isHeadersExpanded: Boolean = false,
    onToggleHeadersExpanded: () -> Unit = {},
    onResetHeaders: () -> Unit = {},
    onOpenQualitySelector: (() -> Unit)? = null
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Title
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(CyberCyan.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Bolt,
                        contentDescription = null,
                        tint = CyberCyan,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "Yeni İndirme Başlat",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = "Video, fotoğraf, dosya veya web sayfası linki girin",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // URL Input Field
            OutlinedTextField(
                value = urlInput,
                onValueChange = onUrlInputChange,
                placeholder = {
                    Text("https://example.com/video.mp4 veya web linki", fontSize = 13.sp, color = TextMuted)
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = null,
                        tint = CyberCyan,
                        modifier = Modifier.size(20.dp)
                    )
                },
                trailingIcon = {
                    if (urlInput.isNotBlank()) {
                        IconButton(onClick = { onUrlInputChange("") }) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Temizle",
                                tint = TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CyberCyan,
                    unfocusedBorderColor = DarkCardBorder,
                    focusedContainerColor = DarkBackground,
                    unfocusedContainerColor = DarkBackground,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("url_input_field")
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Quick helpers: Paste button & thread selection
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButtonWithIcon(
                    icon = Icons.Default.ContentPaste,
                    text = "Panodan Yapıştır",
                    onClick = onPasteClipboard,
                    testTag = "paste_clipboard_button"
                )

                // Thread selector pills
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Parça:",
                        fontSize = 12.sp,
                        color = TextSecondary,
                        modifier = Modifier.padding(end = 2.dp)
                    )
                    listOf(1, 2, 4, 8).forEach { threads ->
                        val isSelected = selectedThreads == threads
                        Surface(
                            onClick = { onThreadsChange(threads) },
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) CyberCyan else DarkSurfaceVariant,
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isSelected) CyberCyan else DarkCardBorder
                            ),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Box(
                                modifier = Modifier.padding(horizontal = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "${threads}x",
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) Color(0xFF0A0F1D) else TextPrimary
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            val hasCustomHeaders = selectedUserAgentPreset != ChromePreset.CHROME_WINDOWS ||
                customUserAgent.isNotBlank() ||
                refererInput.isNotBlank() ||
                cookieInput.isNotBlank() ||
                customHeadersInput.isNotBlank()

            // Expandable Chrome Headers Accordion Toggle
            Surface(
                onClick = onToggleHeadersExpanded,
                shape = RoundedCornerShape(10.dp),
                color = if (isHeadersExpanded) DarkSurfaceVariant else Color(0xFF131D33),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (hasCustomHeaders) CyberCyan else DarkCardBorder
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("toggle_chrome_headers_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Language,
                            contentDescription = null,
                            tint = if (hasCustomHeaders) CyberCyan else TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Ağ & Chrome Başlıkları",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (hasCustomHeaders) CyberCyan else TextPrimary
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = if (hasCustomHeaders) AccentGreen.copy(alpha = 0.2f) else CyberCyan.copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = if (hasCustomHeaders) "Özelleştirildi" else selectedUserAgentPreset.title.substringBefore(" ("),
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = if (hasCustomHeaders) AccentGreen else CyberCyan,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Text(
                                text = "Gerçek Chrome taklidi: User-Agent, Referer, Cookie & Client Hints",
                                fontSize = 10.sp,
                                color = TextMuted
                            )
                        }
                    }
                    Icon(
                        imageVector = if (isHeadersExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            if (isHeadersExpanded) {
                Spacer(modifier = Modifier.height(10.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(DarkBackground.copy(alpha = 0.7f))
                        .border(1.dp, DarkCardBorder, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Preset selector
                    Text(
                        text = "Tarayıcı Kimliği (User-Agent Preseti):",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextSecondary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ChromePreset.values().forEach { preset ->
                            val isSelected = selectedUserAgentPreset == preset
                            Surface(
                                onClick = { onSelectUserAgentPreset(preset) },
                                shape = RoundedCornerShape(6.dp),
                                color = if (isSelected) CyberCyan.copy(alpha = 0.2f) else DarkSurfaceVariant,
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (isSelected) CyberCyan else Color.Transparent
                                ),
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier.padding(vertical = 6.dp, horizontal = 2.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = when (preset) {
                                            ChromePreset.CHROME_WINDOWS -> "Windows"
                                            ChromePreset.CHROME_ANDROID -> "Android"
                                            ChromePreset.CHROME_MAC -> "Mac"
                                            ChromePreset.CUSTOM -> "Özel"
                                        },
                                        fontSize = 10.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) CyberCyan else TextSecondary
                                    )
                                }
                            }
                        }
                    }

                    if (selectedUserAgentPreset == ChromePreset.CUSTOM) {
                        OutlinedTextField(
                            value = customUserAgent,
                            onValueChange = onCustomUserAgentChange,
                            placeholder = { Text("Özel User-Agent dizesi girin", fontSize = 11.sp, color = TextMuted) },
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CyberCyan,
                                unfocusedBorderColor = DarkCardBorder,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("custom_user_agent_input")
                        )
                    }

                    // Referer Input
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Referer (Yönlendiren Sayfa URL):",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextSecondary
                            )
                            if (urlInput.isNotBlank()) {
                                Text(
                                    text = "URL'yi referer yap",
                                    fontSize = 10.sp,
                                    color = CyberCyan,
                                    modifier = Modifier.clickable { onRefererInputChange(urlInput.trim()) }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(
                            value = refererInput,
                            onValueChange = onRefererInputChange,
                            placeholder = { Text("https://site.com/sayfa (Boşsa hedef sunucu kullanılır)", fontSize = 11.sp, color = TextMuted) },
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CyberCyan,
                                unfocusedBorderColor = DarkCardBorder,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("referer_input")
                        )
                    }

                    // Cookie Input
                    Column {
                        Text(
                            text = "Cookie (Oturum / Çerezler):",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(
                            value = cookieInput,
                            onValueChange = onCookieInputChange,
                            placeholder = { Text("session_id=...; cf_clearance=...; auth_token=...", fontSize = 11.sp, color = TextMuted) },
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CyberCyan,
                                unfocusedBorderColor = DarkCardBorder,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("cookie_input")
                        )
                    }

                    // Extra Headers (Key: Value)
                    Column {
                        Text(
                            text = "Ek HTTP Başlıkları (Key: Value):",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(
                            value = customHeadersInput,
                            onValueChange = onCustomHeadersInputChange,
                            placeholder = { Text("Authorization: Bearer token\nX-Requested-With: XMLHttpRequest", fontSize = 11.sp, color = TextMuted) },
                            minLines = 2,
                            maxLines = 4,
                            shape = RoundedCornerShape(8.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = CyberCyan,
                                unfocusedBorderColor = DarkCardBorder,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("extra_headers_input")
                        )
                    }

                    // Reset button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onResetHeaders) {
                            Text("Varsayılana Sıfırla", fontSize = 11.sp, color = TextMuted)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Main Action Buttons (Download, Quality Selector & Sniff)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Sniff & Scan button
                    OutlinedButton(
                        onClick = onStartSniffing,
                        enabled = !isSniffing && urlInput.isNotBlank(),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("sniff_button"),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, ElectricBlue)
                    ) {
                        if (isSniffing) {
                            CircularProgressIndicator(
                                color = ElectricBlue,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Sayfayı Tara",
                                tint = ElectricBlue,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Sayfayı Tara",
                                color = ElectricBlue,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    // Quality Selection Button (HD 1080p, 720p, 360p, Ses)
                    OutlinedButton(
                        onClick = { onOpenQualitySelector?.invoke() },
                        enabled = urlInput.isNotBlank(),
                        modifier = Modifier
                            .weight(1.1f)
                            .height(48.dp)
                            .testTag("quality_selector_button"),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, CyberCyan)
                    ) {
                        Icon(
                            imageVector = Icons.Default.HighQuality,
                            contentDescription = "Kalite Seç",
                            tint = CyberCyan,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Kalite Seç (HD/Ses)",
                            color = CyberCyan,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Direct High Speed Turbo Download button
                Button(
                    onClick = onStartDownload,
                    enabled = urlInput.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("direct_download_button"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CyberCyan,
                        contentColor = Color(0xFF0A0F1D),
                        disabledContainerColor = DarkSurfaceVariant,
                        disabledContentColor = TextMuted
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = "Hemen İndir",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "⚡ Hemen İndir (Çok Kanallı Turbo)",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun TextButtonWithIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    onClick: () -> Unit,
    testTag: String
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = DarkSurfaceVariant,
        modifier = Modifier.testTag(testTag)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = CyberCyan,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = text,
                fontSize = 11.sp,
                color = TextPrimary,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun SampleUrlCard(
    sample: SampleUrl,
    onClick: () -> Unit
) {
    val icon = when (sample.category) {
        FileCategory.APKS -> Icons.Default.Bolt
        FileCategory.MEDIA -> Icons.Default.Movie
        FileCategory.ARCHIVES -> Icons.Default.Folder
        FileCategory.DOCUMENTS -> Icons.Default.Description
        else -> Icons.Default.Description
    }
    val iconTint = when (sample.category) {
        FileCategory.APKS -> AccentGreen
        FileCategory.MEDIA -> CyberCyan
        FileCategory.ARCHIVES -> Color(0xFFA855F7)
        FileCategory.DOCUMENTS -> ElectricBlue
        else -> Color(0xFFA855F7)
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = DarkSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder),
        modifier = Modifier.width(180.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(iconTint.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = sample.category.label,
                    fontSize = 11.sp,
                    color = iconTint,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = sample.title,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = sample.subtitle,
                fontSize = 10.sp,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun ActiveDownloadItemCard(
    item: DownloadEntity,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit
) {
    val category = FileCategory.detect(item.fileName, item.mimeType)
    val currentDownloadedBytes by rememberUpdatedState(item.downloadedBytes)
    val currentTotalBytes by rememberUpdatedState(item.totalBytes)
    val currentSpeed by rememberUpdatedState(item.speedBytesPerSec)

    // derivedStateOf prevents unnecessary recompositions on progress bar and percentage updates
    val progressFraction by remember {
        derivedStateOf {
            if (currentTotalBytes > 0) {
                (currentDownloadedBytes.toFloat() / currentTotalBytes).coerceIn(0f, 1f)
            } else 0f
        }
    }
    val progressPercent by remember {
        derivedStateOf {
            (progressFraction * 100).toInt()
        }
    }
    val formattedProgressBytes by remember {
        derivedStateOf {
            "${DownloadViewModel.formatBytes(currentDownloadedBytes)} / ${DownloadViewModel.formatBytes(currentTotalBytes)}"
        }
    }
    val formattedEta by remember {
        derivedStateOf {
            DownloadViewModel.calculateEta(currentDownloadedBytes, currentTotalBytes, currentSpeed)
        }
    }

    val animatedProgress by animateFloatAsState(
        targetValue = progressFraction,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "progress"
    )

    val isPaused = item.status == DownloadStatus.PAUSED.name
    val isFailed = item.status == DownloadStatus.FAILED.name
    val isConnecting = item.status == DownloadStatus.CONNECTING.name

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isFailed) AccentRed.copy(alpha = 0.5f) else DarkCardBorder
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Top Row: File icon, Name, Category badge, Action controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Category icon
                val icon = when (category) {
                    FileCategory.APKS -> Icons.Default.Bolt
                    FileCategory.MEDIA -> Icons.Default.Movie
                    FileCategory.ARCHIVES -> Icons.Default.Description
                    FileCategory.DOCUMENTS -> Icons.Default.Description
                    else -> Icons.Default.Description
                }
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(DarkSurfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isFailed) AccentRed else CyberCyan,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Details
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.fileName,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = when {
                                isFailed -> AccentRed.copy(alpha = 0.2f)
                                isPaused -> AccentOrange.copy(alpha = 0.2f)
                                isConnecting -> ElectricBlue.copy(alpha = 0.2f)
                                else -> AccentGreen.copy(alpha = 0.2f)
                            }
                        ) {
                            Text(
                                text = when {
                                    isFailed -> "Hata"
                                    isPaused -> "Duraklatıldı"
                                    isConnecting -> "Bağlanıyor..."
                                    else -> "İndiriliyor (${item.threadCount} Parça)"
                                },
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = when {
                                    isFailed -> AccentRed
                                    isPaused -> AccentOrange
                                    isConnecting -> ElectricBlue
                                    else -> AccentGreen
                                },
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }

                        if (!isFailed && !isPaused && item.speedBytesPerSec > 0) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "⚡ ${DownloadViewModel.formatSpeed(item.speedBytesPerSec)}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = CyberCyan
                            )
                        }
                    }
                }

                // Controls (Pause, Resume, Cancel, Retry)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isFailed) {
                        IconButton(
                            onClick = onRetry,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Tekrar Dene",
                                tint = CyberCyan,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    } else if (isPaused) {
                        IconButton(
                            onClick = onResume,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Devam Et",
                                tint = CyberCyan,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    } else {
                        IconButton(
                            onClick = onPause,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Pause,
                                contentDescription = "Duraklat",
                                tint = AccentOrange,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    IconButton(
                        onClick = onCancel,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = "İptal Et",
                            tint = TextMuted,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Progress Bar
            if (item.totalBytes > 0) {
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = if (isPaused) AccentOrange else CyberCyan,
                    trackColor = DarkSurfaceVariant
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = CyberCyan,
                    trackColor = DarkSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Bottom info: Downloaded/Total bytes, ETA, %
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formattedProgressBytes,
                    fontSize = 11.sp,
                    color = TextSecondary
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!isPaused && !isFailed && item.speedBytesPerSec > 0) {
                        Text(
                            text = "Kalan: $formattedEta",
                            fontSize = 11.sp,
                            color = TextMuted
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = "$progressPercent%",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isPaused) AccentOrange else CyberCyan
                    )
                }
            }

            if (item.errorMessage != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Hata: ${item.errorMessage}",
                    fontSize = 11.sp,
                    color = AccentRed
                )
            }
        }
    }
}

@Composable
fun EmptyActiveDownloadsCard() {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = DarkSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(DarkSurfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Speed,
                    contentDescription = null,
                    tint = CyberCyan,
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "Aktif İndirme Bulunmuyor",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Yukarıya bir indirme linki yapıştırın veya hızlı test bağlantılarına dokunun.",
                fontSize = 12.sp,
                color = TextSecondary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

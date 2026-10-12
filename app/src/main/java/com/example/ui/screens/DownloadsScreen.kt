package com.example.ui.screens

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.engine.ArchiveHelper
import com.example.data.local.DownloadEntity
import com.example.data.model.ChromePreset
import com.example.data.model.DownloadStatus
import com.example.data.model.FileCategory
import com.example.ui.theme.AccentGreen
import com.example.ui.theme.AccentOrange
import com.example.ui.theme.AccentRed
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkCardBorder
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.PastelTeal
import com.example.ui.theme.SoftCyan
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.viewmodel.DownloadViewModel
import java.io.File

enum class DownloadsFilterTab(val label: String) {
    ALL("Tümü"),
    ACTIVE("Aktif"),
    COMPLETED("Tamamlanan")
}

/**
 * DownloadsScreen:
 * Spacious, clutter-free Material Design 3 home screen for Zireael.
 * Unifies URL download start, active progress cards, and downloaded library.
 */
@Composable
fun DownloadsScreen(
    urlInput: String,
    onUrlInputChange: (String) -> Unit,
    selectedThreads: Int,
    onThreadsChange: (Int) -> Unit,
    isSniffing: Boolean,
    onStartDownload: () -> Unit,
    onStartSniffing: () -> Unit,
    onOpenQualitySelector: (() -> Unit)? = null,
    runningDownloads: List<DownloadEntity>,
    queuedDownloads: List<DownloadEntity>,
    pausedDownloads: List<DownloadEntity>,
    completedDownloads: List<DownloadEntity>,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
    onOpenFile: (DownloadEntity) -> Unit,
    onShareFile: (DownloadEntity) -> Unit,
    onDeleteFile: (String) -> Unit,
    onShowPreview: (DownloadEntity) -> Unit,
    onMoveToVault: (DownloadEntity) -> Unit,
    capturedUrlBanner: String? = null,
    onDismissBanner: () -> Unit = {}
) {
    val context = LocalContext.current
    var selectedFilterTab by remember { mutableStateOf(DownloadsFilterTab.ALL) }
    var isAdvancedExpanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategoryFilter by remember { mutableStateOf(FileCategory.ALL) }

    val activeCount = runningDownloads.size + queuedDownloads.size + pausedDownloads.size
    val totalDownloadsCount = activeCount + completedDownloads.size

    val filteredCompleted = remember(completedDownloads, searchQuery, selectedCategoryFilter) {
        completedDownloads.filter { item ->
            val matchSearch = searchQuery.isBlank() ||
                    item.fileName.contains(searchQuery, ignoreCase = true)
            val matchCategory = selectedCategoryFilter == FileCategory.ALL ||
                    FileCategory.detect(item.fileName, item.mimeType) == selectedCategoryFilter
            matchSearch && matchCategory
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Captured URL Notification Banner (if any)
        if (capturedUrlBanner != null) {
            item {
                ElevatedCard(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = PastelTeal.copy(alpha = 0.12f)),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Default.Bolt, contentDescription = null, tint = PastelTeal, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(text = capturedUrlBanner, fontSize = 13.sp, color = TextPrimary, fontWeight = FontWeight.Medium)
                        }
                        IconButton(onClick = onDismissBanner, modifier = Modifier.size(28.dp)) {
                            Icon(imageVector = Icons.Default.Clear, contentDescription = "Kapat", tint = TextSecondary, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }

        // 2. Clean, Spacious URL Input Card
        item {
            ElevatedCard(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = DarkSurface),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 3.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "Yeni İndirme Başlat",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = onUrlInputChange,
                        placeholder = { Text("https://, magnet: veya dosya bağlantısı yapıştırın", fontSize = 13.sp, color = TextMuted) },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PastelTeal,
                            unfocusedBorderColor = DarkCardBorder,
                            focusedContainerColor = DarkBackground,
                            unfocusedContainerColor = DarkBackground,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 6.dp)) {
                                if (urlInput.isNotBlank()) {
                                    IconButton(onClick = { onUrlInputChange("") }, modifier = Modifier.size(32.dp)) {
                                        Icon(imageVector = Icons.Default.Clear, contentDescription = "Temizle", tint = TextSecondary, modifier = Modifier.size(16.dp))
                                    }
                                }
                                IconButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                        val clipText = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()
                                        if (!clipText.isNullOrBlank()) {
                                            onUrlInputChange(clipText.trim())
                                        }
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(imageVector = Icons.Default.ContentPaste, contentDescription = "Yapıştır", tint = PastelTeal, modifier = Modifier.size(18.dp))
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("url_input_field")
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Action row: Primary "İndir" button & "Gelişmiş" options toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = onStartDownload,
                            enabled = urlInput.isNotBlank(),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = PastelTeal,
                                contentColor = DarkBackground,
                                disabledContainerColor = DarkSurfaceVariant,
                                disabledContentColor = TextMuted
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("start_download_button")
                        ) {
                            Icon(imageVector = Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = "Hemen İndir", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }

                        Surface(
                            onClick = { isAdvancedExpanded = !isAdvancedExpanded },
                            shape = RoundedCornerShape(14.dp),
                            color = DarkSurfaceVariant,
                            border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder.copy(alpha = 0.5f)),
                            modifier = Modifier.height(48.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(imageVector = Icons.Default.Tune, contentDescription = null, tint = SoftCyan, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(text = "Gelişmiş", fontSize = 13.sp, color = TextPrimary, fontWeight = FontWeight.Medium)
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = if (isAdvancedExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = null,
                                    tint = TextSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }

                    // Collapsible Advanced settings section (keeps UI calm & uncluttered)
                    AnimatedVisibility(
                        visible = isAdvancedExpanded,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(DarkBackground)
                                .padding(14.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(text = "Paralel Bağlantı (Kanal)", fontSize = 13.sp, color = TextSecondary)
                                Text(text = "$selectedThreads Parça", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = PastelTeal)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(listOf(1, 2, 4, 8, 16, 32)) { threads ->
                                    val isSelected = selectedThreads == threads
                                    Surface(
                                        onClick = { onThreadsChange(threads) },
                                        shape = RoundedCornerShape(10.dp),
                                        color = if (isSelected) PastelTeal else DarkSurfaceVariant,
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Box(modifier = Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                                            Text(
                                                text = "${threads}x",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isSelected) DarkBackground else TextPrimary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. Segmented Filter Tabs: Tümü / Aktif / Tamamlanan
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(DarkSurface)
                    .padding(4.dp)
            ) {
                DownloadsFilterTab.values().forEach { tab ->
                    val isSelected = selectedFilterTab == tab
                    val badgeCount = when (tab) {
                        DownloadsFilterTab.ALL -> totalDownloadsCount
                        DownloadsFilterTab.ACTIVE -> activeCount
                        DownloadsFilterTab.COMPLETED -> completedDownloads.size
                    }

                    Surface(
                        onClick = { selectedFilterTab = tab },
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) PastelTeal else Color.Transparent,
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = tab.label,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) DarkBackground else TextSecondary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(if (isSelected) DarkBackground.copy(alpha = 0.2f) else DarkSurfaceVariant)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "$badgeCount",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) DarkBackground else TextSecondary
                                )
                            }
                        }
                    }
                }
            }
        }

        // 4. ACTIVE DOWNLOADS SECTION
        if (selectedFilterTab != DownloadsFilterTab.COMPLETED && activeCount > 0) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Devam Eden İndirmeler",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = "$activeCount işlem",
                        fontSize = 12.sp,
                        color = PastelTeal,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Running Downloads
            items(runningDownloads, key = { it.id }) { item ->
                DownloadsActiveCard(
                    item = item,
                    onPause = { onPause(item.id) },
                    onResume = { onResume(item.id) },
                    onCancel = { onCancel(item.id) },
                    onRetry = { onRetry(item.id) }
                )
            }

            // Paused Downloads
            items(pausedDownloads, key = { it.id }) { item ->
                DownloadsActiveCard(
                    item = item,
                    onPause = { onPause(item.id) },
                    onResume = { onResume(item.id) },
                    onCancel = { onCancel(item.id) },
                    onRetry = { onRetry(item.id) }
                )
            }

            // Queued Downloads
            items(queuedDownloads, key = { it.id }) { item ->
                DownloadsActiveCard(
                    item = item,
                    onPause = { onPause(item.id) },
                    onResume = { onResume(item.id) },
                    onCancel = { onCancel(item.id) },
                    onRetry = { onRetry(item.id) }
                )
            }
        }

        // 5. COMPLETED / LIBRARY SECTION
        if (selectedFilterTab != DownloadsFilterTab.ACTIVE) {
            item {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "İndirilen Dosyalar",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = "${completedDownloads.size} dosya",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }
            }

            // Search bar for completed items
            if (completedDownloads.isNotEmpty()) {
                item {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Dosyalarda ara...", fontSize = 13.sp, color = TextMuted) },
                        leadingIcon = { Icon(imageVector = Icons.Default.Search, contentDescription = null, tint = SoftCyan, modifier = Modifier.size(18.dp)) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = SoftCyan,
                            unfocusedBorderColor = DarkCardBorder,
                            focusedContainerColor = DarkSurface,
                            unfocusedContainerColor = DarkSurface,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            if (filteredCompleted.isEmpty()) {
                item {
                    EmptyCompletedCard()
                }
            } else {
                items(filteredCompleted, key = { it.id }) { item ->
                    DownloadsCompletedCard(
                        item = item,
                        onOpen = { onOpenFile(item) },
                        onShare = { onShareFile(item) },
                        onDelete = { onDeleteFile(item.id) },
                        onPreview = { onShowPreview(item) },
                        onMoveToVault = { onMoveToVault(item) }
                    )
                }
            }
        }
    }
}

@Composable
fun DownloadsActiveCard(
    item: DownloadEntity,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit
) {
    val total = item.totalBytes
    val downloaded = item.downloadedBytes
    val progress = if (total > 0) (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
    val percent = (progress * 100).toInt()
    val isPaused = item.status == DownloadStatus.PAUSED.name

    ElevatedCard(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = DarkSurface),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Category icon
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(DarkSurfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = null,
                        tint = if (isPaused) AccentOrange else PastelTeal,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.fileName,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (isPaused) "Durduruldu" else "${DownloadViewModel.formatSpeed(item.speedBytesPerSec)}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isPaused) AccentOrange else PastelTeal
                        )
                        Text(
                            text = " • ${DownloadViewModel.formatBytes(downloaded)} / ${DownloadViewModel.formatBytes(total)}",
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }
                }

                // Play / Pause Action
                IconButton(
                    onClick = if (isPaused) onResume else onPause,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(if (isPaused) AccentOrange.copy(alpha = 0.15f) else PastelTeal.copy(alpha = 0.15f))
                ) {
                    Icon(
                        imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = if (isPaused) "Devam Et" else "Duraklat",
                        tint = if (isPaused) AccentOrange else PastelTeal,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Cancel Action
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = "İptal",
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Smooth, thick progress bar
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = if (isPaused) AccentOrange else PastelTeal,
                trackColor = DarkBackground
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "%$percent Tamamlandı",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextSecondary
                )
                if (!isPaused && item.speedBytesPerSec > 0) {
                    Text(
                        text = "Kalan: ${DownloadViewModel.calculateEta(downloaded, total, item.speedBytesPerSec)}",
                        fontSize = 11.sp,
                        color = SoftCyan
                    )
                }
            }
        }
    }
}

@Composable
fun DownloadsCompletedCard(
    item: DownloadEntity,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onPreview: () -> Unit,
    onMoveToVault: () -> Unit
) {
    val category = FileCategory.detect(item.fileName, item.mimeType)
    val isArchive = category == FileCategory.ARCHIVES || ArchiveHelper.isArchive(item.fileName)
    val isMedia = category == FileCategory.MEDIA || item.mimeType.startsWith("video/") || item.mimeType.startsWith("audio/")
    val file = File(item.filePath)
    val fileExists = file.exists()

    ElevatedCard(
        onClick = onPreview,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = DarkSurface),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon / Thumbnail
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(DarkSurfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                val icon = when {
                    isMedia -> Icons.Default.Movie
                    isArchive -> Icons.Default.Unarchive
                    category == FileCategory.DOCUMENTS -> Icons.Default.Description
                    else -> Icons.Default.Folder
                }
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isMedia) PastelTeal else SoftCyan,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Details
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.fileName,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = DownloadViewModel.formatBytes(if (fileExists) file.length() else item.totalBytes),
                        fontSize = 11.sp,
                        color = PastelTeal,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = " • ${category.label}",
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                }
            }

            // Quick actions: Open/Play/Extract, Vault, Share, Delete
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onOpen, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = if (isMedia) Icons.Default.PlayArrow else if (isArchive) Icons.Default.Unarchive else Icons.Default.Folder,
                        contentDescription = "Aç",
                        tint = PastelTeal,
                        modifier = Modifier.size(20.dp)
                    )
                }

                IconButton(onClick = onMoveToVault, modifier = Modifier.size(36.dp)) {
                    Icon(imageVector = Icons.Default.Lock, contentDescription = "Kasaya Taşı", tint = SoftCyan, modifier = Modifier.size(18.dp))
                }

                IconButton(onClick = onShare, modifier = Modifier.size(36.dp)) {
                    Icon(imageVector = Icons.Default.Share, contentDescription = "Paylaş", tint = TextSecondary, modifier = Modifier.size(18.dp))
                }

                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(imageVector = Icons.Default.DeleteOutline, contentDescription = "Sil", tint = AccentRed.copy(alpha = 0.8f), modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
fun EmptyCompletedCard() {
    ElevatedCard(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = DarkSurface),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(DarkSurfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = Icons.Default.Folder, contentDescription = null, tint = PastelTeal, modifier = Modifier.size(26.dp))
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = "Henüz İndirilen Dosya Yok", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = "Yukarıdaki kutucuğa bir bağlantı yapıştırarak ilk indirmenizi başlatın.", fontSize = 12.sp, color = TextSecondary)
        }
    }
}

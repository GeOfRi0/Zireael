package com.example.ui.screens

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.components.DownloadNotificationCard
import com.example.ui.components.PermissionsOnboardingDialog
import com.example.ui.components.VideoQualitySelectorDialog
import com.example.ui.components.checkBatteryOptimization
import com.example.ui.components.checkStoragePermission
import com.example.ui.theme.AccentGreen
import com.example.ui.theme.AccentOrange
import com.example.ui.theme.AccentRed
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkCardBorder
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.PastelTeal
import com.example.ui.theme.SoftCyan
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.viewmodel.DownloadViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DownloadViewModel
) {
    val context = LocalContext.current

    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
    val urlInput by viewModel.urlInput.collectAsStateWithLifecycle()
    val selectedThreads by viewModel.selectedThreads.collectAsStateWithLifecycle()
    val isSniffing by viewModel.isSniffing.collectAsStateWithLifecycle()
    val sniffedList by viewModel.sniffedList.collectAsStateWithLifecycle()
    val showSnifferSheet by viewModel.showSnifferSheet.collectAsStateWithLifecycle()
    val showQualitySelector by viewModel.showQualitySelector.collectAsStateWithLifecycle()
    val qualityOptions by viewModel.qualityOptions.collectAsStateWithLifecycle()

    val activeDownloads by viewModel.activeDownloads.collectAsStateWithLifecycle()
    val runningDownloads by viewModel.runningDownloads.collectAsStateWithLifecycle()
    val queuedDownloads by viewModel.queuedDownloads.collectAsStateWithLifecycle()
    val pausedDownloads by viewModel.pausedDownloads.collectAsStateWithLifecycle()
    val completedDownloads by viewModel.completedDownloads.collectAsStateWithLifecycle()

    val totalSpeed by viewModel.totalDownloadSpeed.collectAsStateWithLifecycle()
    val previewEntity by viewModel.previewEntity.collectAsStateWithLifecycle()
    val showPermissionsOnboarding by viewModel.showPermissionsOnboarding.collectAsStateWithLifecycle()
    val capturedUrlBanner by viewModel.capturedUrlBanner.collectAsStateWithLifecycle()

    var showClearConfirmDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(PastelTeal),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = null,
                                tint = DarkBackground,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "TURBO IDM",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                letterSpacing = 0.5.sp
                            )
                            Text(
                                text = "Modern Çok Kanallı İndirici",
                                fontSize = 10.sp,
                                color = TextSecondary
                            )
                        }
                    }
                },
                actions = {
                    // Live Total Speed Pill
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (totalSpeed > 0) PastelTeal.copy(alpha = 0.15f) else DarkSurfaceVariant,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (totalSpeed > 0) PastelTeal else DarkCardBorder.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier.padding(end = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(if (totalSpeed > 0) AccentGreen else TextMuted)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = DownloadViewModel.formatSpeed(totalSpeed),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (totalSpeed > 0) PastelTeal else TextSecondary
                            )
                        }
                    }

                    // Permissions and Battery Optimization settings button
                    IconButton(
                        onClick = { viewModel.openPermissionsOnboarding() },
                        modifier = Modifier.testTag("open_permissions_dialog_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = "İzinler ve Pil Ayarı",
                            tint = if (!checkStoragePermission(context) || !checkBatteryOptimization(context)) AccentOrange else SoftCyan,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    // Clear history button
                    IconButton(onClick = { showClearConfirmDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = "Geçmişi Temizle",
                            tint = TextSecondary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkBackground
                )
            )
        },
        bottomBar = {
            // Modern 3-Tab BottomNavigationBar: İndirmeler, Tarayıcı, Kasa
            NavigationBar(
                containerColor = DarkSurface,
                tonalElevation = 8.dp
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { viewModel.selectTab(0) },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "İndirmeler"
                        )
                    },
                    label = {
                        Text(
                            text = "İndirmeler",
                            fontSize = 12.sp,
                            fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Medium
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = DarkBackground,
                        selectedTextColor = PastelTeal,
                        indicatorColor = PastelTeal,
                        unselectedIconColor = TextSecondary,
                        unselectedTextColor = TextSecondary
                    ),
                    modifier = Modifier.testTag("tab_downloader")
                )

                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { viewModel.selectTab(1) },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Language,
                            contentDescription = "Tarayıcı"
                        )
                    },
                    label = {
                        Text(
                            text = "Tarayıcı",
                            fontSize = 12.sp,
                            fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Medium
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = DarkBackground,
                        selectedTextColor = PastelTeal,
                        indicatorColor = PastelTeal,
                        unselectedIconColor = TextSecondary,
                        unselectedTextColor = TextSecondary
                    ),
                    modifier = Modifier.testTag("tab_browser")
                )

                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { viewModel.selectTab(2) },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Kasa"
                        )
                    },
                    label = {
                        Text(
                            text = "Kasa",
                            fontSize = 12.sp,
                            fontWeight = if (selectedTab == 2) FontWeight.Bold else FontWeight.Medium
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = DarkBackground,
                        selectedTextColor = PastelTeal,
                        indicatorColor = PastelTeal,
                        unselectedIconColor = TextSecondary,
                        unselectedTextColor = TextSecondary
                    ),
                    modifier = Modifier.testTag("tab_vault")
                )
            }
        },
        containerColor = DarkBackground
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Smooth animated tab transitions
            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    fadeIn(animationSpec = tween(220)) togetherWith fadeOut(animationSpec = tween(180))
                },
                label = "tab_animation"
            ) { targetTab ->
                when (targetTab) {
                    0 -> DownloadsScreen(
                        urlInput = urlInput,
                        onUrlInputChange = viewModel::setUrlInput,
                        selectedThreads = selectedThreads,
                        onThreadsChange = { viewModel.selectedThreads.value = it },
                        isSniffing = isSniffing,
                        onStartDownload = { viewModel.startDirectDownload() },
                        onStartSniffing = { viewModel.startSniffing() },
                        onOpenQualitySelector = { viewModel.openQualitySelector() },
                        runningDownloads = runningDownloads,
                        queuedDownloads = queuedDownloads,
                        pausedDownloads = pausedDownloads,
                        completedDownloads = completedDownloads,
                        onPause = viewModel::pauseDownload,
                        onResume = viewModel::resumeDownload,
                        onCancel = viewModel::cancelDownload,
                        onRetry = viewModel::retryDownload,
                        onOpenFile = { viewModel.openDownloadedFile(context, it) },
                        onShareFile = { viewModel.shareDownloadedFile(context, it) },
                        onDeleteFile = { viewModel.deleteDownload(it, true) },
                        onShowPreview = viewModel::showPreview,
                        onMoveToVault = { viewModel.moveFileToVault(it) },
                        capturedUrlBanner = capturedUrlBanner,
                        onDismissBanner = viewModel::dismissCapturedUrlBanner
                    )
                    1 -> WebSnifferBrowserScreen(
                        viewModel = viewModel
                    )
                    2 -> VaultScreen(
                        viewModel = viewModel,
                        completedDownloads = completedDownloads
                    )
                }
            }

            // Docked active download notification card when browsing other tabs
            if (selectedTab != 0 && activeDownloads.isNotEmpty()) {
                val currentDownload = activeDownloads.first()
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    DownloadNotificationCard(
                        item = currentDownload,
                        onPause = viewModel::pauseDownload,
                        onResume = viewModel::resumeDownload,
                        onCancel = viewModel::cancelDownload,
                        onRetry = viewModel::retryDownload
                    )
                }
            }
        }
    }

    // Sniffer BottomSheet
    if (showSnifferSheet) {
        SnifferSheet(
            items = sniffedList,
            onToggleItem = viewModel::toggleSniffedItem,
            onSelectAll = viewModel::selectAllSniffedItems,
            onDownloadSelected = viewModel::downloadSelectedSniffedItems,
            onDismiss = viewModel::dismissSnifferSheet
        )
    }

    // Video Quality Selector Dialog (HD 1080p, 720p, 360p, MP3/M4A Audio)
    if (showQualitySelector && qualityOptions.isNotEmpty()) {
        VideoQualitySelectorDialog(
            options = qualityOptions,
            selectedThreads = selectedThreads,
            onSelectAndDownload = viewModel::selectAndDownloadQuality,
            onDismiss = viewModel::dismissQualitySelector
        )
    }

    // Media Preview Dialog
    previewEntity?.let { entity ->
        MediaPreviewDialog(
            entity = entity,
            onDismiss = viewModel::dismissPreview,
            onOpenFile = { viewModel.openDownloadedFile(context, it) },
            onShareFile = { viewModel.shareDownloadedFile(context, it) }
        )
    }

    // Clear History Dialog
    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            title = {
                Text(
                    text = "İndirme Geçmişini Temizle",
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "Tüm indirme kayıtları temizlensin mi? (Cihaza kaydedilen dosyalar korunacaktır)",
                    color = TextSecondary,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearAllHistory()
                        showClearConfirmDialog = false
                    }
                ) {
                    Text("Temizle", color = AccentRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmDialog = false }) {
                    Text("Vazgeç", color = TextSecondary)
                }
            },
            containerColor = DarkSurface,
            shape = RoundedCornerShape(16.dp)
        )
    }

    // Permissions & Battery Optimization Onboarding Dialog
    if (showPermissionsOnboarding) {
        PermissionsOnboardingDialog(
            onDismiss = viewModel::dismissPermissionsOnboarding
        )
    }
}

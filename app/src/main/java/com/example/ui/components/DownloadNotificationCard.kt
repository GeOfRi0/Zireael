package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.example.ui.theme.DarkCardBorder
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.viewmodel.DownloadViewModel

@Composable
fun DownloadNotificationCard(
    item: DownloadEntity,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(true) }

    val category = FileCategory.detect(item.fileName, item.mimeType)
    val currentDownloadedBytes by rememberUpdatedState(item.downloadedBytes)
    val currentTotalBytes by rememberUpdatedState(item.totalBytes)
    val currentSpeed by rememberUpdatedState(item.speedBytesPerSec)

    // derivedStateOf prevents unnecessary recompositions during progress bar & percentage updates
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

    val formattedEta by remember {
        derivedStateOf {
            DownloadViewModel.calculateEta(currentDownloadedBytes, currentTotalBytes, currentSpeed)
        }
    }

    val animatedProgress by animateFloatAsState(
        targetValue = progressFraction,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "notification_progress"
    )

    val isPaused = item.status == DownloadStatus.PAUSED.name
    val isFailed = item.status == DownloadStatus.FAILED.name
    val isConnecting = item.status == DownloadStatus.CONNECTING.name
    val isCompleted = item.status == DownloadStatus.COMPLETED.name

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = BorderStroke(
            1.dp,
            if (isFailed) AccentRed.copy(alpha = 0.5f) else CyberCyan.copy(alpha = 0.35f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("download_notification_card_${item.id}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            // 1. Android System Notification Header Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // App icon badge
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(CyberCyan),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = null,
                            tint = Color(0xFF0A0F1D),
                            modifier = Modifier.size(14.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    Text(
                        text = "Zireael",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )

                    Text(
                        text = " • Bildirim Kartı",
                        fontSize = 11.sp,
                        color = TextSecondary
                    )

                    Spacer(modifier = Modifier.width(6.dp))

                    // Live glowing status indicator
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(if (isPaused) AccentOrange else AccentGreen)
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = DarkSurfaceVariant,
                        border = BorderStroke(0.5.dp, DarkCardBorder)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.NotificationsActive,
                                contentDescription = null,
                                tint = CyberCyan,
                                modifier = Modifier.size(11.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Canlı İndirme",
                                fontSize = 10.sp,
                                color = CyberCyan,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    IconButton(
                        onClick = { isExpanded = !isExpanded },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = "Genişlet/Daralt",
                            tint = TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 2. Notification Body (File info + Big percentage display)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Category icon with styled frame
                val icon = when (category) {
                    FileCategory.APKS -> Icons.Default.Bolt
                    FileCategory.MEDIA -> Icons.Default.Movie
                    FileCategory.ARCHIVES -> Icons.Default.Folder
                    FileCategory.DOCUMENTS -> Icons.Default.Description
                    else -> Icons.Default.Description
                }
                val iconTint = when (category) {
                    FileCategory.APKS -> AccentGreen
                    FileCategory.MEDIA -> CyberCyan
                    FileCategory.ARCHIVES -> Color(0xFFA855F7)
                    FileCategory.DOCUMENTS -> ElectricBlue
                    else -> Color(0xFFA855F7)
                }

                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(iconTint.copy(alpha = 0.15f))
                        .border(1.dp, iconTint.copy(alpha = 0.3f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

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
                        Text(
                            text = when {
                                isFailed -> "Hata oluştu"
                                isPaused -> "Duraklatıldı"
                                isConnecting -> "Bağlantı kuruluyor..."
                                isCompleted -> "Tamamlandı"
                                else -> "⚡ ${DownloadViewModel.formatSpeed(item.speedBytesPerSec)}"
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = when {
                                isFailed -> AccentRed
                                isPaused -> AccentOrange
                                isConnecting -> ElectricBlue
                                else -> CyberCyan
                            }
                        )

                        if (!isFailed && !isPaused && item.speedBytesPerSec > 0 && item.totalBytes > 0) {
                            Text(
                                text = " • Kalan: $formattedEta",
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                        }
                    }
                }

                // Big percentage badge in notification
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "%$percentInt",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = when {
                            isFailed -> AccentRed
                            isPaused -> AccentOrange
                            else -> CyberCyan
                        }
                    )
                    Text(
                        text = "${item.threadCount} Parça",
                        fontSize = 10.sp,
                        color = TextMuted,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 3. Prominent Percentage-Based Progress Bar with Glow
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(DarkSurfaceVariant)
            ) {
                if (item.totalBytes > 0) {
                    LinearProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp),
                        color = if (isPaused) AccentOrange else CyberCyan,
                        trackColor = DarkSurfaceVariant
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp),
                        color = CyberCyan,
                        trackColor = DarkSurfaceVariant
                    )
                }
            }

            // Percentage labels under progress bar (0% - 50% - 100%)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${DownloadViewModel.formatBytes(item.downloadedBytes)} / ${DownloadViewModel.formatBytes(item.totalBytes)}",
                    fontSize = 11.sp,
                    color = TextSecondary,
                    fontWeight = FontWeight.Medium
                )

                Text(
                    text = "%$percentInt Tamamlandı",
                    fontSize = 11.sp,
                    color = if (isPaused) AccentOrange else CyberCyan,
                    fontWeight = FontWeight.Bold
                )
            }

            // 4. Expanded Action Buttons Bar (Cancel & Pause/Resume)
            AnimatedVisibility(visible = isExpanded) {
                Column {
                    // Active Browser & Network Headers badges
                    val hasCustomHeaders = !item.userAgent.isNullOrBlank() || !item.cookies.isNullOrBlank() || !item.referer.isNullOrBlank()
                    if (hasCustomHeaders) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = CyberCyan.copy(alpha = 0.12f),
                                border = BorderStroke(0.5.dp, CyberCyan.copy(alpha = 0.3f))
                            ) {
                                Text(
                                    text = "🌐 Chrome İstemcisi",
                                    fontSize = 10.sp,
                                    color = CyberCyan,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            if (!item.cookies.isNullOrBlank()) {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = AccentGreen.copy(alpha = 0.12f),
                                    border = BorderStroke(0.5.dp, AccentGreen.copy(alpha = 0.3f))
                                ) {
                                    Text(
                                        text = "🍪 Cookie Aktif",
                                        fontSize = 10.sp,
                                        color = AccentGreen,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            if (!item.referer.isNullOrBlank()) {
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = ElectricBlue.copy(alpha = 0.12f),
                                    border = BorderStroke(0.5.dp, ElectricBlue.copy(alpha = 0.3f))
                                ) {
                                    Text(
                                        text = "🔗 Referer Aktif",
                                        fontSize = 10.sp,
                                        color = ElectricBlue,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Pause / Resume / Retry Button
                        if (isFailed) {
                            Button(
                                onClick = { onRetry(item.id) },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(42.dp),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = ElectricBlue,
                                    contentColor = Color.White
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Tekrar Dene",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        } else if (isPaused) {
                            Button(
                                onClick = { onResume(item.id) },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(42.dp),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = CyberCyan,
                                    contentColor = Color(0xFF0A0F1D)
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Devam Et",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        } else {
                            OutlinedButton(
                                onClick = { onPause(item.id) },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(42.dp),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, AccentOrange.copy(alpha = 0.8f))
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Pause,
                                    contentDescription = null,
                                    tint = AccentOrange,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Duraklat",
                                    color = AccentOrange,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // PROMINENT CANCEL BUTTON (Requested by user!)
                        Button(
                            onClick = { onCancel(item.id) },
                            modifier = Modifier
                                .weight(1f)
                                .height(42.dp)
                                .testTag("notification_cancel_button"),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AccentRed.copy(alpha = 0.18f),
                                contentColor = AccentRed
                            ),
                            border = BorderStroke(1.dp, AccentRed.copy(alpha = 0.6f))
                        ) {
                            Icon(
                                imageVector = Icons.Default.Cancel,
                                contentDescription = "İptal Et",
                                tint = AccentRed,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "İptal Et",
                                color = AccentRed,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

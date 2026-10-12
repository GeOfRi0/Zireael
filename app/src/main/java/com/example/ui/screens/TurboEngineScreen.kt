package com.example.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.DownloadEntity
import com.example.data.model.SegmentProgress
import com.example.ui.theme.AccentGreen
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

@Composable
fun TurboEngineScreen(
    totalSpeed: Long,
    activeDownloads: List<DownloadEntity>,
    segmentProgressMap: Map<String, List<SegmentProgress>>
) {
    val totalThreads = activeDownloads.sumOf { it.threadCount }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Live Speedometer / IDM Turbo Gauge Card
        item {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(CyberCyan.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = null,
                            tint = CyberCyan,
                            modifier = Modifier.size(40.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "ANLIK İNDİRME HIZI",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextSecondary,
                        letterSpacing = 1.sp
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = DownloadViewModel.formatSpeed(totalSpeed),
                        fontSize = 32.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = CyberCyan
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Stats indicators (Threads, Active tasks, Engine)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        MetricPill(
                            icon = Icons.Default.Memory,
                            label = "Paralel Hat",
                            value = "$totalThreads Bağlantı"
                        )
                        MetricPill(
                            icon = Icons.Default.Speed,
                            label = "Aktif İndirme",
                            value = "${activeDownloads.size} Görev"
                        )
                        MetricPill(
                            icon = Icons.Default.Storage,
                            label = "Akış Boyutu",
                            value = "64 KB Parça"
                        )
                    }
                }
            }
        }

        // 2. Multi-threaded Segment Visualizer Header
        item {
            Column {
                Text(
                    text = "IDM Çoklu Parça (Segment) Durumu",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = "Dosyalar HTTP Range ile parçalanıp eşzamanlı çekilir",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }
        }

        // 3. Segment Visualizer Cards for each active download
        if (activeDownloads.isEmpty()) {
            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = DarkSurface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Aktif Çoklu Parça İndirme Yok",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Bir indirme başlattığınızda parçaların paralel indirme akışı burada canlanacaktır.",
                            fontSize = 12.sp,
                            color = TextMuted,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }
        } else {
            items(activeDownloads, key = { it.id }) { item ->
                val segments = segmentProgressMap[item.id].orEmpty()
                DownloadSegmentVisualizerCard(
                    item = item,
                    segments = segments
                )
            }
        }

        // 4. Turbo Engine Architecture Info Card
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = AccentGreen,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "IDM Hızlandırma Mimarisi",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    ArchitectureBullet("Çok Parçalı İndirme", "Dosyalar 1 ila 8 eşit parçaya bölünüp paralel coroutine soketleri üzerinden çekilir.")
                    Spacer(modifier = Modifier.height(6.dp))
                    ArchitectureBullet("Sıfır Kopyalama & RandomAccessFile (Doğrudan Yazım)", "İndirilen baytlar geçici dosyalarda veya RAM'de bekletilmez; RandomAccessFile ve FileChannel ile anında hedef dosyanın ilgili byte offset'ine yazılır.")
                    Spacer(modifier = Modifier.height(6.dp))
                    ArchitectureBullet("Dinamik İş Parçacığı Yeniden Tahsisi (Work-Stealing)", "Kendi parçasını erken bitiren thread'ler boş durmaz; yavaş kalan kanalların kalan verisini ortadan ikiye bölerek desteğe koşar.")
                    Spacer(modifier = Modifier.height(6.dp))
                    ArchitectureBullet("Gerçek Chrome Tarayıcı & Ağ Başlıkları", "İstekler dinamik User-Agent, Referer, Cookie ve modern Sec-Ch-Ua Client Hints ile donatılır; bot filtrelerini ve sunucu engellerini gerçek Chrome gibi aşar.")
                    Spacer(modifier = Modifier.height(6.dp))
                    ArchitectureBullet("HTTP Range & Kaldığı Yerden Devam", "İnternet kopsa veya duraklatsanız dahi indirilen baytlar korunur, baştan başlamaz.")
                    Spacer(modifier = Modifier.height(6.dp))
                    ArchitectureBullet("Android Foreground Service", "İndirmeler arka planda ve ekran kilitliyken sistem ön plan servisi (dataSync) ile kesintisiz devam eder.")
                }
            }
        }

        // 5. Chrome Browser Fingerprint & Anti-Bot Protection Card
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Language,
                                contentDescription = null,
                                tint = CyberCyan,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Gerçek Chrome Tarayıcı Ağ Kimliği",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = AccentGreen.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = "Chrome 131 Aktif",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = AccentGreen,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "İndirme motoru isteklerinde sunuculara (Cloudflare, Akamai, AWS CloudFront) karşı gerçek Google Chrome istemcisi gibi görünür:",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(DarkBackground)
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        HeaderInfoRow("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/131...")
                        HeaderInfoRow("Sec-Ch-Ua", "\"Google Chrome\";v=\"131\", \"Chromium\";v=\"131\"")
                        HeaderInfoRow("Sec-Fetch", "Dest: document | Mode: navigate | Site: same-origin")
                        HeaderInfoRow("Dinamik Başlıklar", "Özel Referer, Cookie & Ek Başlıklar desteklenir")
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = CyberCyan
        )
        Text(
            text = value,
            fontSize = 10.sp,
            color = TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun MetricPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = CyberCyan,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )
        Text(
            text = label,
            fontSize = 11.sp,
            color = TextMuted
        )
    }
}

@Composable
fun DownloadSegmentVisualizerCard(
    item: DownloadEntity,
    segments: List<SegmentProgress>
) {
    val currentDownloadedBytes by rememberUpdatedState(item.downloadedBytes)
    val currentTotalBytes by rememberUpdatedState(item.totalBytes)
    val currentSpeed by rememberUpdatedState(item.speedBytesPerSec)

    val formattedChannelsAndBytes by remember {
        derivedStateOf {
            "${item.threadCount} Paralel Kanal • ${DownloadViewModel.formatBytes(currentDownloadedBytes)} / ${DownloadViewModel.formatBytes(currentTotalBytes)}"
        }
    }

    val formattedSpeedText by remember {
        derivedStateOf {
            "⚡ ${DownloadViewModel.formatSpeed(currentSpeed)}"
        }
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.fileName,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        maxLines = 1
                    )
                    Text(
                        text = formattedChannelsAndBytes,
                        fontSize = 12.sp,
                        color = CyberCyan
                    )
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = DarkSurfaceVariant
                ) {
                    Text(
                        text = formattedSpeedText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = AccentGreen,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Segments rows
            if (segments.isNotEmpty()) {
                segments.forEach { seg ->
                    SegmentProgressRowItem(seg = seg)
                }
            } else {
                // If single stream or preparing
                Text(
                    text = "Tek kanallı yüksek hızlı doğrudan akış (Single Stream Buffer)",
                    fontSize = 12.sp,
                    color = TextMuted
                )
            }
        }
    }
}

@Composable
fun SegmentProgressRowItem(seg: SegmentProgress) {
    val currentDownloaded by rememberUpdatedState(seg.downloadedBytes)
    val currentTotal by rememberUpdatedState(seg.totalBytes)

    val progressFraction by remember {
        derivedStateOf {
            if (currentTotal > 0) {
                (currentDownloaded.toFloat() / currentTotal).coerceIn(0f, 1f)
            } else 0f
        }
    }

    val progressPercent by remember {
        derivedStateOf {
            (progressFraction * 100).toInt()
        }
    }

    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (seg.isHelper) {
                        "⚡ Destek Hat #${seg.segmentIndex + 1} (Kanal #${(seg.helperTargetSegmentIndex ?: 0) + 1}'e Takviye)"
                    } else {
                        "Kanal #${seg.segmentIndex + 1} (${DownloadViewModel.formatBytes(seg.startByte)} - ${DownloadViewModel.formatBytes(seg.endByte)})"
                    },
                    fontSize = 11.sp,
                    fontWeight = if (seg.isHelper) FontWeight.Bold else FontWeight.Normal,
                    color = if (seg.isHelper) AccentGreen else TextSecondary
                )
                if (seg.isHelper) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = AccentGreen.copy(alpha = 0.2f)
                    ) {
                        Text(
                            text = "Dinamik Yeniden Tahsis",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = AccentGreen,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            Text(
                text = "$progressPercent%",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (seg.isHelper) AccentGreen else CyberCyan
            )
        }
        Spacer(modifier = Modifier.height(3.dp))
        LinearProgressIndicator(
            progress = { progressFraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(2.5.dp)),
            color = if (seg.isHelper) AccentGreen else CyberCyan,
            trackColor = DarkSurfaceVariant
        )
    }
}

@Composable
fun ArchitectureBullet(title: String, desc: String) {
    Row {
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(6.dp)
                .clip(CircleShape)
                .background(CyberCyan)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = title,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
            Text(
                text = desc,
                fontSize = 11.sp,
                color = TextSecondary
            )
        }
    }
}

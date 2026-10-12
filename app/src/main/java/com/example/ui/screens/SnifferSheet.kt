package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.model.ExtractedMedia
import com.example.data.model.FileCategory
import com.example.data.model.MediaType
import com.example.ui.theme.AccentGreen
import com.example.ui.theme.CyberCyan
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

enum class SnifferCategoryFilter(val label: String) {
    ALL("Tümü"),
    VIDEO("Video"),
    AUDIO("Ses"),
    IMAGE("Resim"),
    DOCUMENT("Belge / Diğer")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnifferSheet(
    items: List<ExtractedMedia>,
    onToggleItem: (String) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onDownloadSelected: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedCategoryFilter by remember { mutableStateOf(SnifferCategoryFilter.ALL) }

    val filteredItems = remember(items, selectedCategoryFilter) {
        when (selectedCategoryFilter) {
            SnifferCategoryFilter.ALL -> items
            SnifferCategoryFilter.VIDEO -> items.filter { it.mediaType == MediaType.VIDEO }
            SnifferCategoryFilter.AUDIO -> items.filter { it.mediaType == MediaType.AUDIO }
            SnifferCategoryFilter.IMAGE -> items.filter { it.mediaType == MediaType.IMAGE }
            SnifferCategoryFilter.DOCUMENT -> items.filter { it.mediaType == MediaType.DOCUMENT }
        }
    }

    val selectedCount = items.count { it.isSelected }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = DarkSurface,
        scrimColor = Color.Black.copy(alpha = 0.65f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            // Header with title and select all button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(PastelTeal.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = null,
                                tint = PastelTeal,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Yakalanan Medyalar",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }
                    Text(
                        text = "${items.size} medya tespit edildi • $selectedCount seçili",
                        fontSize = 12.sp,
                        color = TextSecondary,
                        modifier = Modifier.padding(start = 42.dp, top = 2.dp)
                    )
                }

                TextButton(
                    onClick = { onSelectAll(selectedCount != items.size) },
                    modifier = Modifier.testTag("toggle_all_sniffer_button")
                ) {
                    Text(
                        text = if (selectedCount == items.size) "Kaldır" else "Tümünü Seç",
                        color = SoftCyan,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Category Filter Tabs (Tümü, Video, Ses, Resim, Diğer)
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(SnifferCategoryFilter.values()) { filter ->
                    val isSelected = selectedCategoryFilter == filter
                    val count = when (filter) {
                        SnifferCategoryFilter.ALL -> items.size
                        SnifferCategoryFilter.VIDEO -> items.count { it.mediaType == MediaType.VIDEO }
                        SnifferCategoryFilter.AUDIO -> items.count { it.mediaType == MediaType.AUDIO }
                        SnifferCategoryFilter.IMAGE -> items.count { it.mediaType == MediaType.IMAGE }
                        SnifferCategoryFilter.DOCUMENT -> items.count { it.mediaType == MediaType.DOCUMENT }
                    }

                    Surface(
                        onClick = { selectedCategoryFilter = filter },
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) PastelTeal else DarkSurfaceVariant,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isSelected) PastelTeal else DarkCardBorder.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        ) {
                            Text(
                                text = filter.label,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) DarkBackground else TextPrimary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(
                                        if (isSelected) DarkBackground.copy(alpha = 0.2f) else DarkCardBorder
                                    )
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "$count",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) DarkBackground else TextSecondary
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Items list in spacious cards
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .height((filteredItems.size * 82).coerceAtMost(380).dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filteredItems, key = { it.url }) { media ->
                    SnifferItemRow(
                        media = media,
                        onToggle = { onToggleItem(media.url) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Bottom download button
            Button(
                onClick = onDownloadSelected,
                enabled = selectedCount > 0,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("download_selected_sniffer_button"),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PastelTeal,
                    contentColor = DarkBackground,
                    disabledContainerColor = DarkSurfaceVariant,
                    disabledContentColor = TextMuted
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = "İndir",
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Seçilenleri İndir ($selectedCount Öğe)",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun SnifferItemRow(
    media: ExtractedMedia,
    onToggle: () -> Unit
) {
    Card(
        onClick = onToggle,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (media.isSelected) DarkSurfaceVariant else DarkSurface
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (media.isSelected) PastelTeal.copy(alpha = 0.5f) else DarkCardBorder.copy(alpha = 0.4f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Selection icon
            IconButton(
                onClick = onToggle,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = if (media.isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    contentDescription = if (media.isSelected) "Seçili" else "Seçilmedi",
                    tint = if (media.isSelected) PastelTeal else TextMuted,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Thumbnail / Icon
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(DarkBackground)
                    .border(0.5.dp, DarkCardBorder.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (media.thumbnailUrl != null) {
                    AsyncImage(
                        model = media.thumbnailUrl,
                        contentDescription = media.title,
                        modifier = Modifier.size(48.dp),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    val icon = when (media.mediaType) {
                        MediaType.VIDEO -> Icons.Default.Movie
                        MediaType.AUDIO -> Icons.Default.Audiotrack
                        MediaType.IMAGE -> Icons.Default.Image
                        MediaType.DOCUMENT -> Icons.Default.Description
                    }
                    val iconColor = when (media.mediaType) {
                        MediaType.VIDEO -> PastelTeal
                        MediaType.AUDIO -> SoftCyan
                        MediaType.IMAGE -> AccentGreen
                        MediaType.DOCUMENT -> ElectricBlue
                    }
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconColor,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Details
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = media.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = media.mediaType.name,
                        fontSize = 11.sp,
                        color = PastelTeal,
                        fontWeight = FontWeight.Bold
                    )
                    if (media.approxSize > 0) {
                        Text(
                            text = " • ${DownloadViewModel.formatBytes(media.approxSize)}",
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }
                }
            }
        }
    }
}

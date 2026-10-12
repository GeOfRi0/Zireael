package com.example.ui.screens

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.example.data.local.DownloadEntity
import com.example.data.model.FileCategory
import com.example.ui.theme.AccentGreen
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
import java.io.File

@Composable
fun DownloadsLibraryScreen(
    downloads: List<DownloadEntity>,
    selectedCategory: FileCategory,
    onCategorySelected: (FileCategory) -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onOpenFile: (DownloadEntity) -> Unit,
    onShareFile: (DownloadEntity) -> Unit,
    onDeleteFile: (String) -> Unit,
    onShowPreview: (DownloadEntity) -> Unit
) {
    val totalSize = downloads.sumOf {
        val f = File(it.filePath)
        if (f.exists()) f.length() else it.totalBytes
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Search & Storage Summary Card
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    // Search bar
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        placeholder = { Text("İndirilen dosyaları ara...", fontSize = 13.sp, color = TextMuted) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                tint = CyberCyan,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        trailingIcon = {
                            if (searchQuery.isNotBlank()) {
                                IconButton(onClick = { onSearchQueryChange("") }) {
                                    Icon(
                                        imageVector = Icons.Default.Clear,
                                        contentDescription = "Temizle",
                                        tint = TextSecondary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
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
                            .testTag("library_search_field")
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Storage stats row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Folder,
                                contentDescription = null,
                                tint = CyberCyan,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Yerel Depolama",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        }

                        Text(
                            text = "${downloads.size} Dosya • ${DownloadViewModel.formatBytes(totalSize)}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = CyberCyan
                        )
                    }
                }
            }
        }

        // Category Filter Chips
        item {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(FileCategory.values()) { cat ->
                    val isSelected = selectedCategory == cat
                    Surface(
                        onClick = { onCategorySelected(cat) },
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSelected) CyberCyan else DarkSurface,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isSelected) CyberCyan else DarkCardBorder
                        ),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = cat.label,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) Color(0xFF0A0F1D) else TextPrimary
                            )
                        }
                    }
                }
            }
        }

        // Downloaded Items List
        if (downloads.isEmpty()) {
            item {
                EmptyLibraryCard()
            }
        } else {
            items(downloads, key = { it.id }) { item ->
                LibraryItemCard(
                    item = item,
                    onOpen = { onOpenFile(item) },
                    onShare = { onShareFile(item) },
                    onDelete = { onDeleteFile(item.id) },
                    onPreview = { onShowPreview(item) }
                )
            }
        }
    }
}

@Composable
fun LibraryItemCard(
    item: DownloadEntity,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onPreview: () -> Unit
) {
    val category = FileCategory.detect(item.fileName, item.mimeType)
    val file = File(item.filePath)
    val fileExists = file.exists()

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPreview() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Thumbnail or Icon
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(DarkSurfaceVariant)
                    .border(0.5.dp, DarkCardBorder, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (category == FileCategory.MEDIA && item.mimeType.startsWith("image/") && fileExists) {
                    AsyncImage(
                        model = file,
                        contentDescription = item.fileName,
                        modifier = Modifier.size(52.dp),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    val icon = when (category) {
                        FileCategory.APKS -> Icons.Default.Bolt
                        FileCategory.MEDIA -> Icons.Default.Movie
                        FileCategory.ARCHIVES -> Icons.Default.Folder
                        FileCategory.DOCUMENTS -> Icons.Default.Description
                        else -> Icons.Default.Description
                    }
                    val tint = when (category) {
                        FileCategory.APKS -> AccentGreen
                        FileCategory.MEDIA -> CyberCyan
                        FileCategory.ARCHIVES -> Color(0xFFA855F7)
                        FileCategory.DOCUMENTS -> ElectricBlue
                        else -> Color(0xFFA855F7)
                    }
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(26.dp)
                    )
                }
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
                    Text(
                        text = DownloadViewModel.formatBytes(if (fileExists) file.length() else item.totalBytes),
                        fontSize = 11.sp,
                        color = CyberCyan,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = " • ${category.label}",
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                    if (item.completedAt > 0) {
                        Text(
                            text = " • ${DownloadViewModel.formatDate(item.completedAt)}",
                            fontSize = 11.sp,
                            color = TextMuted
                        )
                    }
                }
            }

            // Quick Actions: Open & Share & Delete
            Row(verticalAlignment = Alignment.CenterVertically) {
                val isArchive = category == FileCategory.ARCHIVES || com.example.data.engine.ArchiveHelper.isArchive(item.fileName)
                IconButton(
                    onClick = onOpen,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = when {
                            category == FileCategory.MEDIA -> Icons.Default.PlayArrow
                            isArchive -> Icons.Default.Unarchive
                            else -> Icons.Default.Folder
                        },
                        contentDescription = if (category == FileCategory.MEDIA) "Oynat" else if (isArchive) "Arşivi Çıkar" else "Aç",
                        tint = CyberCyan,
                        modifier = Modifier.size(20.dp)
                    )
                }

                IconButton(
                    onClick = onShare,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Paylaş",
                        tint = ElectricBlue,
                        modifier = Modifier.size(18.dp)
                    )
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Sil",
                        tint = AccentRed.copy(alpha = 0.8f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun EmptyLibraryCard() {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = DarkSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(36.dp),
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
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    tint = CyberCyan,
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "Kütüphane Boş",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "İndirdiğiniz videolar, fotoğraflar ve belgeler burada listelenecektir.",
                fontSize = 12.sp,
                color = TextSecondary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

package com.indirici.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import com.indirici.app.ui.theme.AppIcons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.indirici.app.domain.model.AppSettings
import com.indirici.app.domain.model.EngineType
import com.indirici.app.domain.model.MediaFormatOption
import com.indirici.app.engine.MediaFormatCatalog
import com.indirici.app.ui.AddDownloadUiState
import com.indirici.app.ui.util.FormatUtils
import kotlin.math.roundToInt

@Composable
fun AddDownloadDialog(
    defaultChunkCount: Int,
    uiState: AddDownloadUiState,
    onAnalyzeClick: (String) -> Unit,
    onOpenCloudflareBrowser: (String) -> Unit,
    onSelectFormat: (String) -> Unit,
    onStartDownload: (url: String, customFileName: String?, chunkCount: Int) -> Unit,
    onDismiss: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    var url by remember { mutableStateOf("") }
    var customFileName by remember { mutableStateOf("") }
    var chunkCount by remember { mutableIntStateOf(defaultChunkCount) }

    LaunchedEffect(uiState.metadata, uiState.selectedFormatId) {
        val meta = uiState.metadata ?: return@LaunchedEffect
        if (meta.resolvedUrl.isNotBlank() && url.isBlank()) {
            url = meta.originalUrl
        }
        if (meta.engineType == EngineType.MEDIA) {
            val baseName = customFileName.ifBlank { meta.fileName }
            customFileName = MediaFormatCatalog.sanitizeTitleToFileName(baseName, uiState.selectedFormatId)
        } else if (customFileName.isBlank() && meta.fileName.isNotBlank()) {
            customFileName = meta.fileName
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Yeni İndirme Ekle",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Dosya veya Video/Ses Linki") },
                    placeholder = { Text("https://...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (url.isNotEmpty()) {
                                IconButton(
                                    onClick = {
                                        url = ""
                                        customFileName = ""
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Temizle"
                                    )
                                }
                            }
                            IconButton(
                                onClick = {
                                    val clip = clipboardManager.getText()?.text
                                    if (!clip.isNullOrBlank()) {
                                        url = clip.trim()
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = AppIcons.ContentPaste,
                                    contentDescription = "Panodan Yapıştır"
                                )
                            }
                        }
                    }
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = { onOpenCloudflareBrowser(url.ifBlank { "https://" }) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = AppIcons.Security,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Cloudflare Geç")
                    }

                    OutlinedButton(
                        onClick = { onAnalyzeClick(url) },
                        enabled = !uiState.isAnalyzing && url.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) {
                        if (uiState.isAnalyzing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Çözülüyor...")
                        } else {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Linki Çöz")
                        }
                    }
                }

                if (uiState.hasCloudflareSession) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = AppIcons.VerifiedUser,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Cloudflare oturumu hazır (Sadece bu site ve bu indirme boyunca RAM'de tutulur).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                if (uiState.errorMessage != null) {
                    var showDetails by remember(uiState.errorMessage, uiState.errorDetail) {
                        mutableStateOf(false)
                    }
                    var isCopied by remember(uiState.errorMessage, uiState.errorDetail) {
                        mutableStateOf(false)
                    }

                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = uiState.errorMessage,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            if (!uiState.errorDetail.isNullOrBlank()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (showDetails) "Ayrıntıyı gizle" else "Ayrıntıyı göster",
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            textDecoration = TextDecoration.Underline
                                        ),
                                        color = MaterialTheme.colorScheme.error,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier
                                            .clickable { showDetails = !showDetails }
                                            .padding(vertical = 4.dp)
                                    )

                                    if (showDetails) {
                                        TextButton(
                                            onClick = {
                                                clipboardManager.setText(AnnotatedString(uiState.errorDetail))
                                                isCopied = true
                                            }
                                        ) {
                                            Text(
                                                text = if (isCopied) "Kopyalandı!" else "Kopyala",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onErrorContainer
                                            )
                                        }
                                    }
                                }

                                if (showDetails) {
                                    HorizontalDivider(
                                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.2f),
                                        modifier = Modifier.padding(vertical = 4.dp)
                                    )
                                    SelectionContainer {
                                        Text(
                                            text = uiState.errorDetail,
                                            color = MaterialTheme.colorScheme.onErrorContainer,
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                val meta = uiState.metadata
                if (meta != null) {
                    if (meta.engineType == EngineType.MEDIA && meta.availableFormats.isNotEmpty()) {
                        MediaFormatSelectorSection(
                            formats = meta.availableFormats,
                            selectedFormatId = uiState.selectedFormatId,
                            onSelectFormat = onSelectFormat
                        )
                    } else {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "Boyut: ${FormatUtils.formatBytes(meta.totalBytes)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = if (meta.supportsRange) {
                                        "Çoklu Parça & Duraklatma: Destekleniyor"
                                    } else {
                                        "Çoklu Parça: Sunucu desteklemiyor (Tek parça indirilecek)"
                                    },
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = customFileName,
                    onValueChange = { customFileName = it },
                    label = { Text("Dosya Adı (İsteğe Bağlı)") },
                    placeholder = { Text("Otomatik algılanacak") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Parça Sayısı (Hızlandırma)",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "$chunkCount Parça",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Slider(
                        value = chunkCount.toFloat(),
                        onValueChange = { chunkCount = it.roundToInt() },
                        valueRange = AppSettings.MIN_CHUNK_COUNT.toFloat()..AppSettings.MAX_CHUNK_COUNT.toFloat(),
                        steps = AppSettings.MAX_CHUNK_COUNT - AppSettings.MIN_CHUNK_COUNT - 1
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onStartDownload(url, customFileName.takeIf { it.isNotBlank() }, chunkCount)
                },
                enabled = url.isNotBlank() && !uiState.isAnalyzing
            ) {
                Text("İndirmeyi Başlat")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("İptal")
            }
        }
    )
}

@Composable
private fun MediaFormatSelectorSection(
    formats: List<MediaFormatOption>,
    selectedFormatId: String,
    onSelectFormat: (String) -> Unit
) {
    val videoFormats = formats.filter { !it.isAudioOnly }
    val audioFormats = formats.filter { it.isAudioOnly }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "Kalite ve Format Seçin",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            if (videoFormats.isNotEmpty()) {
                Text(
                    text = "Video (MP4 / H.265 / MKV / WebM / MOV / AVI / WMV / TS / fMP4)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                videoFormats.forEach { option ->
                    FormatOptionRow(
                        option = option,
                        isSelected = option.formatId == selectedFormatId,
                        onSelect = { onSelectFormat(option.formatId) }
                    )
                }
            }

            if (audioFormats.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    text = "Ses (MP3 / M4A / AAC / WAV / OGG / FLAC / OPUS)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                audioFormats.forEach { option ->
                    FormatOptionRow(
                        option = option,
                        isSelected = option.formatId == selectedFormatId,
                        onSelect = { onSelectFormat(option.formatId) }
                    )
                }
            }
        }
    }
}

@Composable
private fun FormatOptionRow(
    option: MediaFormatOption,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            RadioButton(
                selected = isSelected,
                onClick = onSelect
            )
            Icon(
                imageVector = if (option.isAudioOnly) AppIcons.AudioFile else AppIcons.VideoFile,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = option.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
            )
        }
        if (option.estimatedSizeBytes > 0L) {
            Text(
                text = "~${FormatUtils.formatBytes(option.estimatedSizeBytes)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

package com.indirici.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.indirici.app.domain.model.AppSettings
import com.indirici.app.ui.YtDlpUpdateUiState
import kotlin.math.roundToInt

@Composable
fun SettingsDialog(
    currentSettings: AppSettings,
    ytDlpVersion: String,
    updateState: YtDlpUpdateUiState,
    isDownloading: Boolean,
    onUpdateYtDlp: () -> Unit,
    onSave: (defaultChunkCount: Int, maxConcurrentDownloads: Int) -> Unit,
    onDismiss: () -> Unit
) {
    var defaultChunks by remember { mutableIntStateOf(currentSettings.defaultChunkCount) }
    var maxConcurrent by remember { mutableIntStateOf(currentSettings.maxConcurrentDownloads) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "İndirme Ayarları",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Varsayılan Parça Sayısı",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "$defaultChunks Parça",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Slider(
                        value = defaultChunks.toFloat(),
                        onValueChange = { defaultChunks = it.roundToInt() },
                        valueRange = AppSettings.MIN_CHUNK_COUNT.toFloat()..AppSettings.MAX_CHUNK_COUNT.toFloat(),
                        steps = AppSettings.MAX_CHUNK_COUNT - AppSettings.MIN_CHUNK_COUNT - 1
                    )
                    Text(
                        text = "Dosyalar hızlı indirme için varsayılan olarak bu sayıda parçaya bölünür.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Aynı Anda İndirilecek Dosya",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "$maxConcurrent Dosya",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Slider(
                        value = maxConcurrent.toFloat(),
                        onValueChange = { maxConcurrent = it.roundToInt() },
                        valueRange = AppSettings.MIN_CONCURRENT.toFloat()..AppSettings.MAX_CONCURRENT.toFloat(),
                        steps = AppSettings.MAX_CONCURRENT - AppSettings.MIN_CONCURRENT - 1
                    )
                    Text(
                        text = "Fazla eklenen dosyalar kuyrukta bekler ve sırası gelince otomatik başlar.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = "Kayıt Klasörü: Telefonun İndirilenler (Downloads) klasörü",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.secondary
                )

                HorizontalDivider(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Video Motoru (yt-dlp)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Kurulu Sürüm",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = ytDlpVersion,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    val isUpdating = updateState is YtDlpUpdateUiState.Updating
                    val updateButtonEnabled = !isUpdating && !isDownloading

                    Button(
                        onClick = onUpdateYtDlp,
                        enabled = updateButtonEnabled,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isUpdating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Güncelleniyor...")
                        } else {
                            Text("Video Motorunu Güncelle")
                        }
                    }

                    if (isDownloading) {
                        Text(
                            text = "Aktif indirme devam ederken video motoru güncellenemez.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    when (updateState) {
                        is YtDlpUpdateUiState.Success -> {
                            Text(
                                text = updateState.message,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        is YtDlpUpdateUiState.Error -> {
                            Text(
                                text = updateState.message,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        else -> Unit
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(defaultChunks, maxConcurrent)
                    onDismiss()
                }
            ) {
                Text("Kaydet")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Kapat")
            }
        }
    )
}

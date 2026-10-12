package com.example.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.example.data.local.DownloadEntity
import com.example.data.model.FileCategory
import com.example.ui.theme.AccentGreen
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.DarkCardBorder
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.viewmodel.DownloadViewModel
import java.io.File

@Composable
fun MediaPreviewDialog(
    entity: DownloadEntity,
    onDismiss: () -> Unit,
    onOpenFile: (DownloadEntity) -> Unit,
    onShareFile: (DownloadEntity) -> Unit
) {
    val context = LocalContext.current
    val category = FileCategory.detect(entity.fileName, entity.mimeType)
    val file = File(entity.filePath)
    val fileExists = file.exists()

    var selectedHashAlgorithm by remember { mutableStateOf("SHA-256") }
    var expectedHashInput by remember { mutableStateOf("") }
    var computedHash by remember { mutableStateOf("") }
    var isCalculatingHash by remember { mutableStateOf(false) }

    LaunchedEffect(file, selectedHashAlgorithm) {
        if (fileExists) {
            isCalculatingHash = true
            computedHash = DownloadViewModel.computeFileHash(file, selectedHashAlgorithm)
            isCalculatingHash = false
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder),
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header with close button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Dosya Önizleme",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Kapat",
                            tint = TextSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Media Preview Area
                if (category == FileCategory.MEDIA && entity.mimeType.startsWith("image/") && fileExists) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        AsyncImage(
                            model = file,
                            contentDescription = entity.fileName,
                            modifier = Modifier.fillMaxWidth(),
                            contentScale = ContentScale.Fit
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(DarkSurfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
                                FileCategory.ARCHIVES -> com.example.ui.theme.AccentOrange
                                FileCategory.DOCUMENTS -> ElectricBlue
                                else -> ElectricBlue
                            }
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = tint,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = category.label,
                                fontSize = 13.sp,
                                color = TextSecondary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // File metadata details
                Text(
                    text = entity.fileName,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "Boyut: ${DownloadViewModel.formatBytes(if (fileExists) file.length() else entity.totalBytes)}",
                        fontSize = 12.sp,
                        color = CyberCyan
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Tür: ${entity.mimeType.substringAfter('/')}",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "Konum: ${entity.filePath}",
                    fontSize = 11.sp,
                    color = TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                // 7. MD5 / SHA-256 Hash Verification Section
                if (fileExists) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(DarkSurfaceVariant)
                            .padding(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Bütünlük & Hash Doğrulama",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )

                            // Algorithm toggle chips (SHA-256 / MD5)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf("SHA-256", "MD5").forEach { algo ->
                                    val isSelected = selectedHashAlgorithm == algo
                                    Surface(
                                        onClick = { selectedHashAlgorithm = algo },
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (isSelected) CyberCyan else DarkCardBorder,
                                        modifier = Modifier.testTag("hash_algo_$algo")
                                    ) {
                                        Text(
                                            text = algo,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSelected) Color(0xFF0A0F1D) else TextPrimary,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // User input field for expected hash
                        androidx.compose.material3.OutlinedTextField(
                            value = expectedHashInput,
                            onValueChange = { expectedHashInput = it.trim() },
                            placeholder = {
                                Text("Beklenen $selectedHashAlgorithm hash değerini yapıştırın", fontSize = 11.sp, color = androidx.compose.ui.graphics.Color(0xFF64748B))
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("hash_input_field"),
                            trailingIcon = {
                                androidx.compose.material3.IconButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                        val clip = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()
                                        if (!clip.isNullOrBlank()) {
                                            expectedHashInput = clip.trim()
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentPaste,
                                        contentDescription = "Yapıştır",
                                        tint = CyberCyan,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Calculated hash display or loading indicator
                        if (isCalculatingHash) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 4.dp)
                            ) {
                                androidx.compose.material3.CircularProgressIndicator(
                                    color = CyberCyan,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "$selectedHashAlgorithm hesaplanıyor...",
                                    fontSize = 11.sp,
                                    color = TextSecondary
                                )
                            }
                        } else if (computedHash.isNotBlank()) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Dosya Hash: ${computedHash.take(16)}...${computedHash.takeLast(8)}",
                                        fontSize = 10.sp,
                                        color = TextSecondary
                                    )

                                    if (expectedHashInput.isNotBlank()) {
                                        val isMatch = computedHash.equals(expectedHashInput, ignoreCase = true)
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = if (isMatch) Icons.Default.CheckCircle else Icons.Default.Cancel,
                                                contentDescription = if (isMatch) "Eşleşti" else "Uyuşmuyor",
                                                tint = if (isMatch) AccentGreen else com.example.ui.theme.AccentRed,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = if (isMatch) "Doğrulandı (Eşleşti)" else "Eşleşmedi (Hatalı)",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isMatch) AccentGreen else com.example.ui.theme.AccentRed
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Actions: Open with default app & Share
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = { onShareFile(entity) },
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                            .testTag("preview_share_button"),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, ElectricBlue)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Paylaş",
                            tint = ElectricBlue,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Paylaş",
                            color = ElectricBlue,
                            fontSize = 13.sp
                        )
                    }

                    val isArchive = category == FileCategory.ARCHIVES || com.example.data.engine.ArchiveHelper.isArchive(entity.fileName)

                    Button(
                        onClick = {
                            onOpenFile(entity)
                            onDismiss()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                            .testTag("preview_open_button"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CyberCyan,
                            contentColor = Color(0xFF0A0F1D)
                        )
                    ) {
                        Icon(
                            imageVector = when {
                                category == FileCategory.MEDIA -> Icons.Default.PlayArrow
                                isArchive -> Icons.Default.Unarchive
                                else -> Icons.Default.FolderOpen
                            },
                            contentDescription = "Aç",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = when {
                                category == FileCategory.MEDIA -> "Oynat"
                                isArchive -> "Arşivi Çıkar"
                                else -> "Dosyayı Aç"
                            },
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}

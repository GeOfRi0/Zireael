package com.example.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.engine.VaultFileItem
import com.example.data.local.DownloadEntity
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

/**
 * VaultScreen:
 * Modern, spacious, Material Design 3 Encrypted Vault screen.
 * Displays hardware-encrypted files, seamless unlock mechanisms,
 * and zero-leak playback via TurboPlayerActivity.
 */
@Composable
fun VaultScreen(
    viewModel: DownloadViewModel,
    completedDownloads: List<DownloadEntity>
) {
    val context = LocalContext.current
    val isUnlocked by viewModel.isVaultUnlocked.collectAsStateWithLifecycle()
    val vaultFiles by viewModel.vaultFiles.collectAsStateWithLifecycle()

    var pinInput by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf(false) }
    var showSetPinDialog by remember { mutableStateOf(false) }
    var newPinInput by remember { mutableStateOf("") }
    var showEncryptFileDialog by remember { mutableStateOf(false) }

    LaunchedEffect(isUnlocked) {
        if (isUnlocked) {
            viewModel.refreshVaultFiles()
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (!isUnlocked) {
            // -------------------------------------------------------------
            // LOCKED STATE: Spacious, elegant unlock card
            // -------------------------------------------------------------
            item {
                ElevatedCard(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = DarkSurface),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 3.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(PastelTeal.copy(alpha = 0.12f))
                                .border(1.dp, PastelTeal.copy(alpha = 0.3f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = PastelTeal,
                                modifier = Modifier.size(36.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "Gizli Kasa",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = "Donanım destekli AES-256-GCM ile şifrelenen dosyalarınız galeriden ve sistemden izole edilmiştir.",
                            fontSize = 13.sp,
                            color = TextSecondary,
                            textAlign = TextAlign.Center,
                            lineHeight = 18.sp
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        // PIN Input Field
                        OutlinedTextField(
                            value = pinInput,
                            onValueChange = {
                                pinInput = it.take(8)
                                pinError = false
                            },
                            placeholder = { Text("Kasa PIN Kodu (4-8 hane)", fontSize = 13.sp, color = TextMuted) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.NumberPassword,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    if (pinInput.isNotBlank()) {
                                        val success = viewModel.unlockVaultWithPin(pinInput)
                                        if (!success) pinError = true
                                    }
                                }
                            ),
                            isError = pinError,
                            shape = RoundedCornerShape(14.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = PastelTeal,
                                unfocusedBorderColor = DarkCardBorder,
                                focusedContainerColor = DarkBackground,
                                unfocusedContainerColor = DarkBackground,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (pinError) {
                            Text(
                                text = "Hatalı PIN veya Kasa henüz kurulmadı",
                                color = AccentRed,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = { showSetPinDialog = true },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                shape = RoundedCornerShape(14.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Key,
                                    contentDescription = null,
                                    tint = SoftCyan,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(text = "PIN Belirle", color = SoftCyan, fontSize = 13.sp)
                            }

                            Button(
                                onClick = {
                                    if (pinInput.isNotBlank()) {
                                        val ok = viewModel.unlockVaultWithPin(pinInput)
                                        if (!ok) pinError = true
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = PastelTeal,
                                    contentColor = DarkBackground
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.LockOpen,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(text = "Kilidi Aç", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        } else {
            // -------------------------------------------------------------
            // UNLOCKED STATE: Status Bar Card + Encrypted File Items
            // -------------------------------------------------------------
            item {
                ElevatedCard(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.elevatedCardColors(containerColor = DarkSurface),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(AccentGreen.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.LockOpen,
                                    contentDescription = null,
                                    tint = AccentGreen,
                                    modifier = Modifier.size(24.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column {
                                Text(
                                    text = "Kasa Açık",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                                Text(
                                    text = "${vaultFiles.size} Şifreli Dosya • Güvenli Alan",
                                    fontSize = 12.sp,
                                    color = TextSecondary
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Encrypt from downloads action
                            IconButton(
                                onClick = { showEncryptFileDialog = true },
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(DarkSurfaceVariant)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "Dosya Ekle",
                                    tint = PastelTeal,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            // Lock vault action
                            IconButton(
                                onClick = { viewModel.lockVault() },
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(DarkSurfaceVariant)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = "Kilitle",
                                    tint = AccentOrange,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }

            // List of encrypted files
            if (vaultFiles.isEmpty()) {
                item {
                    EmptyVaultCard(onAddFile = { showEncryptFileDialog = true })
                }
            } else {
                items(vaultFiles, key = { it.file.absolutePath }) { vaultItem ->
                    VaultFileCard(
                        item = vaultItem,
                        onPlayOrOpen = {
                            viewModel.openVaultMediaFile(context, vaultItem.file)
                        },
                        onRestore = {
                            viewModel.restoreFileFromVault(vaultItem.file)
                            Toast.makeText(context, "${vaultItem.originalName} genel depolamaya çıkarıldı", Toast.LENGTH_SHORT).show()
                        },
                        onDelete = {
                            vaultItem.file.delete()
                            viewModel.refreshVaultFiles()
                            Toast.makeText(context, "Kasa dosyası silindi", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }
    }

    // Set PIN Dialog
    if (showSetPinDialog) {
        AlertDialog(
            onDismissRequest = { showSetPinDialog = false },
            title = { Text("Yeni Kasa PIN Belirle", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("Cihaz ekran kilidinden bağımsız, en az 4 haneli bir PIN girin:", fontSize = 13.sp, color = TextSecondary)
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = newPinInput,
                        onValueChange = { newPinInput = it.take(8) },
                        placeholder = { Text("Yeni PIN (4-8 rakam)", color = TextMuted) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newPinInput.length >= 4) {
                            com.example.data.engine.TurboDownloadManager.getVaultManager(context).setPin(newPinInput)
                            viewModel.unlockVaultWithPin(newPinInput)
                            showSetPinDialog = false
                            Toast.makeText(context, "Kasa PIN kodu başarıyla ayarlandı!", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "PIN en az 4 haneli olmalıdır", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PastelTeal, contentColor = DarkBackground)
                ) {
                    Text("Kaydet", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSetPinDialog = false }) {
                    Text("Vazgeç", color = TextSecondary)
                }
            },
            containerColor = DarkSurface,
            shape = RoundedCornerShape(20.dp)
        )
    }

    // Pick file from downloads to encrypt
    if (showEncryptFileDialog) {
        AlertDialog(
            onDismissRequest = { showEncryptFileDialog = false },
            title = { Text("Kasaya Eklenecek Dosyayı Seçin", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                if (completedDownloads.isEmpty()) {
                    Text("Henüz tamamlanmış bir indirme bulunmuyor.", fontSize = 13.sp, color = TextSecondary)
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(280.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(completedDownloads, key = { it.id }) { item ->
                            Surface(
                                onClick = {
                                    viewModel.moveFileToVault(item)
                                    showEncryptFileDialog = false
                                    Toast.makeText(context, "${item.fileName} kasaya şifrelendi", Toast.LENGTH_SHORT).show()
                                },
                                shape = RoundedCornerShape(12.dp),
                                color = DarkSurfaceVariant,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = null,
                                        tint = PastelTeal,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(text = item.fileName, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(text = DownloadViewModel.formatBytes(item.totalBytes), fontSize = 11.sp, color = TextSecondary)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showEncryptFileDialog = false }) {
                    Text("Kapat", color = SoftCyan)
                }
            },
            containerColor = DarkSurface,
            shape = RoundedCornerShape(20.dp)
        )
    }
}

@Composable
fun VaultFileCard(
    item: VaultFileItem,
    onPlayOrOpen: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit
) {
    val isMedia = item.originalName.endsWith(".mp4", true) ||
            item.originalName.endsWith(".mkv", true) ||
            item.originalName.endsWith(".mp3", true) ||
            item.originalName.endsWith(".m4a", true)

    ElevatedCard(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = DarkSurface),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(DarkSurfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when {
                        item.originalName.endsWith(".mp4", true) || item.originalName.endsWith(".mkv", true) -> Icons.Default.Movie
                        item.originalName.endsWith(".mp3", true) || item.originalName.endsWith(".m4a", true) -> Icons.Default.Audiotrack
                        else -> Icons.Default.Description
                    },
                    contentDescription = null,
                    tint = PastelTeal,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            // Details
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.originalName,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(PastelTeal.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "AES-256-GCM",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = PastelTeal
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = DownloadViewModel.formatBytes(item.encryptedSizeBytes),
                        fontSize = 11.sp,
                        color = TextSecondary
                    )
                }
            }

            // Quick actions
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isMedia) {
                    IconButton(onClick = onPlayOrOpen, modifier = Modifier.size(36.dp)) {
                        Icon(imageVector = Icons.Default.PlayArrow, contentDescription = "Oynat", tint = PastelTeal, modifier = Modifier.size(22.dp))
                    }
                }

                IconButton(onClick = onRestore, modifier = Modifier.size(36.dp)) {
                    Icon(imageVector = Icons.Default.Unarchive, contentDescription = "Kasadan Çıkar", tint = SoftCyan, modifier = Modifier.size(18.dp))
                }

                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(imageVector = Icons.Default.DeleteOutline, contentDescription = "Sil", tint = AccentRed.copy(alpha = 0.8f), modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
fun EmptyVaultCard(onAddFile: () -> Unit) {
    ElevatedCard(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = DarkSurface),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
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
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = PastelTeal,
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "Kasa Henüz Boş",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "İndirdiğiniz hassas videoları, fotoğrafları veya belgeleri buraya şifreleyerek galeriden gizleyebilirsiniz.",
                fontSize = 12.sp,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onAddFile,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PastelTeal, contentColor = DarkBackground)
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Dosya Şifrele", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
    }
}

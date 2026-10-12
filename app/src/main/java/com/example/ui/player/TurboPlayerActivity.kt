package com.example.ui.player

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.data.engine.VaultManager
import com.example.ui.theme.AccentGreen
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/**
 * TurboPlayerActivity:
 * Built-in high performance media player powered by AndroidX Media3 (ExoPlayer & PlayerView).
 *
 * Guarantees:
 * 1. Plays local downloaded videos and audios without external third-party dependencies.
 * 2. Secure playback of Encrypted Vault files (.tvault) via isolated temporary stream cache.
 * 3. Never leaks plaintext media to external apps or MediaStore.
 * 4. Automatic clean-up of decrypted files on activity termination (onDestroy).
 * 5. Tactile UI with gestures, playback speeds (0.5x - 2.0x), Picture-in-Picture (PiP).
 */
class TurboPlayerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_FILE_PATH = "extra_file_path"
        const val EXTRA_MEDIA_TITLE = "extra_media_title"
        const val EXTRA_MIME_TYPE = "extra_mime_type"
        const val EXTRA_IS_VAULT = "extra_is_vault"

        /**
         * Convenience helper to launch TurboPlayerActivity for regular downloads or vault items.
         */
        fun start(
            context: Context,
            filePath: String,
            title: String? = null,
            mimeType: String? = null,
            isVault: Boolean = false
        ) {
            val intent = Intent(context, TurboPlayerActivity::class.java).apply {
                putExtra(EXTRA_FILE_PATH, filePath)
                putExtra(EXTRA_MEDIA_TITLE, title)
                putExtra(EXTRA_MIME_TYPE, mimeType)
                putExtra(EXTRA_IS_VAULT, isVault)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    private var exoPlayer: ExoPlayer? = null
    private var tempVaultPlaybackFile: File? = null
    private val vaultManager by lazy { VaultManager(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val filePath = intent.getStringExtra(EXTRA_FILE_PATH).orEmpty()
        val mediaTitle = intent.getStringExtra(EXTRA_MEDIA_TITLE) ?: File(filePath).name
        val mimeType = intent.getStringExtra(EXTRA_MIME_TYPE).orEmpty()
        val isVault = intent.getBooleanExtra(EXTRA_IS_VAULT, false)

        setContent {
            MyApplicationTheme {
                TurboPlayerScreen(
                    filePath = filePath,
                    mediaTitle = mediaTitle,
                    mimeType = mimeType,
                    isVault = isVault,
                    onBack = { finish() },
                    onEnterPip = { enterPictureInPicture() }
                )
            }
        }
    }

    override fun onPause() {
        super.onPause()
        exoPlayer?.pause()
    }

    override fun onStop() {
        super.onStop()
        if (!isInPictureInPictureMode) {
            exoPlayer?.pause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer?.release()
        exoPlayer = null

        // Secure cleanup: ensure decrypted plaintext file is wiped immediately
        tempVaultPlaybackFile?.delete()
        vaultManager.cleanupSecurePlaybackFiles()
    }

    private fun enterPictureInPicture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build()
                enterPictureInPictureMode(params)
            } catch (e: Exception) {
                Toast.makeText(this, "PiP başlatılamadı", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @Composable
    private fun TurboPlayerScreen(
        filePath: String,
        mediaTitle: String,
        mimeType: String,
        isVault: Boolean,
        onBack: () -> Unit,
        onEnterPip: () -> Unit
    ) {
        val context = this@TurboPlayerActivity
        var isDecrypting by remember { mutableStateOf(isVault) }
        var activePlaybackFile by remember { mutableStateOf<File?>(null) }
        var errorMessage by remember { mutableStateOf<String?>(null) }

        var isPlaying by remember { mutableStateOf(false) }
        var currentPosition by remember { mutableLongStateOf(0L) }
        var totalDuration by remember { mutableLongStateOf(0L) }
        var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
        var showControls by remember { mutableStateOf(true) }

        val isAudio = remember(filePath, mimeType) {
            mimeType.startsWith("audio/") ||
                    filePath.endsWith(".mp3", ignoreCase = true) ||
                    filePath.endsWith(".m4a", ignoreCase = true) ||
                    filePath.endsWith(".flac", ignoreCase = true) ||
                    filePath.endsWith(".wav", ignoreCase = true) ||
                    filePath.endsWith(".aac", ignoreCase = true)
        }

        // Initialize media file (decrypt if vault)
        LaunchedEffect(filePath) {
            if (filePath.isBlank()) {
                errorMessage = "Oynatılacak dosya yolu geçersiz"
                return@LaunchedEffect
            }

            val sourceFile = File(filePath)
            if (!sourceFile.exists()) {
                errorMessage = "Dosya bulunamadı: ${sourceFile.name}"
                return@LaunchedEffect
            }

            if (isVault) {
                isDecrypting = true
                val result = vaultManager.createSecureDecryptedPlaybackFile(sourceFile)
                result.fold(
                    onSuccess = { decryptedTemp ->
                        tempVaultPlaybackFile = decryptedTemp
                        activePlaybackFile = decryptedTemp
                        isDecrypting = false
                    },
                    onFailure = { error ->
                        isDecrypting = false
                        errorMessage = "Kasa dosyası çözülemedi: ${error.message}"
                    }
                )
            } else {
                activePlaybackFile = sourceFile
            }
        }

        // Initialize ExoPlayer when media file is ready
        DisposableEffect(activePlaybackFile) {
            val fileToPlay = activePlaybackFile ?: return@DisposableEffect onDispose {}

            val player = ExoPlayer.Builder(context).build().apply {
                val mediaItem = MediaItem.fromUri(Uri.fromFile(fileToPlay))
                setMediaItem(mediaItem)
                prepare()
                playWhenReady = true

                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(playing: Boolean) {
                        isPlaying = playing
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) {
                            totalDuration = duration.coerceAtLeast(0L)
                        }
                    }
                })
            }
            exoPlayer = player

            onDispose {
                player.release()
                if (exoPlayer == player) {
                    exoPlayer = null
                }
            }
        }

        // Periodic position tracker
        LaunchedEffect(isPlaying) {
            while (isPlaying) {
                exoPlayer?.let { player ->
                    currentPosition = player.currentPosition.coerceAtLeast(0L)
                    totalDuration = player.duration.coerceAtLeast(0L)
                }
                delay(250)
            }
        }

        // Auto-hide controls after 4 seconds
        LaunchedEffect(showControls, isPlaying) {
            if (showControls && isPlaying) {
                delay(4000)
                showControls = false
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) {
                    showControls = !showControls
                }
        ) {
            when {
                isDecrypting -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(
                            color = CyberCyan,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Gizli Kasa Medyası Güvenli Çözülüyor...",
                            color = TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                errorMessage != null -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = errorMessage ?: "",
                            color = TextPrimary,
                            fontSize = 14.sp
                        )
                    }
                }
                activePlaybackFile != null -> {
                    if (isAudio) {
                        // Audio Mode Visualizer Card
                        AudioVisualizerView(
                            title = mediaTitle,
                            isPlaying = isPlaying,
                            isVault = isVault
                        )
                    } else {
                        // Video PlayerView
                        AndroidView(
                            factory = { ctx ->
                                PlayerView(ctx).apply {
                                    this.player = exoPlayer
                                    useController = false // Use custom sleek tactile controls
                                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                    layoutParams = FrameLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                }
                            },
                            update = { view ->
                                view.player = exoPlayer
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }

            // Sleek Tactile Overlay Controls
            AnimatedVisibility(
                visible = showControls && activePlaybackFile != null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = 0.75f),
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.85f)
                                )
                            )
                        )
                ) {
                    // Top Bar: Back, Title, Vault Pill, PiP
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 36.dp)
                            .align(Alignment.TopCenter),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(DarkSurface.copy(alpha = 0.8f))
                        ) {
                            Icon(
                                imageVector = androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Geri",
                                tint = TextPrimary
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = mediaTitle,
                                color = TextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (isVault) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(top = 2.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = null,
                                        tint = AccentGreen,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Gizli Kasa Korumalı Oynatıcı",
                                        color = AccentGreen,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }

                        if (!isAudio && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            IconButton(
                                onClick = onEnterPip,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(DarkSurface.copy(alpha = 0.8f))
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PictureInPicture,
                                    contentDescription = "PiP Modu",
                                    tint = CyberCyan
                                )
                            }
                        }
                    }

                    // Center Transport Controls (Rewind, Play/Pause, Fast-Forward)
                    Row(
                        modifier = Modifier.align(Alignment.Center),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(28.dp)
                    ) {
                        IconButton(
                            onClick = {
                                exoPlayer?.let { p ->
                                    p.seekTo((p.currentPosition - 10000).coerceAtLeast(0L))
                                }
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(DarkSurface.copy(alpha = 0.8f))
                        ) {
                            Icon(
                                imageVector = Icons.Default.FastRewind,
                                contentDescription = "-10s",
                                tint = TextPrimary,
                                modifier = Modifier.size(26.dp)
                            )
                        }

                        IconButton(
                            onClick = {
                                exoPlayer?.let { p ->
                                    if (p.isPlaying) p.pause() else p.play()
                                }
                            },
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(CyberCyan)
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Duraklat" else "Oynat",
                                tint = Color(0xFF0A0F1D),
                                modifier = Modifier.size(36.dp)
                            )
                        }

                        IconButton(
                            onClick = {
                                exoPlayer?.let { p ->
                                    p.seekTo((p.currentPosition + 10000).coerceAtMost(p.duration))
                                }
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(DarkSurface.copy(alpha = 0.8f))
                        ) {
                            Icon(
                                imageVector = Icons.Default.FastForward,
                                contentDescription = "+10s",
                                tint = TextPrimary,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }

                    // Bottom Bar: Scrubber, Timestamps, Speed selector
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 24.dp)
                            .align(Alignment.BottomCenter)
                    ) {
                        // Scrubber Slider
                        val sliderPosition = if (totalDuration > 0) {
                            (currentPosition.toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f)
                        } else 0f

                        Slider(
                            value = sliderPosition,
                            onValueChange = { frac ->
                                if (totalDuration > 0) {
                                    val target = (frac * totalDuration).toLong()
                                    currentPosition = target
                                    exoPlayer?.seekTo(target)
                                }
                            },
                            colors = SliderDefaults.colors(
                                thumbColor = CyberCyan,
                                activeTrackColor = CyberCyan,
                                inactiveTrackColor = Color.White.copy(alpha = 0.25f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${formatDuration(currentPosition)} / ${formatDuration(totalDuration)}",
                                color = TextSecondary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )

                            // Playback Speed Button
                            Surface(
                                onClick = {
                                    playbackSpeed = when (playbackSpeed) {
                                        0.5f -> 1.0f
                                        1.0f -> 1.25f
                                        1.25f -> 1.5f
                                        1.5f -> 2.0f
                                        else -> 0.5f
                                    }
                                    exoPlayer?.playbackParameters = PlaybackParameters(playbackSpeed)
                                },
                                shape = RoundedCornerShape(8.dp),
                                color = DarkSurface.copy(alpha = 0.85f),
                                border = androidx.compose.foundation.BorderStroke(1.dp, CyberCyan.copy(alpha = 0.5f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Speed,
                                        contentDescription = null,
                                        tint = CyberCyan,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "${playbackSpeed}x",
                                        color = CyberCyan,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun AudioVisualizerView(
        title: String,
        isPlaying: Boolean,
        isVault: Boolean
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(160.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(ElectricBlue.copy(alpha = 0.35f), DarkBackground)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = CyberCyan,
                    modifier = Modifier.size(72.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = title,
                color = TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = if (isPlaying) "Oynatılıyor..." else "Duraklatıldı",
                color = if (isPlaying) AccentGreen else TextSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }

    private fun formatDuration(millis: Long): String {
        val totalSec = millis / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return if (min >= 60) {
            val hr = min / 60
            val remMin = min % 60
            String.format(Locale.getDefault(), "%02d:%02d:%02d", hr, remMin, sec)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", min, sec)
        }
    }
}

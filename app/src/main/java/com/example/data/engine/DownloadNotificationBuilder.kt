package com.example.data.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.data.service.DownloadForegroundService
import com.example.ui.viewmodel.DownloadViewModel

/**
 * DownloadNotificationBuilder:
 * Manages notification channels and builds rich, persistent notifications
 * showing real-time download percentage, transfer speed, and remaining time (ETA).
 */
object DownloadNotificationBuilder {

    const val FOREGROUND_CHANNEL_ID = "turbo_idm_foreground_channel"
    const val FOREGROUND_CHANNEL_NAME = "Zireael İndirme Servisi"

    const val COMPLETE_CHANNEL_ID = "turbo_idm_completed_channel"
    const val COMPLETE_CHANNEL_NAME = "Tamamlanan ve Hatalı İndirmeler"

    const val FOREGROUND_NOTIFICATION_ID = 9001

    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return

            // 1. Silent, ongoing channel for active download progress updates
            val foregroundChannel = NotificationChannel(
                FOREGROUND_CHANNEL_ID,
                FOREGROUND_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Aktif indirmeler için gerçek zamanlı hız, yüzde ve ETA bildirimleri"
                setShowBadge(false)
            }

            // 2. Alert channel for completed or failed downloads
            val completeChannel = NotificationChannel(
                COMPLETE_CHANNEL_ID,
                COMPLETE_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Tamamlanan veya hata alan indirmeler için bildirimler"
                setShowBadge(true)
            }

            manager.createNotificationChannel(foregroundChannel)
            manager.createNotificationChannel(completeChannel)
        }
    }

    /**
     * Builds the persistent foreground notification displaying real-time progress, speed, and ETA.
     */
    fun buildForegroundNotification(
        context: Context,
        downloadId: String?,
        fileName: String,
        progress: Int,
        speedBytesPerSec: Long,
        etaString: String?,
        activeCount: Int,
        isPaused: Boolean = false
    ): Notification {
        createNotificationChannels(context)

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cleanProgress = progress.coerceIn(0, 100)
        val speedStr = DownloadViewModel.formatSpeed(speedBytesPerSec)
        val etaStr = if (!etaString.isNullOrBlank() && etaString != "--:--") "Kalan: $etaString" else ""

        val title = if (activeCount > 1) {
            "⚡ Zireael: $activeCount Dosya İndiriliyor"
        } else {
            "⚡ Zireael: $fileName"
        }

        val contentText = when {
            isPaused -> if (activeCount > 1) "$activeCount dosya duraklatıldı • %$cleanProgress" else "Duraklatıldı • %$cleanProgress"
            activeCount > 1 -> {
                if (etaStr.isNotBlank()) "$activeCount aktif dosya • %$cleanProgress • $speedStr • $etaStr"
                else "$activeCount aktif dosya • %$cleanProgress • $speedStr"
            }
            etaStr.isNotBlank() -> "%$cleanProgress • $speedStr • $etaStr"
            speedBytesPerSec > 0 -> "%$cleanProgress • $speedStr"
            else -> "%$cleanProgress tamamlandı"
        }

        val builder = NotificationCompat.Builder(context, FOREGROUND_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSubText("%$cleanProgress")
            .setProgress(100, cleanProgress, cleanProgress <= 0 && activeCount <= 1)
            .setOngoing(!isPaused)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)

        // Action Buttons: Pause / Resume & Cancel
        if (!isPaused) {
            val pauseIntent = Intent(context, DownloadForegroundService::class.java).apply {
                action = DownloadForegroundService.ACTION_PAUSE
                if (downloadId != null) putExtra(DownloadForegroundService.EXTRA_DOWNLOAD_ID, downloadId)
            }
            val pausePendingIntent = PendingIntent.getService(
                context,
                101,
                pauseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                android.R.drawable.ic_media_pause,
                "Durdur",
                pausePendingIntent
            )
        } else {
            val resumeIntent = Intent(context, DownloadForegroundService::class.java).apply {
                action = DownloadForegroundService.ACTION_RESUME
                if (downloadId != null) putExtra(DownloadForegroundService.EXTRA_DOWNLOAD_ID, downloadId)
            }
            val resumePendingIntent = PendingIntent.getService(
                context,
                102,
                resumeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                android.R.drawable.ic_media_play,
                "Devam Et",
                resumePendingIntent
            )
        }

        val cancelIntent = Intent(context, DownloadForegroundService::class.java).apply {
            action = DownloadForegroundService.ACTION_CANCEL
            if (downloadId != null) putExtra(DownloadForegroundService.EXTRA_DOWNLOAD_ID, downloadId)
        }
        val cancelPendingIntent = PendingIntent.getService(
            context,
            103,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            "İptal",
            cancelPendingIntent
        )

        return builder.build()
    }

    /**
     * Builds a completion notification when a download finishes successfully.
     */
    fun buildCompletionNotification(
        context: Context,
        downloadId: String,
        fileName: String,
        totalBytes: Long
    ): Notification {
        createNotificationChannels(context)

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            downloadId.hashCode(),
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val sizeStr = DownloadViewModel.formatBytes(totalBytes)

        return NotificationCompat.Builder(context, COMPLETE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("İndirme Tamamlandı")
            .setContentText("$fileName ($sizeStr)")
            .setAutoCancel(true)
            .setOngoing(false)
            .setContentIntent(contentPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }

    /**
     * Builds a failure notification when a download encounters a fatal error.
     */
    fun buildFailureNotification(
        context: Context,
        downloadId: String,
        fileName: String,
        errorMessage: String
    ): Notification {
        createNotificationChannels(context)

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            downloadId.hashCode(),
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, COMPLETE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("İndirme Başarısız")
            .setContentText("$fileName: $errorMessage")
            .setAutoCancel(true)
            .setOngoing(false)
            .setContentIntent(contentPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }
}

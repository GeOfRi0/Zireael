package com.example.data.engine

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.example.data.local.DownloadEntity
import com.example.ui.viewmodel.DownloadViewModel

/**
 * SystemDownloadNotificationHelper:
 * Manages individual and completion/failure notifications for Zireael downloads.
 * Uses DownloadNotificationBuilder for rich formatting including ETA, speed, and actions.
 */
class SystemDownloadNotificationHelper(private val context: Context) {

    init {
        DownloadNotificationBuilder.createNotificationChannels(context)
    }

    /**
     * Updates an individual progress notification for a given download entity.
     */
    fun updateProgressNotification(item: DownloadEntity, speedBytes: Long) {
        val total = item.totalBytes
        val downloaded = item.downloadedBytes
        val percent = if (total > 0) ((downloaded * 100) / total).toInt().coerceIn(0, 100) else 0
        val eta = DownloadViewModel.calculateEta(downloaded, total, speedBytes)

        val notification = DownloadNotificationBuilder.buildForegroundNotification(
            context = context,
            downloadId = item.id,
            fileName = item.fileName,
            progress = percent,
            speedBytesPerSec = speedBytes,
            etaString = eta,
            activeCount = 1,
            isPaused = false
        )

        try {
            val notificationManager = NotificationManagerCompat.from(context)
            if (notificationManager.areNotificationsEnabled()) {
                notificationManager.notify(item.id.hashCode(), notification)
            }
        } catch (e: SecurityException) {
            // Permission POST_NOTIFICATIONS may not be granted by user yet
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Posts a notification when a download completes successfully.
     */
    fun notifyCompleted(item: DownloadEntity) {
        val notification = DownloadNotificationBuilder.buildCompletionNotification(
            context = context,
            downloadId = item.id,
            fileName = item.fileName,
            totalBytes = item.totalBytes
        )
        try {
            val notificationManager = NotificationManagerCompat.from(context)
            if (notificationManager.areNotificationsEnabled()) {
                notificationManager.notify(item.id.hashCode(), notification)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Posts a notification when a download encounters a fatal error.
     */
    fun notifyFailed(item: DownloadEntity, error: String) {
        val notification = DownloadNotificationBuilder.buildFailureNotification(
            context = context,
            downloadId = item.id,
            fileName = item.fileName,
            errorMessage = error
        )
        try {
            val notificationManager = NotificationManagerCompat.from(context)
            if (notificationManager.areNotificationsEnabled()) {
                notificationManager.notify(item.id.hashCode(), notification)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun cancelNotification(id: String) {
        try {
            NotificationManagerCompat.from(context).cancel(id.hashCode())
        } catch (e: Exception) {
            // Ignore
        }
    }
}

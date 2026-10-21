package com.indirici.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.indirici.app.IndiriciApplication
import com.indirici.app.domain.model.DownloadItem
import com.indirici.app.domain.model.DownloadStatus
import com.indirici.app.domain.model.EngineType
import com.indirici.app.ui.MainActivity
import com.indirici.app.ui.util.FormatUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class DownloadForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var monitorJob: Job? = null
    private val notifiedCompletedIds = HashSet<Long>()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat(buildSummaryNotification(emptyList()))

        if (monitorJob == null || monitorJob?.isActive != true) {
            val coordinator = (application as IndiriciApplication).queueCoordinator
            monitorJob = serviceScope.launch {
                coordinator.downloadsFlow.collect { allItems ->
                    allItems.filter { it.status == DownloadStatus.COMPLETED }.forEach { completed ->
                        if (notifiedCompletedIds.add(completed.id)) {
                            showCompletedNotification(completed.fileName)
                        }
                    }

                    val activeOrQueued = allItems.filter {
                        it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED
                    }

                    if (activeOrQueued.isEmpty()) {
                        delay(1200L)
                        if (!coordinator.hasActiveOrQueuedWork()) {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                    } else {
                        val notification = buildSummaryNotification(activeOrQueued)
                        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        nm.notify(NOTIFICATION_ID, notification)
                    }
                }
            }
        }

        return START_STICKY
    }

    private fun startForegroundCompat(notification: Notification) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun buildSummaryNotification(activeItems: List<DownloadItem>): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseAllIntent = Intent(this, DownloadNotificationReceiver::class.java).apply {
            action = DownloadNotificationReceiver.ACTION_PAUSE_ALL
        }
        val pauseAllPendingIntent = PendingIntent.getBroadcast(
            this,
            1,
            pauseAllIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val downloadingList = activeItems.filter { it.status == DownloadStatus.DOWNLOADING }
        val queuedCount = activeItems.count { it.status == DownloadStatus.QUEUED }

        val hasVideoStream = downloadingList.any {
            it.engineType == EngineType.FFMPEG_HLS || it.engineType == EngineType.MEDIA
        }

        val title = when {
            downloadingList.size == 1 && hasVideoStream ->
                "Video indiriliyor... (${downloadingList.first().fileName})"
            downloadingList.size == 1 ->
                downloadingList.first().fileName
            downloadingList.size > 1 && hasVideoStream ->
                "Video indiriliyor... (${downloadingList.size} aktif görev)"
            downloadingList.size > 1 ->
                "${downloadingList.size} dosya indiriliyor"
            queuedCount > 0 ->
                "$queuedCount görev sırada bekliyor"
            else ->
                "Video indiriliyor..."
        }

        val totalSpeed = downloadingList.sumOf { it.speedBytesPerSec }
        val totalBytes = downloadingList.sumOf { it.totalBytes.coerceAtLeast(0L) }
        val totalDownloaded = downloadingList.sumOf { it.downloadedBytes.coerceAtLeast(0L) }

        val progressPercent = if (totalBytes > 0L) {
            ((totalDownloaded.toDouble() / totalBytes.toDouble()) * 100.0).toInt().coerceIn(0, 100)
        } else {
            0
        }

        val subtitle = if (downloadingList.isNotEmpty()) {
            val sizeText = if (totalBytes > 0L) {
                "${FormatUtils.formatBytes(totalDownloaded)} / ${FormatUtils.formatBytes(totalBytes)} (%$progressPercent)"
            } else {
                FormatUtils.formatBytes(totalDownloaded)
            }
            val queueSuffix = if (queuedCount > 0) " • +$queuedCount sırada" else ""
            "$sizeText • ${FormatUtils.formatSpeed(totalSpeed)}$queueSuffix"
        } else {
            "Video indiriliyor..."
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPendingIntent)
            .setProgress(100, progressPercent, totalBytes <= 0L)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Tümünü Duraklat",
                pauseAllPendingIntent
            )
            .build()
    }

    private fun showCompletedNotification(fileName: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Tamamlandı")
            .setContentText("$fileName başarıyla indirildi.")
            .setAutoCancel(true)
            .build()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(COMPLETED_NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "İndirme İlerlemesi",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Arka planda devam eden video ve dosya indirmelerinin ilerleme durumu"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "indirici_download_channel"
        const val NOTIFICATION_ID = 1001
        const val COMPLETED_NOTIFICATION_ID = 1002
        const val ACTION_START_OR_UPDATE = "com.indirici.app.action.START_OR_UPDATE"
    }
}

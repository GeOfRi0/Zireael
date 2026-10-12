package com.example.data.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.example.data.engine.DownloadNotificationBuilder
import com.example.data.engine.TurboDownloadManager

/**
 * DownloadForegroundService:
 * Foreground service ensuring uninterrupted background downloads on Android.
 *
 * Guarantees:
 * 1. Persistent CPU WakeLock & high-performance WifiLock preventing device sleep during active downloads.
 * 2. Android 14+ compatible FOREGROUND_SERVICE_TYPE_DATA_SYNC foreground service declaration.
 * 3. Real-time notification updates showing progress percentage, transfer speed, and remaining time (ETA).
 * 4. Interactive notification action intents (Pause, Resume, Cancel).
 */
class DownloadForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = DownloadNotificationBuilder.FOREGROUND_CHANNEL_ID
        const val CHANNEL_NAME = DownloadNotificationBuilder.FOREGROUND_CHANNEL_NAME
        const val NOTIFICATION_ID = DownloadNotificationBuilder.FOREGROUND_NOTIFICATION_ID

        const val ACTION_START = "com.example.action.START_FOREGROUND"
        const val ACTION_UPDATE = "com.example.action.UPDATE_FOREGROUND"
        const val ACTION_STOP = "com.example.action.STOP_FOREGROUND"
        const val ACTION_PAUSE = "com.example.action.PAUSE_FOREGROUND"
        const val ACTION_RESUME = "com.example.action.RESUME_FOREGROUND"
        const val ACTION_CANCEL = "com.example.action.CANCEL_FOREGROUND"

        const val EXTRA_DOWNLOAD_ID = "extra_download_id"
        const val EXTRA_FILE_NAME = "extra_file_name"
        const val EXTRA_PROGRESS = "extra_progress"
        const val EXTRA_SPEED = "extra_speed"
        const val EXTRA_ETA = "extra_eta"
        const val EXTRA_ACTIVE_COUNT = "extra_active_count"
        const val EXTRA_IS_PAUSED = "extra_is_paused"

        /**
         * Overload maintaining backward-compatibility with earlier call sites.
         */
        fun startOrUpdate(
            context: Context,
            fileName: String,
            progress: Int,
            speedBytesPerSec: Long,
            activeCount: Int
        ) {
            startOrUpdate(
                context = context,
                downloadId = null,
                fileName = fileName,
                progress = progress,
                speedBytesPerSec = speedBytesPerSec,
                etaString = null,
                activeCount = activeCount,
                isPaused = false
            )
        }

        /**
         * Full parameter startOrUpdate method passing ETA and action download ID.
         */
        fun startOrUpdate(
            context: Context,
            downloadId: String?,
            fileName: String,
            progress: Int,
            speedBytesPerSec: Long,
            etaString: String? = null,
            activeCount: Int = 1,
            isPaused: Boolean = false
        ) {
            val intent = Intent(context, DownloadForegroundService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_DOWNLOAD_ID, downloadId)
                putExtra(EXTRA_FILE_NAME, fileName)
                putExtra(EXTRA_PROGRESS, progress)
                putExtra(EXTRA_SPEED, speedBytesPerSec)
                putExtra(EXTRA_ETA, etaString)
                putExtra(EXTRA_ACTIVE_COUNT, activeCount)
                putExtra(EXTRA_IS_PAUSED, isPaused)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, DownloadForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private var isForegroundActive = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        DownloadNotificationBuilder.createNotificationChannels(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        when (intent.action) {
            ACTION_STOP -> {
                releaseLocks()
                stopForegroundService()
                return START_NOT_STICKY
            }

            ACTION_PAUSE -> {
                val downloadId = intent.getStringExtra(EXTRA_DOWNLOAD_ID)
                if (downloadId != null) {
                    TurboDownloadManager.getEngine(this).pauseDownload(downloadId)
                } else {
                    TurboDownloadManager.getQueueManager(this).pauseQueue()
                }
                return START_STICKY
            }

            ACTION_RESUME -> {
                val downloadId = intent.getStringExtra(EXTRA_DOWNLOAD_ID)
                if (downloadId != null) {
                    TurboDownloadManager.getEngine(this).resumeDownload(downloadId)
                } else {
                    TurboDownloadManager.getQueueManager(this).resumeQueue()
                }
                return START_STICKY
            }

            ACTION_CANCEL -> {
                val downloadId = intent.getStringExtra(EXTRA_DOWNLOAD_ID)
                if (downloadId != null) {
                    TurboDownloadManager.getEngine(this).cancelDownload(downloadId)
                }
                return START_STICKY
            }

            ACTION_START, ACTION_UPDATE -> {
                acquireLocks()

                val downloadId = intent.getStringExtra(EXTRA_DOWNLOAD_ID)
                val fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: "Dosya indiriliyor"
                val progress = intent.getIntExtra(EXTRA_PROGRESS, 0).coerceIn(0, 100)
                val speed = intent.getLongExtra(EXTRA_SPEED, 0L)
                val eta = intent.getStringExtra(EXTRA_ETA)
                val activeCount = intent.getIntExtra(EXTRA_ACTIVE_COUNT, 1).coerceAtLeast(1)
                val isPaused = intent.getBooleanExtra(EXTRA_IS_PAUSED, false)

                val notification = DownloadNotificationBuilder.buildForegroundNotification(
                    context = this,
                    downloadId = downloadId,
                    fileName = fileName,
                    progress = progress,
                    speedBytesPerSec = speed,
                    etaString = eta,
                    activeCount = activeCount,
                    isPaused = isPaused
                )

                if (!isForegroundActive) {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            startForeground(
                                NOTIFICATION_ID,
                                notification,
                                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                            )
                        } else {
                            startForeground(NOTIFICATION_ID, notification)
                        }
                        isForegroundActive = true
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                } else {
                    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    try {
                        manager?.notify(NOTIFICATION_ID, notification)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }

        return START_STICKY
    }

    private fun acquireLocks() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = powerManager?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "Zireael:DownloadWakeLock"
                )?.apply {
                    setReferenceCounted(false)
                    acquire(10 * 60 * 1000L) // Safety 10-minute timeout
                }
            } else if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(10 * 60 * 1000L)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            if (wifiLock == null) {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                wifiLock = wifiManager?.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "Zireael:DownloadWifiLock"
                )?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            } else if (wifiLock?.isHeld == false) {
                wifiLock?.acquire()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopForegroundService() {
        isForegroundActive = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseLocks()
        isForegroundActive = false
    }
}

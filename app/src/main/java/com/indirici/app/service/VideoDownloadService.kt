package com.indirici.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.indirici.app.domain.model.VideoDownloadTask
import com.indirici.app.engine.FfmpegHlsEngine
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * WebView Sniffer'dan yakalanan HLS (.m3u8) akışlarını arka planda FFmpegKit.executeAsync
 * ile indiren, Scoped Storage üzerinden geçici klasöre yazıp tamamlandığında MediaStore ile
 * cihazın Movies / Download klasörüne ve Galeri'ye aktaran ForegroundService.
 *
 * Güvenlik Kuralı:
 * Çerezler (Cookie) ve User-Agent diske/Intent loglarına yazılmaması için yalnızca RAM'deki
 * geçici haritada tutulur ve indirme bittiğinde/hata aldığında derhal silinir.
 */
class VideoDownloadService : Service() {

    override fun onCreate() {
        super.onCreate()
        createChannelIfNeeded()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val sessionIdKey = intent?.getIntExtra(EXTRA_EPHEMERAL_TASK_ID, -1) ?: -1
        val task = ephemeralTasks.remove(sessionIdKey)

        if (task == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val ongoingNotification = buildProgressNotification("Video indiriliyor...")
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    ONGOING_NOTIFICATION_ID,
                    ongoingNotification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(ONGOING_NOTIFICATION_ID, ongoingNotification)
            }
        }

        executeFfmpegDownload(task, startId)
        return START_NOT_STICKY
    }

    private fun executeFfmpegDownload(task: VideoDownloadTask, startId: Int) {
        // 1. Geçici videoyu önce context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) içine yaz
        val tempDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: cacheDir
        if (!tempDir.exists()) tempDir.mkdirs()

        val timeStamp = System.currentTimeMillis()
        val tempOutputFile = File(tempDir, "temp_video_$timeStamp.mp4")

        val arguments: Array<String> = FfmpegHlsEngine.buildFfmpegArguments(
            m3u8Url = task.url,
            outputPath = tempOutputFile.absolutePath,
            userAgent = task.userAgent,
            referer = task.referer,
            cookie = task.cookie
        )

        FFmpegKit.executeWithArgumentsAsync(
            arguments,
            { session ->
                val returnCode = session.returnCode
                if (ReturnCode.isSuccess(returnCode) && tempOutputFile.exists()) {
                    try {
                        moveTempVideoToGallery(tempOutputFile, "video_$timeStamp.mp4")
                        showResultNotification(
                            title = "Tamamlandı",
                            message = "Video başarıyla indirildi ve galeriye kaydedildi."
                        )
                    } catch (e: Exception) {
                        showResultNotification(
                            title = "Kaydetme Hatası",
                            message = e.message ?: "Video galeriye taşınamadı."
                        )
                    } finally {
                        if (tempOutputFile.exists()) tempOutputFile.delete()
                    }
                } else {
                    val errorLogs = session.allLogsAsString
                    if (tempOutputFile.exists()) tempOutputFile.delete()

                    val userMessage = "Video indirilemedi (Kod: $returnCode)"

                    showResultNotification(
                        title = "İndirme Başarısız",
                        message = userMessage
                    )
                }

                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            },
            { _ ->
                // Güvenlik kuralı gereği hassas başlık/çerez sızıntısını önlemek için ham loglar dışarı yazılmaz
            },
            { statistics ->
                val sizeMb = (statistics?.size ?: 0L) / (1024.0 * 1024.0)
                if (sizeMb > 0.1) {
                    val progressText = String.format("Video indiriliyor... (%.1f MB)", sizeMb)
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.notify(ONGOING_NOTIFICATION_ID, buildProgressNotification(progressText))
                }
            }
        )
    }

    /**
     * İndirme tamamlandığında dosyayı MediaStore API kullanarak cihazın genel Movies/Download
     * klasörüne taşır ve kullanıcının galerisinde görünmesini sağlar.
     */
    private fun moveTempVideoToGallery(tempFile: File, displayFileName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayFileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }

            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val itemUri = contentResolver.insert(collection, values)
                ?: throw IllegalStateException("MediaStore kaydı oluşturulamadı")

            contentResolver.openOutputStream(itemUri)?.use { output ->
                tempFile.inputStream().use { input ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
            }

            val completeValues = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }
            contentResolver.update(itemUri, completeValues, null, null)
        } else {
            @Suppress("DEPRECATION")
            val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            if (!moviesDir.exists()) moviesDir.mkdirs()
            val targetFile = File(moviesDir, displayFileName)

            tempFile.inputStream().use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
            }

            MediaScannerConnection.scanFile(
                this,
                arrayOf(targetFile.absolutePath),
                arrayOf("video/mp4"),
                null
            )
        }
    }

    private fun buildProgressNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Video indiriliyor...")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .build()
    }

    private fun showResultNotification(title: String, message: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(true)
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(RESULT_NOTIFICATION_ID, notification)
    }

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Video İndirme Servisi",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "video_download_service_channel"
        private const val ONGOING_NOTIFICATION_ID = 2001
        private const val RESULT_NOTIFICATION_ID = 2002
        private const val EXTRA_EPHEMERAL_TASK_ID = "extra_ephemeral_task_id"

        private val idCounter = AtomicInteger(1)
        private val ephemeralTasks = ConcurrentHashMap<Int, VideoDownloadTask>()

        fun start(context: Context, task: VideoDownloadTask) {
            val taskId = idCounter.getAndIncrement()
            ephemeralTasks[taskId] = task
            val intent = Intent(context, VideoDownloadService::class.java).apply {
                putExtra(EXTRA_EPHEMERAL_TASK_ID, taskId)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}

package com.example

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.example.data.engine.DownloadNotificationBuilder
import com.example.data.engine.TurboDownloadManager
import com.example.data.service.DownloadForegroundService
import com.example.data.worker.DownloadWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackgroundDownloadServiceTest {

    @Test
    fun `notification channels are registered properly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        DownloadNotificationBuilder.createNotificationChannels(context)

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val foregroundChannel = manager.getNotificationChannel(DownloadNotificationBuilder.FOREGROUND_CHANNEL_ID)
        val completeChannel = manager.getNotificationChannel(DownloadNotificationBuilder.COMPLETE_CHANNEL_ID)

        assertNotNull(foregroundChannel)
        assertEquals(DownloadNotificationBuilder.FOREGROUND_CHANNEL_NAME, foregroundChannel.name)
        assertEquals(NotificationManager.IMPORTANCE_LOW, foregroundChannel.importance)

        assertNotNull(completeChannel)
        assertEquals(DownloadNotificationBuilder.COMPLETE_CHANNEL_NAME, completeChannel.name)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, completeChannel.importance)
    }

    @Test
    fun `foreground notification builder formats percentage speed and eta accurately`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val notification = DownloadNotificationBuilder.buildForegroundNotification(
            context = context,
            downloadId = "test-download-123",
            fileName = "ubuntu-server.iso",
            progress = 65,
            speedBytesPerSec = 15 * 1024 * 1024L, // 15 MB/s
            etaString = "01:45",
            activeCount = 1,
            isPaused = false
        )

        assertNotNull(notification)
        val extras = NotificationCompat.getExtras(notification)
        val title = extras?.getString(Notification.EXTRA_TITLE)
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val subText = extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()

        assertEquals("⚡ Zireael: ubuntu-server.iso", title)
        assertTrue(text?.contains("%65") == true)
        assertTrue(text?.contains("MB/s") == true)
        assertTrue(text?.contains("Kalan: 01:45") == true)
        assertEquals("%65", subText)
        assertTrue((notification.flags and Notification.FLAG_ONGOING_EVENT) != 0)

        // Verify action buttons (Pause and Cancel)
        val actions = notification.actions
        assertNotNull(actions)
        assertTrue(actions.size >= 2)
        assertEquals("Durdur", actions[0].title.toString())
        assertEquals("İptal", actions[1].title.toString())
    }

    @Test
    fun `multiple active downloads notification displays aggregate count`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val notification = DownloadNotificationBuilder.buildForegroundNotification(
            context = context,
            downloadId = null,
            fileName = "batch",
            progress = 42,
            speedBytesPerSec = 8 * 1024 * 1024L,
            etaString = "03:12",
            activeCount = 3,
            isPaused = false
        )

        val extras = NotificationCompat.getExtras(notification)
        val title = extras?.getString(Notification.EXTRA_TITLE)
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()

        assertEquals("⚡ Zireael: 3 Dosya İndiriliyor", title)
        assertTrue(text?.contains("3 aktif dosya") == true)
        assertTrue(text?.contains("%42") == true)
    }

    @Test
    fun `paused notification displays Resume action button`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val notification = DownloadNotificationBuilder.buildForegroundNotification(
            context = context,
            downloadId = "test-paused-id",
            fileName = "archive.zip",
            progress = 50,
            speedBytesPerSec = 0L,
            etaString = null,
            activeCount = 1,
            isPaused = true
        )

        val actions = notification.actions
        assertNotNull(actions)
        assertTrue(actions.size >= 2)
        assertEquals("Devam Et", actions[0].title.toString())
        assertEquals("İptal", actions[1].title.toString())
    }

    @Test
    fun `completion and failure notification builders create valid dismissable notifications`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val completion = DownloadNotificationBuilder.buildCompletionNotification(
            context = context,
            downloadId = "comp-1",
            fileName = "archive.zip",
            totalBytes = 100 * 1024 * 1024L
        )
        val compExtras = NotificationCompat.getExtras(completion)
        assertEquals("İndirme Tamamlandı", compExtras?.getString(Notification.EXTRA_TITLE))
        assertTrue((completion.flags and Notification.FLAG_AUTO_CANCEL) != 0)

        val failure = DownloadNotificationBuilder.buildFailureNotification(
            context = context,
            downloadId = "fail-1",
            fileName = "broken.zip",
            errorMessage = "HTTP 404 Not Found"
        )
        val failExtras = NotificationCompat.getExtras(failure)
        assertEquals("İndirme Başarısız", failExtras?.getString(Notification.EXTRA_TITLE))
        assertTrue(failExtras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.contains("404") == true)
    }

    @Test
    fun `turbo download manager provides persistent singleton instances`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine1 = TurboDownloadManager.getEngine(context)
        val engine2 = TurboDownloadManager.getEngine(context)
        val queue1 = TurboDownloadManager.getQueueManager(context)
        val queue2 = TurboDownloadManager.getQueueManager(context)

        assertNotNull(engine1)
        assertNotNull(queue1)
        assertTrue(engine1 === engine2)
        assertTrue(queue1 === queue2)
    }

    @Test
    fun `download worker schedule constants and intents are properly configured`() {
        assertEquals("turbo_idm_queue_resumption_work", DownloadWorker.UNIQUE_QUEUE_WORK_NAME)
        assertEquals("key_download_id", DownloadWorker.KEY_DOWNLOAD_ID)

        val context = ApplicationProvider.getApplicationContext<Context>()
        try {
            val config = androidx.work.Configuration.Builder().build()
            androidx.work.WorkManager.initialize(context, config)
        } catch (e: Exception) {
            // Already initialized
        }
        // Schedule should succeed without exception
        DownloadWorker.scheduleResumeQueue(context, requireUnmeteredWifi = false)
    }
}

package com.example.data.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.data.engine.TurboDownloadManager

/**
 * DownloadWorker:
 * CoroutineWorker bridging WorkManager with TurboDownloadEngine and DownloadQueueManager.
 * Ensures interrupted or queued background downloads resume when network connectivity
 * is restored or unmetered Wi-Fi constraints are met.
 */
class DownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val downloadId = inputData.getString(KEY_DOWNLOAD_ID)
            val queueManager = TurboDownloadManager.getQueueManager(applicationContext)
            val engine = TurboDownloadManager.getEngine(applicationContext)

            if (!downloadId.isNullOrBlank()) {
                engine.startDownload(downloadId)
            } else {
                queueManager.processQueue()
            }

            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    companion object {
        const val KEY_DOWNLOAD_ID = "key_download_id"
        const val UNIQUE_QUEUE_WORK_NAME = "turbo_idm_queue_resumption_work"

        /**
         * Enqueues a specific download with optional Wi-Fi only constraint.
         */
        fun enqueueDownload(
            context: Context,
            downloadId: String,
            requireUnmeteredWifi: Boolean = false
        ) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (requireUnmeteredWifi) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .build()

            val inputData = Data.Builder()
                .putString(KEY_DOWNLOAD_ID, downloadId)
                .build()

            val workRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setConstraints(constraints)
                .setInputData(inputData)
                .addTag("download_$downloadId")
                .build()

            WorkManager.getInstance(context).enqueue(workRequest)
        }

        /**
         * Schedules queue processing when network connectivity constraints are fulfilled.
         */
        fun scheduleResumeQueue(
            context: Context,
            requireUnmeteredWifi: Boolean = false
        ) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (requireUnmeteredWifi) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .build()

            val workRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_QUEUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                workRequest
            )
        }
    }
}

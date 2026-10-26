package com.indirici.app.service

import android.content.Context
import com.indirici.app.data.local.DownloadDao
import com.indirici.app.data.local.DownloadTaskEntity
import com.indirici.app.data.settings.SettingsRepository
import com.indirici.app.domain.engine.DownloadEngine
import com.indirici.app.domain.model.AppSettings
import com.indirici.app.domain.model.DownloadStatus
import com.indirici.app.domain.model.EngineType
import com.indirici.app.domain.model.UrlMetadata
import com.indirici.app.domain.security.EphemeralSessionStore
import com.indirici.app.engine.EngineRouter
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.concurrent.atomic.AtomicInteger

class DownloadQueueCoordinatorTest {

    /** Süresiz çalışan; iptal edilince yavaş temizlik yapan sahte motor. */
    private class SlowCleanupEngine : DownloadEngine {
        override val engineType = EngineType.DIRECT
        val starts = AtomicInteger(0)
        val running = AtomicInteger(0)
        val maxRunning = AtomicInteger(0)

        override suspend fun analyze(url: String, downloadId: Long?): UrlMetadata =
            throw UnsupportedOperationException()

        override suspend fun executeDownload(
            taskId: Long,
            onProgress: suspend (Long, Long, Long) -> Unit
        ): String {
            starts.incrementAndGet()
            val now = running.incrementAndGet()
            maxRunning.updateAndGet { maxOf(it, now) }
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { delay(400) }
                running.decrementAndGet()
            }
        }
    }

    @Test
    fun `pause then immediate resume never runs the same task twice concurrently`() = runBlocking {
        val statusRef = java.util.concurrent.atomic.AtomicReference("IDLE")
        val dao: DownloadDao = mock()
        val task = DownloadTaskEntity(
            id = 1L, url = "https://a.example/f.zip", fileName = "f.zip", mimeType = null,
            totalBytes = 0, downloadedBytes = 0, speedBytesPerSec = 0, chunkCount = 4,
            supportsRange = false, status = "QUEUED", engineType = EngineType.DIRECT.name
        )
        whenever(dao.getTasksByStatus(any())).doAnswer {
            if (it.getArgument<String>(0) == statusRef.get()) listOf(task) else emptyList()
        }
        whenever(dao.getTaskById(any())).doAnswer { task.copy(status = statusRef.get()) }
        whenever(dao.updateTaskStatus(any(), any(), anyOrNull())).doAnswer {
            statusRef.set(it.getArgument<String>(1))
            Unit
        }

        val settings: SettingsRepository = mock()
        whenever(settings.settingsFlow).thenReturn(flowOf(AppSettings()))
        val engine = SlowCleanupEngine()
        val router = EngineRouter(engine, engine, engine)
        val coordinator = DownloadQueueCoordinator(
            mock<Context>(), dao, settings, router, EphemeralSessionStore()
        )

        statusRef.set(DownloadStatus.QUEUED.name)
        coordinator.pumpQueue()
        waitUntil { engine.starts.get() == 1 }

        coordinator.pauseDownload(1L)
        waitUntil { statusRef.get() == DownloadStatus.PAUSED.name }
        coordinator.resumeDownload(1L)

        waitUntil(3000) { engine.starts.get() >= 2 }
        assertTrue("task must be restarted after resume", engine.starts.get() >= 2)
        assertEquals("same task ran concurrently", 1, engine.maxRunning.get())
    }

    private suspend fun waitUntil(timeoutMs: Long = 2000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < end) delay(10)
    }
}

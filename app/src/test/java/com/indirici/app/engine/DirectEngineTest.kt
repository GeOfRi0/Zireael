package com.indirici.app.engine

import android.content.Context
import com.indirici.app.data.local.DownloadChunkEntity
import com.indirici.app.data.local.DownloadDao
import com.indirici.app.data.local.DownloadTaskEntity
import com.indirici.app.data.storage.DownloadsFileSaver
import com.indirici.app.domain.security.EphemeralSessionStore
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.io.File
import java.io.IOException

class DirectEngineTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    @Test
    fun `executeDownload throws IOException and does not mark chunk completed when server cuts off stream prematurely`() = runBlocking {
        val taskId = 1L
        val totalBytes = 10_000L
        val tempPartFile = File(tempDir.root, "test_download.part")

        val task = DownloadTaskEntity(
            id = taskId,
            url = "https://example.com/largefile.bin",
            fileName = "largefile.bin",
            mimeType = "application/octet-stream",
            totalBytes = totalBytes,
            downloadedBytes = 0L,
            speedBytesPerSec = 0L,
            chunkCount = 1,
            supportsRange = true,
            status = "DOWNLOADING",
            engineType = "DIRECT",
            tempFilePath = tempPartFile.absolutePath
        )

        val chunkEntity = DownloadChunkEntity(
            id = 10,
            taskId = taskId,
            chunkIndex = 0,
            startByte = 0L,
            endByte = 9_999L, // 10,000 bytes expected
            downloadedBytes = 0L,
            isCompleted = false
        )

        var chunkCompleted = false
        var savedDownloadedBytes = 0L

        val mockDao: DownloadDao = mock {
            onBlocking { getTaskById(taskId) } doReturn task
            onBlocking { getChunksForTask(taskId) } doReturn listOf(chunkEntity)
            onBlocking { updateChunkProgress(any(), any(), any(), any()) } doAnswer { invocation ->
                savedDownloadedBytes = invocation.getArgument(2)
                chunkCompleted = invocation.getArgument(3)
                Unit
            }
        }

        val mockContext: Context = mock()
        val mockFileSaver: DownloadsFileSaver = mock {
            on { getScopedTempDownloadsDir(any()) } doReturn tempDir.root
        }
        val ephemeralStore = EphemeralSessionStore()

        // OkHttp interceptor that returns 206 Partial Content, but body has only 500 bytes (cut off)
        val testClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val request = chain.request()
                // Return truncated 500 bytes response
                val fakeBody = ByteArray(500) { 1.toByte() }
                    .toResponseBody("application/octet-stream".toMediaType())

                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(206)
                    .message("Partial Content")
                    .header("Content-Range", "bytes 0-9999/10000")
                    .body(fakeBody)
                    .build()
            })
            .build()

        val engine = DirectEngine(
            context = mockContext,
            downloadDao = mockDao,
            fileSaver = mockFileSaver,
            ephemeralSessionStore = ephemeralStore,
            okHttpClient = testClient
        )

        try {
            engine.executeDownload(taskId) { _, _, _ -> }
            fail("Expected IOException due to premature EOF / incomplete chunk, but download succeeded!")
        } catch (e: IOException) {
            assertTrue("Expected error to mention incomplete or missing chunk bytes, got: ${e.message}",
                e.message?.contains("eksik", ignoreCase = true) == true ||
                e.message?.contains("incomplete", ignoreCase = true) == true
            )
        }

        assertFalse("Chunk must NOT be marked as completed when server closes stream prematurely", chunkCompleted)
    }

    @Test
    fun `executeDownload redownloads when part file vanished but chunks claim completion`() = runBlocking {
        val taskId = 2L
        val total = 1_000L
        val missingPart = File(tempDir.root, "vanished.part") // hiç oluşturulmuyor
        val task = DownloadTaskEntity(
            id = taskId, url = "https://example.com/f.bin", fileName = "f.bin",
            mimeType = "application/octet-stream", totalBytes = total, downloadedBytes = total,
            speedBytesPerSec = 0L, chunkCount = 1, supportsRange = true,
            status = "DOWNLOADING", engineType = "DIRECT", tempFilePath = missingPart.absolutePath
        )
        var chunks = listOf(
            DownloadChunkEntity(
                id = 1, taskId = taskId, chunkIndex = 0, startByte = 0L, endByte = total - 1,
                downloadedBytes = total, isCompleted = true
            )
        )
        var savedBytes: ByteArray? = null

        val dao: DownloadDao = mock {
            onBlocking { getTaskById(taskId) } doReturn task
            onBlocking { getChunksForTask(taskId) } doAnswer { chunks }
            onBlocking { deleteChunksForTask(taskId) } doAnswer { chunks = emptyList(); Unit }
            onBlocking { insertChunks(any()) } doAnswer { inv ->
                chunks = inv.getArgument<List<DownloadChunkEntity>>(0).mapIndexed { i, c -> c.copy(id = 100L + i) }
                Unit
            }
            onBlocking { updateChunkProgress(any(), any(), any(), any()) } doAnswer { inv ->
                val idx: Int = inv.getArgument(1)
                chunks = chunks.map {
                    if (it.chunkIndex == idx) it.copy(
                        downloadedBytes = inv.getArgument(2), isCompleted = inv.getArgument(3)
                    ) else it
                }
                Unit
            }
        }
        val saver: DownloadsFileSaver = mock {
            on { getScopedTempDownloadsDir(any()) } doReturn tempDir.root
            onBlocking { saveToPublicDownloads(any(), any(), anyOrNull(), any()) } doAnswer { inv ->
                savedBytes = inv.getArgument<File>(0).readBytes()
                "content://saved"
            }
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                Response.Builder()
                    .request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(206).message("Partial Content")
                    .header("Content-Range", "bytes 0-999/1000")
                    .body(ByteArray(1000) { 7 }.toResponseBody("application/octet-stream".toMediaType()))
                    .build()
            })
            .build()

        val engine = DirectEngine(mock<Context>(), dao, saver, EphemeralSessionStore(), client)
        engine.executeDownload(taskId) { _, _, _ -> }

        val bytes = savedBytes ?: fail("dosya kaydedilmedi").let { return@runBlocking }
        assertTrue(
            "Kayıp .part dosyası sıfırlarla doldurulup başarılı kaydedildi (veri bozulması)",
            bytes.size == 1000 && bytes.all { it == 7.toByte() }
        )
    }
}

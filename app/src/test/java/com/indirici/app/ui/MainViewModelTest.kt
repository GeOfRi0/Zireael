package com.indirici.app.ui

import com.indirici.app.data.settings.SettingsRepository
import com.indirici.app.domain.engine.DownloadEngine
import com.indirici.app.domain.model.AppSettings
import com.indirici.app.domain.model.DownloadItem
import com.indirici.app.domain.model.DownloadStatus
import com.indirici.app.domain.model.EngineType
import com.indirici.app.domain.model.UrlMetadata
import com.indirici.app.engine.EngineRouter
import com.indirici.app.engine.MediaEngine
import com.indirici.app.engine.YtDlpUpdateResult
import com.indirici.app.service.DownloadQueueCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    private val coordinator: DownloadQueueCoordinator = mock()
    private val settingsRepository: SettingsRepository = mock()
    private val router: EngineRouter = mock()
    private val directEngine: DownloadEngine = mock()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        whenever(coordinator.downloadsFlow).thenReturn(flowOf(emptyList()))
        whenever(settingsRepository.settingsFlow).thenReturn(flowOf(AppSettings()))
        whenever(directEngine.engineType).thenReturn(EngineType.DIRECT)
        whenever(router.detectEngineForUrl(any())).thenReturn(directEngine)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `startDownload does not reuse metadata analyzed for a different URL`() {
        val oldUrl = "https://a.example/old.zip"
        val newUrl = "https://b.example/new.zip"
        kotlinx.coroutines.runBlocking {
            whenever(coordinator.analyzeUrl(oldUrl)).thenReturn(
                UrlMetadata(oldUrl, oldUrl, "old.zip", null, 10L, true, EngineType.DIRECT)
            )
        }
        val vm = MainViewModel(coordinator, settingsRepository, router)

        vm.analyzeUrl(oldUrl)
        vm.startDownload(newUrl, null, 4) {}

        val urlCaptor = argumentCaptor<String>()
        kotlinx.coroutines.runBlocking {
            verify(coordinator).enqueueDownload(
                urlCaptor.capture(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), any(), anyOrNull(), anyOrNull(), anyOrNull()
            )
        }
        org.junit.Assert.assertEquals(newUrl, urlCaptor.firstValue)
    }

    @Test
    fun `slow older analysis does not overwrite result of newer analysis`() {
        val urlA = "https://a.example/a.zip"
        val urlB = "https://b.example/b.zip"
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val metaA = UrlMetadata(urlA, urlA, "a.zip", null, 1L, true, EngineType.DIRECT)
        val metaB = UrlMetadata(urlB, urlB, "b.zip", null, 2L, true, EngineType.DIRECT)
        kotlinx.coroutines.runBlocking {
            whenever(coordinator.analyzeUrl(urlA)).doSuspendableAnswer {
                gate.await()
                metaA
            }
            whenever(coordinator.analyzeUrl(urlB)).thenReturn(metaB)
        }
        val vm = MainViewModel(coordinator, settingsRepository, router)

        // A askıda kalır; B hemen biter; sonra A bitirilir.
        vm.analyzeUrl(urlA)
        vm.analyzeUrl(urlB)
        gate.complete(Unit)

        org.junit.Assert.assertEquals(urlB, vm.addDialogState.value.metadata?.originalUrl)
    }

    @Test
    fun `sniffed download failure is reported to the user instead of swallowed`() {
        val task = com.indirici.app.domain.model.VideoDownloadTask(
            url = "https://a.example/v.m3u8", referer = "", userAgent = "", cookie = ""
        )
        kotlinx.coroutines.runBlocking {
            whenever(coordinator.enqueueSniffedVideoTask(task))
                .thenAnswer { throw java.io.IOException("disk dolu") }
        }
        val vm = MainViewModel(coordinator, settingsRepository, router)
        val events = mutableListOf<String>()
        val collector = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined).launch {
            vm.errorEvents.collect { events.add(it) }
        }

        vm.startSniffedVideoDownload(task)
        collector.cancel()

        org.junit.Assert.assertEquals(1, events.size)
        org.junit.Assert.assertTrue(events[0].contains("disk dolu"))
    }

    @Test
    fun `updateYtDlp fails immediately without invoking mediaEngine when download is active`() {
        val activeItem = DownloadItem(
            id = 1L,
            url = "https://example.com/file.mp4",
            fileName = "file.mp4",
            mimeType = "video/mp4",
            totalBytes = 1000L,
            downloadedBytes = 100L,
            speedBytesPerSec = 50L,
            chunkCount = 4,
            supportsRange = true,
            status = DownloadStatus.DOWNLOADING,
            engineType = EngineType.DIRECT,
            createdAt = System.currentTimeMillis()
        )
        val mediaEngine: MediaEngine = mock()
        whenever(router.mediaEngine).thenReturn(mediaEngine)
        whenever(coordinator.downloadsFlow).thenReturn(flowOf(listOf(activeItem)))

        val vm = MainViewModel(coordinator, settingsRepository, router)

        // WhileSubscribed StateFlow'u tetiklemek için bir collector başlatılır
        val collectJob = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined).launch {
            vm.downloads.collect {}
        }

        try {
            vm.updateYtDlp()

            val state = vm.ytDlpUpdateState.value
            org.junit.Assert.assertTrue(state is YtDlpUpdateUiState.Error)
            val errorState = state as YtDlpUpdateUiState.Error
            org.junit.Assert.assertTrue(errorState.message.contains("Aktif indirme"))

            kotlinx.coroutines.runBlocking {
                verify(mediaEngine, never()).updateYtDlp()
            }
        } finally {
            collectJob.cancel()
        }
    }

    @Test
    fun `updateYtDlp delegates to mediaEngine and updates state on successful update`() {
        val mediaEngine: MediaEngine = mock()
        whenever(router.mediaEngine).thenReturn(mediaEngine)
        whenever(coordinator.downloadsFlow).thenReturn(flowOf(emptyList()))
        kotlinx.coroutines.runBlocking {
            whenever(mediaEngine.getYtDlpVersion()).thenReturn("2024.09.27")
            whenever(mediaEngine.updateYtDlp()).thenReturn(YtDlpUpdateResult.Updated("2024.11.01"))
        }

        val vm = MainViewModel(coordinator, settingsRepository, router)
        val events = mutableListOf<String>()
        val collector = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined).launch {
            vm.errorEvents.collect { events.add(it) }
        }

        vm.updateYtDlp()
        collector.cancel()

        val state = vm.ytDlpUpdateState.value
        org.junit.Assert.assertTrue(state is YtDlpUpdateUiState.Success)
        val successState = state as YtDlpUpdateUiState.Success
        org.junit.Assert.assertTrue(successState.message.contains("2024.11.01"))
        org.junit.Assert.assertEquals("2024.11.01", vm.ytDlpVersion.value)
        org.junit.Assert.assertTrue(events.any { it.contains("2024.11.01") })
    }

    @Test
    fun `updateYtDlp informs user when already up to date`() {
        val mediaEngine: MediaEngine = mock()
        whenever(router.mediaEngine).thenReturn(mediaEngine)
        whenever(coordinator.downloadsFlow).thenReturn(flowOf(emptyList()))
        kotlinx.coroutines.runBlocking {
            whenever(mediaEngine.getYtDlpVersion()).thenReturn("2024.09.27")
            whenever(mediaEngine.updateYtDlp()).thenReturn(YtDlpUpdateResult.AlreadyUpToDate("2024.09.27"))
        }

        val vm = MainViewModel(coordinator, settingsRepository, router)
        vm.updateYtDlp()

        val state = vm.ytDlpUpdateState.value
        org.junit.Assert.assertTrue(state is YtDlpUpdateUiState.Success)
        val successState = state as YtDlpUpdateUiState.Success
        org.junit.Assert.assertTrue(successState.message.contains("zaten en güncel"))
        org.junit.Assert.assertEquals("2024.09.27", vm.ytDlpVersion.value)
    }

    @Test
    fun `updateYtDlp sets error state when update fails`() {
        val mediaEngine: MediaEngine = mock()
        whenever(router.mediaEngine).thenReturn(mediaEngine)
        whenever(coordinator.downloadsFlow).thenReturn(flowOf(emptyList()))
        val expectedError = "Güncelleme sunucusuna bağlanılamadı. İnternet bağlantınızı kontrol edin."
        kotlinx.coroutines.runBlocking {
            whenever(mediaEngine.getYtDlpVersion()).thenReturn("2024.09.27")
            whenever(mediaEngine.updateYtDlp()).thenReturn(YtDlpUpdateResult.Error(expectedError))
        }

        val vm = MainViewModel(coordinator, settingsRepository, router)
        val events = mutableListOf<String>()
        val collector = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined).launch {
            vm.errorEvents.collect { events.add(it) }
        }

        vm.updateYtDlp()
        collector.cancel()

        val state = vm.ytDlpUpdateState.value
        org.junit.Assert.assertTrue(state is YtDlpUpdateUiState.Error)
        val errorState = state as YtDlpUpdateUiState.Error
        org.junit.Assert.assertEquals(expectedError, errorState.message)
        org.junit.Assert.assertTrue(events.contains(expectedError))
    }
}

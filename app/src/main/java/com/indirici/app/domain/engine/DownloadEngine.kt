package com.indirici.app.domain.engine

import com.indirici.app.domain.model.EngineType
import com.indirici.app.domain.model.UrlMetadata

/**
 * Ortak İndirme Motoru Arayüzü (V1 DirectEngine ve V2 MediaEngine için).
 */
interface DownloadEngine {
    val engineType: EngineType

    /**
     * Verilen URL'yi kontrol eder, DRM varsa [com.indirici.app.domain.exception.DrmProtectedException] fırlatır.
     * Dosya adı, boyut ve çoklu parça (Range) desteğini döndürür.
     */
    suspend fun analyze(url: String, downloadId: Long? = null): UrlMetadata

    /**
     * İndirmeyi yürütür (veya kaldığı yerden devam ettirir).
     * Tamamlandığında telefonun İndirilenler (Downloads) klasöründeki nihai URI/dosya yolunu döndürür.
     */
    suspend fun executeDownload(
        taskId: Long,
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long, speedBytesPerSec: Long) -> Unit
    ): String
}

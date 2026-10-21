package com.indirici.app.domain.model

/**
 * Kullanıcı tarafından değiştirilebilen uygulama ayarları.
 */
data class AppSettings(
    val defaultChunkCount: Int = DEFAULT_CHUNK_COUNT,
    val maxConcurrentDownloads: Int = DEFAULT_MAX_CONCURRENT
) {
    companion object {
        const val DEFAULT_CHUNK_COUNT = 4
        const val MIN_CHUNK_COUNT = 1
        const val MAX_CHUNK_COUNT = 16

        const val DEFAULT_MAX_CONCURRENT = 2
        const val MIN_CONCURRENT = 1
        const val MAX_CONCURRENT = 5
    }
}

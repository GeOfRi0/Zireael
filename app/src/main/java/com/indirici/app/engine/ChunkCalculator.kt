package com.indirici.app.engine

data class ByteRangeSlice(
    val chunkIndex: Int,
    val startByte: Long,
    val endByte: Long // dahil (inclusive), bilinmeyen boyut için -1L
)

object ChunkCalculator {

    /**
     * Dosya boyutuna ve parça desteğine göre byte aralıklarını hesaplar.
     * Sunucu Range desteklemiyorsa veya dosya boyutu bilinmiyorsa (<= 0) tek parça döndürülür.
     */
    fun calculateSlices(
        totalBytes: Long,
        requestedChunks: Int,
        supportsRange: Boolean
    ): List<ByteRangeSlice> {
        val safeChunkCount = requestedChunks.coerceIn(1, 16)
        if (!supportsRange || totalBytes <= 0L || safeChunkCount == 1 || totalBytes < 64 * 1024L) {
            val endByte = if (totalBytes > 0L) totalBytes - 1L else -1L
            return listOf(ByteRangeSlice(chunkIndex = 0, startByte = 0L, endByte = endByte))
        }

        val chunkSize = totalBytes / safeChunkCount
        val slices = ArrayList<ByteRangeSlice>(safeChunkCount)
        for (i in 0 until safeChunkCount) {
            val start = i * chunkSize
            val end = if (i == safeChunkCount - 1) {
                totalBytes - 1L
            } else {
                (i + 1) * chunkSize - 1L
            }
            slices.add(ByteRangeSlice(chunkIndex = i, startByte = start, endByte = end))
        }
        return slices
    }
}

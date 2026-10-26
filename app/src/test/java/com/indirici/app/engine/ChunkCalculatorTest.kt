package com.indirici.app.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class ChunkCalculatorTest {

    @Test
    fun `calculateSlices splits file into 4 contiguous byte ranges when Range is supported`() {
        val totalBytes = 1_000_000L // ~1 MB
        val slices = ChunkCalculator.calculateSlices(
            totalBytes = totalBytes,
            requestedChunks = 4,
            supportsRange = true
        )

        assertEquals(4, slices.size)
        assertEquals(ByteRangeSlice(0, 0L, 249_999L), slices[0])
        assertEquals(ByteRangeSlice(1, 250_000L, 499_999L), slices[1])
        assertEquals(ByteRangeSlice(2, 500_000L, 749_999L), slices[2])
        assertEquals(ByteRangeSlice(3, 750_000L, 999_999L), slices[3])
    }

    @Test
    fun `calculateSlices falls back to single slice when server does not support Range`() {
        val slices = ChunkCalculator.calculateSlices(
            totalBytes = 5_000_000L,
            requestedChunks = 4,
            supportsRange = false
        )

        assertEquals(1, slices.size)
        assertEquals(ByteRangeSlice(0, 0L, 4_999_999L), slices.first())
    }

    @Test
    fun `calculateSlices returns open-ended single slice when file size is unknown`() {
        val slices = ChunkCalculator.calculateSlices(
            totalBytes = -1L,
            requestedChunks = 4,
            supportsRange = true
        )

        assertEquals(1, slices.size)
        assertEquals(ByteRangeSlice(0, 0L, -1L), slices.first())
    }

    @Test
    fun `calculateSlices falls back to single slice for small files under 64 KB`() {
        val smallSize = 40_000L // 40 KB < 64 KB
        val slices = ChunkCalculator.calculateSlices(
            totalBytes = smallSize,
            requestedChunks = 4,
            supportsRange = true
        )

        assertEquals(1, slices.size)
        assertEquals(ByteRangeSlice(0, 0L, 39_999L), slices.first())
    }

    @Test
    fun `calculateSlices clamps requestedChunks between 1 and 16`() {
        val totalBytes = 1_000_000L
        val minClamped = ChunkCalculator.calculateSlices(totalBytes, requestedChunks = 0, supportsRange = true)
        assertEquals(1, minClamped.size)

        val maxClamped = ChunkCalculator.calculateSlices(totalBytes, requestedChunks = 32, supportsRange = true)
        assertEquals(16, maxClamped.size)
    }

    @Test
    fun `calculateSlices handles non-divisible file sizes without dropping bytes`() {
        val totalBytes = 100_003L
        val slices = ChunkCalculator.calculateSlices(totalBytes, requestedChunks = 4, supportsRange = true)
        assertEquals(4, slices.size)

        // Verify continuity
        assertEquals(0L, slices[0].startByte)
        assertEquals(slices[0].endByte + 1, slices[1].startByte)
        assertEquals(slices[1].endByte + 1, slices[2].startByte)
        assertEquals(slices[2].endByte + 1, slices[3].startByte)
        assertEquals(totalBytes - 1, slices[3].endByte)
    }
}

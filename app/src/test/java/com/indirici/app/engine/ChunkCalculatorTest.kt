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
}

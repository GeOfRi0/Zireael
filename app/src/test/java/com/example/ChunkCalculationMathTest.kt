package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for Chunking and HTTP Range calculation mathematics,
 * including edge cases: zero byte files, 1 byte files, prime/odd byte sizes,
 * large files (> 4GB), and single vs multi-segment bounds.
 */
class ChunkCalculationMathTest {

    data class ChunkRange(val start: Long, val end: Long) {
        val size: Long get() = if (start > end) 0L else end - start + 1
    }

    private fun calculateSegments(totalBytes: Long, threadCount: Int): List<ChunkRange> {
        if (totalBytes <= 0 || threadCount <= 0) return emptyList()
        val effectiveThreads = threadCount.coerceAtLeast(1)
        val partSize = totalBytes / effectiveThreads
        val chunks = mutableListOf<ChunkRange>()

        for (i in 0 until effectiveThreads) {
            val start = i * partSize
            val end = if (i == effectiveThreads - 1) totalBytes - 1 else (i + 1) * partSize - 1
            if (start <= end) {
                chunks.add(ChunkRange(start, end))
            }
        }
        return chunks
    }

    @Test
    fun `zero byte file produces empty chunks`() {
        val chunks = calculateSegments(0L, 4)
        assertTrue(chunks.isEmpty())
    }

    @Test
    fun `negative byte file produces empty chunks`() {
        val chunks = calculateSegments(-100L, 4)
        assertTrue(chunks.isEmpty())
    }

    @Test
    fun `single byte file produces exactly one single byte chunk`() {
        val chunks = calculateSegments(1L, 4)
        assertEquals(1, chunks.size)
        assertEquals(0L, chunks[0].start)
        assertEquals(0L, chunks[0].end)
        assertEquals(1L, chunks[0].size)
    }

    @Test
    fun `regular file chunks partition entire file without gaps or overlaps`() {
        val totalBytes = 10_000_000L // 10 MB
        val threadCount = 4
        val chunks = calculateSegments(totalBytes, threadCount)

        assertEquals(4, chunks.size)
        // Verify continuity
        assertEquals(0L, chunks[0].start)
        assertEquals(chunks[0].end + 1, chunks[1].start)
        assertEquals(chunks[1].end + 1, chunks[2].start)
        assertEquals(chunks[2].end + 1, chunks[3].start)
        assertEquals(totalBytes - 1, chunks[3].end)

        // Sum of all chunk sizes must equal totalBytes exactly
        val sumSize = chunks.sumOf { it.size }
        assertEquals(totalBytes, sumSize)
    }

    @Test
    fun `odd prime byte file distributes remainder without dropping bytes`() {
        val totalBytes = 997L // prime number
        val threadCount = 8
        val chunks = calculateSegments(totalBytes, threadCount)

        assertEquals(8, chunks.size)
        assertEquals(0L, chunks[0].start)
        assertEquals(totalBytes - 1, chunks.last().end)

        val totalSum = chunks.sumOf { it.size }
        assertEquals(totalBytes, totalSum)
    }

    @Test
    fun `large file boundary exceeding 32-bit integer limits (10GB) calculates accurately`() {
        val totalBytes = 10L * 1024L * 1024L * 1024L // 10 GB (> Int.MAX_VALUE)
        val threadCount = 8
        val chunks = calculateSegments(totalBytes, threadCount)

        assertEquals(8, chunks.size)
        assertEquals(0L, chunks[0].start)
        assertEquals(totalBytes - 1, chunks.last().end)

        // Check sum
        val totalSum = chunks.sumOf { it.size }
        assertEquals(totalBytes, totalSum)

        // Individual chunk size around 1.25 GB
        val expectedPartSize = totalBytes / 8
        assertEquals(expectedPartSize, chunks[0].size)
    }

    @Test
    fun `single thread produces single chunk covering entire file`() {
        val totalBytes = 5_000_000L
        val chunks = calculateSegments(totalBytes, 1)

        assertEquals(1, chunks.size)
        assertEquals(0L, chunks[0].start)
        assertEquals(totalBytes - 1, chunks[0].end)
        assertEquals(totalBytes, chunks[0].size)
    }
}

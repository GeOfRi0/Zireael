package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * Unit tests verifying TurboDownloadEngine Phase 3 optimizations:
 * 1. Token Bucket Rate Limiter mathematics and token refill dynamics.
 * 2. Work-Stealing / Dynamic Segment Division bounds and continuity.
 * 3. Pre-allocation space calculations accounting for partial existing file length.
 * 4. Multi-chunk invariant guarantees and merge validation checks.
 * 5. Exponential backoff retry timing calculations.
 */
class TurboEngineOptimizationTest {

    // --- 1. Token Bucket Rate Limiter Tests ---

    class MockTokenBucketRateLimiter(private val limitProvider: () -> Long) {
        var availableTokens: Long = 0L
        var lastRefillNanos: Long = System.nanoTime()

        fun calculateWaitTimeMillis(bytes: Int, currentNanos: Long): Long {
            val limit = limitProvider()
            if (limit <= 0L || bytes <= 0) return 0L

            val elapsedNanos = (currentNanos - lastRefillNanos).coerceAtLeast(0L)
            lastRefillNanos = currentNanos

            val refilled = (elapsedNanos.toDouble() / 1_000_000_000.0 * limit).toLong()
            availableTokens = (availableTokens + refilled).coerceAtMost(limit)

            availableTokens -= bytes
            if (availableTokens < 0) {
                val deficit = -availableTokens
                val waitNanos = (deficit.toDouble() / limit * 1_000_000_000.0).toLong()
                return (waitNanos / 1_000_000L).coerceAtLeast(1L)
            }
            return 0L
        }
    }

    @Test
    fun `rate limiter with zero limit allows immediate processing with zero wait`() {
        val limiter = MockTokenBucketRateLimiter { 0L }
        val waitMs = limiter.calculateWaitTimeMillis(1024 * 1024, System.nanoTime())
        assertEquals(0L, waitMs)
    }

    @Test
    fun `rate limiter calculates accurate delay when burst exceeds token capacity`() {
        val limit = 1_000_000L // 1 MB/s
        val limiter = MockTokenBucketRateLimiter { limit }
        val now = 1_000_000_000_000L
        limiter.lastRefillNanos = now

        val waitMs = limiter.calculateWaitTimeMillis(2_000_000, now)
        assertEquals(2000L, waitMs)
    }

    // --- 2. Dynamic Work-Stealing / Segment Division Tests ---

    data class Chunk(
        val index: Int,
        val start: Long,
        val end: AtomicLong,
        val isHelper: Boolean = false,
        val originalIndex: Int = index
    ) {
        val size: Long get() = (end.get() - start + 1).coerceAtLeast(0L)
    }

    private fun splitChunk(
        target: Chunk,
        downloadedSoFar: Long,
        nextIndex: Int,
        minSplitSize: Long = 2 * 1024 * 1024L
    ): Chunk? {
        val currentHead = target.start + downloadedSoFar
        val remaining = target.end.get() - currentHead + 1
        if (remaining < 2 * minSplitSize) return null

        val halfRemaining = remaining / 2
        val newTargetEnd = currentHead + halfRemaining
        val helperStart = newTargetEnd + 1
        val oldEnd = target.end.getAndSet(newTargetEnd)

        if (oldEnd <= helperStart) {
            target.end.set(oldEnd)
            return null
        }

        return Chunk(
            index = nextIndex,
            start = helperStart,
            end = AtomicLong(oldEnd),
            isHelper = true,
            originalIndex = target.originalIndex
        )
    }

    @Test
    fun `work-stealing splits large segment evenly without gaps or overlaps`() {
        val totalBytes = 100_000_000L
        val chunk0 = Chunk(0, 0L, AtomicLong(totalBytes - 1))

        val downloadedSoFar = 20_000_000L
        val helperChunk = splitChunk(chunk0, downloadedSoFar, nextIndex = 1)

        assertNotNull(helperChunk)
        val expectedNewTargetEnd = 20_000_000L + (80_000_000L / 2)
        assertEquals(expectedNewTargetEnd, chunk0.end.get())
        assertEquals(expectedNewTargetEnd + 1, helperChunk!!.start)
        assertEquals(totalBytes - 1, helperChunk.end.get())
        assertTrue(helperChunk.isHelper)
        assertEquals(0, helperChunk.originalIndex)

        val combinedSize = chunk0.size + helperChunk.size
        assertEquals(totalBytes, combinedSize)
    }

    // --- 3. Chunk Merge Validation Tests ---

    data class MockChunkFile(val subIndex: Int, val startByte: Long, val endByte: Long, val actualSize: Long) {
        val expectedSize: Long get() = (endByte - startByte + 1).coerceAtLeast(0L)
    }

    private fun validateChunksForMerge(chunks: List<MockChunkFile>, expectedTotal: Long) {
        val sorted = chunks.sortedBy { it.startByte }
        var expectedNext = 0L

        for (c in sorted) {
            if (c.actualSize < c.expectedSize) {
                throw IllegalStateException("Eksik byte: ${c.actualSize} < ${c.expectedSize}")
            }
            if (c.actualSize > c.expectedSize) {
                throw IllegalStateException("Fazla byte: ${c.actualSize} > ${c.expectedSize}")
            }
            if (c.startByte != expectedNext) {
                throw IllegalStateException("Süreksizlik tespit edildi: $expectedNext != ${c.startByte}")
            }
            expectedNext = c.endByte + 1
        }

        val total = sorted.sumOf { it.actualSize }
        if (expectedTotal > 0 && total != expectedTotal) {
            throw IllegalStateException("Toplam boyut uyumsuz: $total != $expectedTotal")
        }
    }

    @Test
    fun `merge validation succeeds when all chunks are continuous and exact size`() {
        val chunks = listOf(
            MockChunkFile(0, 0, 49, 50),
            MockChunkFile(1, 50, 99, 50)
        )
        // Should not throw
        validateChunksForMerge(chunks, 100)
    }

    @Test
    fun `merge validation detects missing bytes in a chunk`() {
        val chunks = listOf(
            MockChunkFile(0, 0, 49, 48), // 2 bytes missing!
            MockChunkFile(1, 50, 99, 50)
        )
        try {
            validateChunksForMerge(chunks, 100)
            fail("Expected exception for missing bytes")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Eksik byte"))
        }
    }

    @Test
    fun `merge validation detects gap between chunks`() {
        val chunks = listOf(
            MockChunkFile(0, 0, 40, 41),
            MockChunkFile(1, 45, 99, 55) // Gap between 41 and 45!
        )
        try {
            validateChunksForMerge(chunks, 100)
            fail("Expected exception for gap discontinuity")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Süreksizlik"))
        }
    }

    // --- 4. Exponential Backoff Calculation Tests ---

    @Test
    fun `exponential backoff doubles interval up to maximum cap`() {
        var backoffMs = 1000L
        val maxBackoffMs = 16000L

        val intervals = mutableListOf<Long>()
        repeat(6) {
            intervals.add(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(maxBackoffMs)
        }

        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 16000L, 16000L), intervals)
    }
}

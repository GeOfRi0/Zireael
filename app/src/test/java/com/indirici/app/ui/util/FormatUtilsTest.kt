package com.indirici.app.ui.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatUtilsTest {

    @Test
    fun `formatBytes handles negative, zero, and sub-kilobyte sizes correctly`() {
        assertEquals("Bilinmiyor", FormatUtils.formatBytes(-1L))
        assertEquals("Bilinmiyor", FormatUtils.formatBytes(-100L))
        assertEquals("0 B", FormatUtils.formatBytes(0L))
        assertEquals("512 B", FormatUtils.formatBytes(512L))
        assertEquals("1023 B", FormatUtils.formatBytes(1023L))
    }

    @Test
    fun `formatBytes formats kilobytes, megabytes, and gigabytes with correct decimal precision`() {
        assertEquals("1.0 KB", FormatUtils.formatBytes(1024L))
        assertEquals("1.5 KB", FormatUtils.formatBytes(1536L))
        assertEquals("1.0 MB", FormatUtils.formatBytes(1024L * 1024L))
        assertEquals("2.5 MB", FormatUtils.formatBytes((2.5 * 1024 * 1024).toLong()))
        assertEquals("1.00 GB", FormatUtils.formatBytes(1024L * 1024L * 1024L))
        assertEquals("3.75 GB", FormatUtils.formatBytes((3.75 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun `formatSpeed handles zero, negative, and active download transfer rates`() {
        assertEquals("0 B/sn", FormatUtils.formatSpeed(0L))
        assertEquals("0 B/sn", FormatUtils.formatSpeed(-50L))
        assertEquals("500 B/sn", FormatUtils.formatSpeed(500L))
        assertEquals("1.0 MB/sn", FormatUtils.formatSpeed(1024L * 1024L))
        assertEquals("2.5 MB/sn", FormatUtils.formatSpeed((2.5 * 1024 * 1024).toLong()))
    }
}

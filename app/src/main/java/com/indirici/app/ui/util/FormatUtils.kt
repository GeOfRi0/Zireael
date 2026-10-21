package com.indirici.app.ui.util

import java.util.Locale

object FormatUtils {

    fun formatBytes(bytes: Long): String {
        if (bytes < 0L) return "Bilinmiyor"
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.2f GB", gb)
    }

    fun formatSpeed(bytesPerSec: Long): String {
        if (bytesPerSec <= 0L) return "0 B/sn"
        return "${formatBytes(bytesPerSec)}/sn"
    }
}

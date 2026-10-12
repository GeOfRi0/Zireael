package com.example.data.engine

import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * PathSecuritySanitizer:
 * Security utility protecting against Path Traversal and Zip Slip attacks.
 *
 * Guarantees:
 * 1. Sanitizes untrusted filenames from Content-Disposition headers, query parameters, and URLs.
 * 2. Eliminates directory traversal patterns ('../', '..\\', '%2e%2e').
 * 3. Removes illegal filesystem characters and control characters (\u0000 to \u001f, \u007f).
 * 4. Ensures files are strictly restricted to their designated destination directory.
 */
object PathSecuritySanitizer {

    private val ILLEGAL_CHARS_REGEX = Regex("""[\\/:*?"<>|\u0000-\u001F\u007F]""")
    private val RESERVED_NAMES = setOf("con", "prn", "aux", "nul", "com1", "com2", "com3", "com4", "lpt1", "lpt2")

    /**
     * Sanitizes a raw filename to prevent Path Traversal and Zip Slip attacks.
     *
     * @param rawName Raw input filename from header or URL.
     * @param fallback Default safe name if input is invalid or blank.
     * @return A clean, traversal-safe basename.
     */
    fun sanitizeFileName(rawName: String?, fallback: String = "downloaded_file"): String {
        if (rawName.isNullOrBlank()) return fallback

        // 1. Decode potential URL/percent-encoded traversal sequences (e.g., %2e%2e%2f -> ../)
        var name = rawName.trim()
        try {
            while (name.contains('%')) {
                val decoded = URLDecoder.decode(name, StandardCharsets.UTF_8.name())
                if (decoded == name) break
                name = decoded
            }
        } catch (_: Exception) {
            // Keep current string if percent decoding fails
        }

        // 2. Normalize backslashes and strip any directory paths, taking only the basename
        name = name.replace('\\', '/')
        name = name.substringAfterLast('/')

        // 3. Remove traversal indicators ('..' patterns) repeatedly
        while (name.contains("..")) {
            name = name.replace("..", "")
        }

        // 4. Replace illegal filesystem characters, null bytes, and control characters with underscores
        name = name.replace(ILLEGAL_CHARS_REGEX, "_")

        // 5. Trim leading and trailing spaces, dots, and underscores
        name = name.trim().trim('.', '_', ' ')

        // 6. Prevent Windows/FAT32 reserved device names
        val lowerBase = name.substringBefore('.').lowercase()
        if (name.isBlank() || RESERVED_NAMES.contains(lowerBase)) {
            return fallback
        }

        // 7. Enforce max filename length (255 characters limit on Android filesystems)
        if (name.length > 255) {
            val ext = name.substringAfterLast('.', "")
            val base = name.substringBeforeLast('.')
            name = if (ext.isNotBlank()) {
                val maxBaseLen = 254 - ext.length
                "${base.take(maxBaseLen)}.$ext"
            } else {
                name.take(255)
            }
        }

        return name.ifBlank { fallback }
    }

    /**
     * Validates whether resolving [fileName] inside [targetDirectory] is strictly confined
     * to [targetDirectory] and does NOT escape to a parent directory (Zip Slip prevention).
     */
    fun isSafeDestination(targetDirectory: File, fileName: String): Boolean {
        return try {
            val safeName = sanitizeFileName(fileName)
            val destFile = File(targetDirectory, safeName).canonicalFile
            val baseDir = targetDirectory.canonicalFile
            destFile.path.startsWith(baseDir.path + File.separator) || destFile == baseDir
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Validates whether [destinationFile] is strictly confined inside [targetDirectory]
     * and does NOT escape to a parent directory (Zip Slip prevention).
     */
    fun isSafeDestination(targetDirectory: File, destinationFile: File): Boolean {
        return try {
            val destFile = destinationFile.canonicalFile
            val baseDir = targetDirectory.canonicalFile
            destFile.path.startsWith(baseDir.path + File.separator) || destFile == baseDir
        } catch (_: Exception) {
            false
        }
    }
}

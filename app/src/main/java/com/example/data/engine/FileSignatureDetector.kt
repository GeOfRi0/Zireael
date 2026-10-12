package com.example.data.engine

import java.io.File
import java.io.FileInputStream

object FileSignatureDetector {

    data class DetectionResult(
        val extension: String,
        val mimeType: String,
        val category: String,
        val isHtmlOrWebPage: Boolean = false
    )

    fun detect(file: File): DetectionResult? {
        if (!file.exists() || file.length() < 4) return null
        val buffer = ByteArray(256)
        val bytesRead = try {
            FileInputStream(file).use { it.read(buffer) }
        } catch (e: Exception) {
            return null
        }
        if (bytesRead < 4) return null
        return detectFromBytes(buffer, bytesRead, file.length())
    }

    fun detectFromBytes(bytes: ByteArray, length: Int, totalFileSize: Long = 0L): DetectionResult? {
        if (length < 4) return null

        // 1. MP4 / M4A / MOV (ISO Base Media File Format)
        // Checks ftyp box at offset 4 or within first 64 bytes
        var ftypOffset = -1
        for (i in 0..minOf(length - 4, 64)) {
            if (bytes[i] == 'f'.code.toByte() && bytes[i + 1] == 't'.code.toByte() &&
                bytes[i + 2] == 'y'.code.toByte() && bytes[i + 3] == 'p'.code.toByte()
            ) {
                ftypOffset = i
                break
            }
        }
        if (ftypOffset != -1) {
            val brand = if (length >= ftypOffset + 8) String(bytes, ftypOffset + 4, 4, Charsets.US_ASCII) else ""
            return if (brand.startsWith("M4A", ignoreCase = true)) {
                DetectionResult(".m4a", "audio/mp4", "MEDIA")
            } else if (brand.startsWith("qt", ignoreCase = true)) {
                DetectionResult(".mov", "video/quicktime", "MEDIA")
            } else {
                DetectionResult(".mp4", "video/mp4", "MEDIA")
            }
        }

        // Checks moov box or mdat box within first 64 bytes
        for (i in 0..minOf(length - 4, 64)) {
            if ((bytes[i] == 'm'.code.toByte() && bytes[i + 1] == 'o'.code.toByte() && bytes[i + 2] == 'o'.code.toByte() && bytes[i + 3] == 'v'.code.toByte()) ||
                (bytes[i] == 'm'.code.toByte() && bytes[i + 1] == 'd'.code.toByte() && bytes[i + 2] == 'a'.code.toByte() && bytes[i + 3] == 't'.code.toByte())
            ) {
                return DetectionResult(".mp4", "video/mp4", "MEDIA")
            }
        }

        // 2. WebM / Matroska (EBML signature: 0x1A 0x45 0xDF 0xA3)
        if (bytes[0] == 0x1A.toByte() && bytes[1] == 0x45.toByte() &&
            bytes[2] == 0xDF.toByte() && bytes[3] == 0xA3.toByte()
        ) {
            val headerString = String(bytes, 0, length.coerceAtMost(64), Charsets.US_ASCII)
            return if (headerString.contains("webm", ignoreCase = true)) {
                DetectionResult(".webm", "video/webm", "MEDIA")
            } else {
                DetectionResult(".mkv", "video/x-matroska", "MEDIA")
            }
        }

        // 3. MPEG-TS stream (Sync byte 0x47)
        if (bytes[0] == 0x47.toByte()) {
            if (length >= 188 && bytes.getOrNull(188) == 0x47.toByte()) {
                return DetectionResult(".ts", "video/mp2t", "MEDIA")
            }
        }

        // 4. MPEG-1 / MPEG-2 Video (0x00 0x00 0x01 0xBA or 0x00 0x00 0x01 0xB3)
        if (bytes[0] == 0x00.toByte() && bytes[1] == 0x00.toByte() && bytes[2] == 0x01.toByte() &&
            (bytes[3] == 0xBA.toByte() || bytes[3] == 0xB3.toByte())
        ) {
            return DetectionResult(".mpg", "video/mpeg", "MEDIA")
        }

        // 5. RIFF containers: AVI, WAV, WEBP (bytes[0..3] == 'R','I','F','F')
        if (bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() && length >= 12
        ) {
            val formType = String(bytes, 8, 4, Charsets.US_ASCII)
            return when {
                formType.equals("AVI ", ignoreCase = true) -> DetectionResult(".avi", "video/x-msvideo", "MEDIA")
                formType.equals("WAVE", ignoreCase = true) -> DetectionResult(".wav", "audio/wav", "MEDIA")
                formType.equals("WEBP", ignoreCase = true) -> DetectionResult(".webp", "image/webp", "MEDIA")
                else -> DetectionResult(".avi", "video/x-msvideo", "MEDIA")
            }
        }

        // 6. MP3 Audio (ID3 tag or MPEG sync frames)
        if (bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() && bytes[2] == '3'.code.toByte()) {
            return DetectionResult(".mp3", "audio/mpeg", "MEDIA")
        }
        if (bytes[0] == 0xFF.toByte() && (bytes[1].toInt() and 0xE0) == 0xE0) {
            // Check for AAC ADTS (0xFFF1 or 0xFFF9)
            val secondByte = bytes[1].toInt() and 0xFF
            if ((secondByte and 0xF6) == 0xF0) {
                return DetectionResult(".aac", "audio/aac", "MEDIA")
            }
            return DetectionResult(".mp3", "audio/mpeg", "MEDIA")
        }

        // 7. FLAC Audio (fLaC)
        if (bytes[0] == 'f'.code.toByte() && bytes[1] == 'L'.code.toByte() &&
            bytes[2] == 'a'.code.toByte() && bytes[3] == 'C'.code.toByte()
        ) {
            return DetectionResult(".flac", "audio/flac", "MEDIA")
        }

        // 8. OGG Audio/Video (OggS)
        if (bytes[0] == 'O'.code.toByte() && bytes[1] == 'g'.code.toByte() &&
            bytes[2] == 'g'.code.toByte() && bytes[3] == 'S'.code.toByte()
        ) {
            return DetectionResult(".ogg", "audio/ogg", "MEDIA")
        }

        // 9. JPEG Image (0xFF 0xD8 0xFF)
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) {
            return DetectionResult(".jpg", "image/jpeg", "MEDIA")
        }

        // 10. PNG Image (0x89 'P' 'N' 'G')
        if (bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte()
        ) {
            return DetectionResult(".png", "image/png", "MEDIA")
        }

        // 11. GIF Image (GIF87a / GIF89a)
        if (bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == '8'.code.toByte()
        ) {
            return DetectionResult(".gif", "image/gif", "MEDIA")
        }

        // 12. BMP Image (BM)
        if (bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte()) {
            return DetectionResult(".bmp", "image/bmp", "MEDIA")
        }

        // 13. PDF Document (%PDF)
        if (bytes[0] == '%'.code.toByte() && bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'D'.code.toByte() && bytes[3] == 'F'.code.toByte()
        ) {
            return DetectionResult(".pdf", "application/pdf", "DOCUMENTS")
        }

        // 14. ZIP / APK / JAR / Office Open XML (PK\x03\x04)
        if (bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() &&
            bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()
        ) {
            val contentPreview = String(bytes, 0, length, Charsets.US_ASCII)
            return if (contentPreview.contains("AndroidManifest.xml") || contentPreview.contains("classes.dex")) {
                DetectionResult(".apk", "application/vnd.android.package-archive", "APKS")
            } else if (contentPreview.contains("word/") || contentPreview.contains("[Content_Types].xml")) {
                DetectionResult(".docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "DOCUMENTS")
            } else {
                DetectionResult(".zip", "application/zip", "ARCHIVES")
            }
        }

        // 15. RAR Archive (Rar!\x1A\x07)
        if (bytes[0] == 'R'.code.toByte() && bytes[1] == 'a'.code.toByte() &&
            bytes[2] == 'r'.code.toByte() && bytes[3] == '!'.code.toByte()
        ) {
            return DetectionResult(".rar", "application/x-rar-compressed", "ARCHIVES")
        }

        // 16. 7-Zip Archive (7z\xBC\xAF\x27\x1C)
        if (bytes[0] == '7'.code.toByte() && bytes[1] == 'z'.code.toByte() &&
            bytes[2] == 0xBC.toByte() && bytes[3] == 0xAF.toByte() &&
            bytes[4] == 0x27.toByte() && bytes[5] == 0x1C.toByte()
        ) {
            return DetectionResult(".7z", "application/x-7z-compressed", "ARCHIVES")
        }

        // 17. GZIP Archive (0x1F 0x8B)
        if (bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte()) {
            return DetectionResult(".gz", "application/gzip", "ARCHIVES")
        }

        // 18. FLV Video (FLV\x01)
        if (bytes[0] == 'F'.code.toByte() && bytes[1] == 'L'.code.toByte() &&
            bytes[2] == 'V'.code.toByte()
        ) {
            return DetectionResult(".flv", "video/x-flv", "MEDIA")
        }

        // 19. HLS Playlist (#EXTM3U)
        if (length >= 7 && String(bytes, 0, 7, Charsets.US_ASCII) == "#EXTM3U") {
            return DetectionResult(".m3u8", "application/vnd.apple.mpegurl", "MEDIA")
        }

        // 20. Web Page / HTML Detection (<!DOCTYPE html>, <html>, <HTML>, <?xml)
        val textPreview = String(bytes, 0, length.coerceAtMost(128), Charsets.UTF_8).trim().lowercase()
        if (textPreview.startsWith("<!doctype html") ||
            textPreview.startsWith("<html") ||
            textPreview.contains("<head>") ||
            textPreview.contains("<body>")
        ) {
            return DetectionResult(".html", "text/html", "DOCUMENTS", isHtmlOrWebPage = true)
        }

        // 21. JSON Text ({...} or [...])
        if (textPreview.startsWith("{") || textPreview.startsWith("[")) {
            return DetectionResult(".json", "application/json", "DOCUMENTS")
        }

        return null
    }
}

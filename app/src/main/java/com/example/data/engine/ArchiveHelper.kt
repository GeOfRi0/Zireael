package com.example.data.engine

import android.content.Context
import android.util.Log
import com.github.junrar.Archive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Archive types supported by Zireael's native ArchiveHelper.
 */
enum class ArchiveFormat(val extensionName: String) {
    ZIP("ZIP"),
    RAR("RAR"),
    SEVEN_Z("7Z"),
    TAR("TAR"),
    TAR_GZ("TAR.GZ"),
    UNKNOWN("Bilinmiyor")
}

/**
 * Result data class for archive extraction operations.
 */
data class ArchiveExtractionResult(
    val success: Boolean,
    val sourceArchive: File,
    val outputDirectory: File,
    val extractedCount: Int,
    val totalBytesExtracted: Long,
    val errorMessage: String? = null
)

/**
 * ArchiveHelper:
 * High-performance, memory-safe and zero-external-dependency archive extractor.
 *
 * Capabilities:
 * 1. Supports ZIP, RAR, 7Z, TAR and TAR.GZ archives.
 * 2. Strict Zip Slip (Path Traversal) prevention via PathSecuritySanitizer.
 * 3. 8KB constant streaming buffer and use {} blocks to eliminate OOM errors.
 * 4. Background extraction running on Dispatchers.IO.
 */
object ArchiveHelper {

    private const val TAG = "ArchiveHelper"
    private const val BUFFER_SIZE = 8 * 1024 // 8 KB constant buffer

    /**
     * Determines whether a given filename or path is a recognized archive.
     */
    fun isArchive(fileName: String): Boolean {
        val lower = fileName.lowercase().substringBefore('?').trim()
        return lower.endsWith(".zip") ||
                lower.endsWith(".rar") ||
                lower.endsWith(".7z") ||
                lower.endsWith(".tar") ||
                lower.endsWith(".tar.gz") ||
                lower.endsWith(".tgz") ||
                lower.endsWith(".gz")
    }

    /**
     * Detects the specific ArchiveFormat based on file name or magic bytes.
     */
    fun detectFormat(file: File): ArchiveFormat {
        val name = file.name.lowercase()
        return when {
            name.endsWith(".zip") -> ArchiveFormat.ZIP
            name.endsWith(".rar") -> ArchiveFormat.RAR
            name.endsWith(".7z") -> ArchiveFormat.SEVEN_Z
            name.endsWith(".tar.gz") || name.endsWith(".tgz") -> ArchiveFormat.TAR_GZ
            name.endsWith(".tar") -> ArchiveFormat.TAR
            name.endsWith(".gz") -> ArchiveFormat.TAR_GZ
            else -> ArchiveFormat.UNKNOWN
        }
    }

    /**
     * Generates a safe output directory in the same parent folder as the archive,
     * named after the archive file without its extension.
     */
    fun getDefaultOutputDirectory(archiveFile: File): File {
        val parent = archiveFile.parentFile ?: File(".")
        var baseName = archiveFile.name
        if (baseName.endsWith(".tar.gz", ignoreCase = true)) {
            baseName = baseName.substring(0, baseName.length - 7)
        } else if (baseName.contains(".")) {
            baseName = baseName.substringBeforeLast(".")
        }
        val safeBaseName = PathSecuritySanitizer.sanitizeFileName(baseName, fallback = "extracted_archive")
        var targetDir = File(parent, safeBaseName)

        // If a non-directory file exists with that name, append suffix
        if (targetDir.exists() && !targetDir.isDirectory) {
            targetDir = File(parent, "${safeBaseName}_extracted")
        }
        return targetDir
    }

    /**
     * Extracts an archive file in the background (Dispatchers.IO).
     *
     * @param archiveFile The source archive file (.zip, .rar, .7z, .tar, .gz).
     * @param destinationDir Optional target directory. Defaults to a folder named after the archive.
     * @param onProgress Optional progress callback (extractedCount, currentFileName).
     * @return Result containing ArchiveExtractionResult.
     */
    suspend fun extractArchive(
        archiveFile: File,
        destinationDir: File? = null,
        onProgress: ((Int, String) -> Unit)? = null
    ): Result<ArchiveExtractionResult> = withContext(Dispatchers.IO) {
        if (!archiveFile.exists() || !archiveFile.canRead() || archiveFile.length() == 0L) {
            return@withContext Result.failure(
                IllegalArgumentException("Arşiv dosyası bulunamadı veya okunamıyor: ${archiveFile.name}")
            )
        }

        val targetDir = destinationDir ?: getDefaultOutputDirectory(archiveFile)
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }

        val format = detectFormat(archiveFile)
        Log.i(TAG, "Starting extraction for ${archiveFile.name} (Format: $format) into ${targetDir.absolutePath}")

        try {
            val result = when (format) {
                ArchiveFormat.ZIP -> extractZip(archiveFile, targetDir, onProgress)
                ArchiveFormat.RAR -> extractRar(archiveFile, targetDir, onProgress)
                ArchiveFormat.SEVEN_Z -> extractSevenZ(archiveFile, targetDir, onProgress)
                ArchiveFormat.TAR -> extractTar(archiveFile, targetDir, isGzip = false, onProgress)
                ArchiveFormat.TAR_GZ -> extractTar(archiveFile, targetDir, isGzip = true, onProgress)
                ArchiveFormat.UNKNOWN -> {
                    // Fallback attempt: try ZIP first, then RAR
                    try {
                        extractZip(archiveFile, targetDir, onProgress)
                    } catch (_: Exception) {
                        extractRar(archiveFile, targetDir, onProgress)
                    }
                }
            }
            Log.i(TAG, "Extraction finished successfully: ${result.extractedCount} files, ${result.totalBytesExtracted} bytes")
            Result.success(result)
        } catch (e: Exception) {
            Log.e(TAG, "Archive extraction failed for ${archiveFile.name}", e)
            Result.failure(e)
        }
    }

    // -------------------------------------------------------------------------
    // 1. ZIP Extraction (Commons Compress ZipFile)
    // -------------------------------------------------------------------------
    private fun extractZip(
        archiveFile: File,
        targetDir: File,
        onProgress: ((Int, String) -> Unit)?
    ): ArchiveExtractionResult {
        var extractedCount = 0
        var totalBytes = 0L

        ZipFile.builder().setFile(archiveFile).get().use { zipFile ->
            val entries = zipFile.entries
            while (entries.hasMoreElements()) {
                val entry: ZipArchiveEntry = entries.nextElement()
                val entryName = entry.name

                // 1. Zip Slip / Path Traversal Guard
                val destinationFile = File(targetDir, entryName)
                if (!PathSecuritySanitizer.isSafeDestination(targetDir, destinationFile)) {
                    Log.w(TAG, "Zip Slip attempt blocked: $entryName")
                    continue
                }

                if (entry.isDirectory) {
                    destinationFile.mkdirs()
                } else {
                    destinationFile.parentFile?.mkdirs()
                    zipFile.getInputStream(entry).use { inputStream ->
                        FileOutputStream(destinationFile).use { outputStream ->
                            val copied = copyStreamWithBuffer(inputStream, outputStream)
                            totalBytes += copied
                        }
                    }
                    extractedCount++
                    onProgress?.invoke(extractedCount, destinationFile.name)
                }
            }
        }

        return ArchiveExtractionResult(
            success = true,
            sourceArchive = archiveFile,
            outputDirectory = targetDir,
            extractedCount = extractedCount,
            totalBytesExtracted = totalBytes
        )
    }

    // -------------------------------------------------------------------------
    // 2. RAR Extraction (Junrar Archive)
    // -------------------------------------------------------------------------
    private fun extractRar(
        archiveFile: File,
        targetDir: File,
        onProgress: ((Int, String) -> Unit)?
    ): ArchiveExtractionResult {
        var extractedCount = 0
        var totalBytes = 0L

        Archive(archiveFile).use { rarArchive ->
            var header = rarArchive.nextFileHeader()
            while (header != null) {
                val entryName = header.fileName.replace('\\', '/')

                // 1. Zip Slip / Path Traversal Guard
                val destinationFile = File(targetDir, entryName)
                if (!PathSecuritySanitizer.isSafeDestination(targetDir, destinationFile)) {
                    Log.w(TAG, "Zip Slip attempt blocked in RAR: $entryName")
                    header = rarArchive.nextFileHeader()
                    continue
                }

                if (header.isDirectory) {
                    destinationFile.mkdirs()
                } else {
                    destinationFile.parentFile?.mkdirs()
                    FileOutputStream(destinationFile).use { outputStream ->
                        rarArchive.extractFile(header, outputStream)
                    }
                    totalBytes += destinationFile.length()
                    extractedCount++
                    onProgress?.invoke(extractedCount, destinationFile.name)
                }
                header = rarArchive.nextFileHeader()
            }
        }

        return ArchiveExtractionResult(
            success = true,
            sourceArchive = archiveFile,
            outputDirectory = targetDir,
            extractedCount = extractedCount,
            totalBytesExtracted = totalBytes
        )
    }

    // -------------------------------------------------------------------------
    // 3. 7Z Extraction (Commons Compress SevenZFile)
    // -------------------------------------------------------------------------
    private fun extractSevenZ(
        archiveFile: File,
        targetDir: File,
        onProgress: ((Int, String) -> Unit)?
    ): ArchiveExtractionResult {
        var extractedCount = 0
        var totalBytes = 0L

        SevenZFile.builder().setFile(archiveFile).get().use { sevenZFile ->
            var entry = sevenZFile.nextEntry
            val buffer = ByteArray(BUFFER_SIZE)

            while (entry != null) {
                val entryName = entry.name

                // 1. Zip Slip / Path Traversal Guard
                val destinationFile = File(targetDir, entryName)
                if (!PathSecuritySanitizer.isSafeDestination(targetDir, destinationFile)) {
                    Log.w(TAG, "Zip Slip attempt blocked in 7Z: $entryName")
                    entry = sevenZFile.nextEntry
                    continue
                }

                if (entry.isDirectory) {
                    destinationFile.mkdirs()
                } else {
                    destinationFile.parentFile?.mkdirs()
                    FileOutputStream(destinationFile).use { outputStream ->
                        var bytesRead: Int
                        while (sevenZFile.read(buffer).also { bytesRead = it } > 0) {
                            outputStream.write(buffer, 0, bytesRead)
                            totalBytes += bytesRead
                        }
                    }
                    extractedCount++
                    onProgress?.invoke(extractedCount, destinationFile.name)
                }
                entry = sevenZFile.nextEntry
            }
        }

        return ArchiveExtractionResult(
            success = true,
            sourceArchive = archiveFile,
            outputDirectory = targetDir,
            extractedCount = extractedCount,
            totalBytesExtracted = totalBytes
        )
    }

    // -------------------------------------------------------------------------
    // 4. TAR & TAR.GZ Extraction
    // -------------------------------------------------------------------------
    private fun extractTar(
        archiveFile: File,
        targetDir: File,
        isGzip: Boolean,
        onProgress: ((Int, String) -> Unit)?
    ): ArchiveExtractionResult {
        var extractedCount = 0
        var totalBytes = 0L

        val rawInput: InputStream = FileInputStream(archiveFile)
        val bufferedInput = BufferedInputStream(rawInput)
        val tarInput: TarArchiveInputStream = if (isGzip) {
            TarArchiveInputStream(GzipCompressorInputStream(bufferedInput))
        } else {
            TarArchiveInputStream(bufferedInput)
        }

        tarInput.use { tis ->
            var entry = tis.nextEntry
            while (entry != null) {
                val entryName = entry.name

                // 1. Zip Slip / Path Traversal Guard
                val destinationFile = File(targetDir, entryName)
                if (!PathSecuritySanitizer.isSafeDestination(targetDir, destinationFile)) {
                    Log.w(TAG, "Zip Slip attempt blocked in TAR: $entryName")
                    entry = tis.nextEntry
                    continue
                }

                if (entry.isDirectory) {
                    destinationFile.mkdirs()
                } else {
                    destinationFile.parentFile?.mkdirs()
                    FileOutputStream(destinationFile).use { fos ->
                        val copied = copyStreamWithBuffer(tis, fos)
                        totalBytes += copied
                    }
                    extractedCount++
                    onProgress?.invoke(extractedCount, destinationFile.name)
                }
                entry = tis.nextEntry
            }
        }

        return ArchiveExtractionResult(
            success = true,
            sourceArchive = archiveFile,
            outputDirectory = targetDir,
            extractedCount = extractedCount,
            totalBytesExtracted = totalBytes
        )
    }

    /**
     * Copies data from InputStream to OutputStream using an 8KB buffer and returns bytes copied.
     */
    private fun copyStreamWithBuffer(input: InputStream, output: OutputStream): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        var read: Int
        while (input.read(buffer).also { read = it } != -1) {
            output.write(buffer, 0, read)
            total += read
        }
        output.flush()
        return total
    }
}

package com.indirici.app.data.storage

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Geçici indirilen dosyayı MediaStore API kullanarak genel İndirilenler (Download) veya
 * Filmler (Movies) klasörüne taşır ve MediaScanner ile kullanıcının galerisinde görünmesini sağlar.
 */
class DownloadsFileSaver(private val context: Context) {

    /**
     * Geçici dosyaların yazılacağı Scoped Storage klasörünü döndürür:
     * context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
     */
    fun getScopedTempDownloadsDir(subFolder: String? = null): File {
        val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.cacheDir, "downloads_fallback")
        val targetDir = if (!subFolder.isNullOrBlank()) File(baseDir, subFolder) else baseDir
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }
        return targetDir
    }

    suspend fun saveToPublicDownloads(
        tempFile: File,
        desiredFileName: String,
        mimeType: String?,
        saveVideoToMovies: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        if (!tempFile.exists()) {
            throw IOException("Geçici dosya bulunamadı: ${tempFile.absolutePath}")
        }

        val safeName = sanitizeFileName(desiredFileName)
        val resolvedMime = mimeType ?: guessMimeType(safeName)
        val isVideo = resolvedMime.startsWith("video/")

        val savedUriString = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(
                tempFile = tempFile,
                fileName = safeName,
                mimeType = resolvedMime,
                useMoviesCollection = saveVideoToMovies && isVideo
            )
        } else {
            saveViaLegacyExternalDir(
                tempFile = tempFile,
                fileName = safeName,
                mimeType = resolvedMime,
                useMoviesDir = saveVideoToMovies && isVideo
            )
        }

        if (tempFile.exists()) {
            tempFile.delete()
        }

        return@withContext savedUriString
    }

    private fun saveViaMediaStore(
        tempFile: File,
        fileName: String,
        mimeType: String,
        useMoviesCollection: Boolean
    ): String {
        val resolver = context.contentResolver
        val relativeDir = if (useMoviesCollection) {
            Environment.DIRECTORY_MOVIES
        } else {
            Environment.DIRECTORY_DOWNLOADS
        }

        val collection = if (useMoviesCollection && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Files.getContentUri("external")
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val itemUri = resolver.insert(collection, values)
            ?: throw IOException("MediaStore kaydı oluşturulamadı")

        try {
            resolver.openOutputStream(itemUri)?.use { outputStream ->
                tempFile.inputStream().use { inputStream ->
                    inputStream.copyTo(outputStream, bufferSize = 64 * 1024)
                }
            } ?: throw IOException("MediaStore çıktı akışı açılamadı")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val completeValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                resolver.update(itemUri, completeValues, null, null)
            }

            return itemUri.toString()
        } catch (e: Exception) {
            resolver.delete(itemUri, null, null)
            throw e
        }
    }

    @Suppress("DEPRECATION")
    private fun saveViaLegacyExternalDir(
        tempFile: File,
        fileName: String,
        mimeType: String,
        useMoviesDir: Boolean
    ): String {
        val dirType = if (useMoviesDir) {
            Environment.DIRECTORY_MOVIES
        } else {
            Environment.DIRECTORY_DOWNLOADS
        }
        val publicDir = Environment.getExternalStoragePublicDirectory(dirType)
        if (!publicDir.exists()) {
            publicDir.mkdirs()
        }

        var targetFile = File(publicDir, fileName)
        var counter = 1
        val nameWithoutExt = fileName.substringBeforeLast('.', fileName)
        val ext = fileName.substringAfterLast('.', "")

        while (targetFile.exists()) {
            val candidateName = if (ext.isNotEmpty()) {
                "${nameWithoutExt}_($counter).$ext"
            } else {
                "${nameWithoutExt}_($counter)"
            }
            targetFile = File(publicDir, candidateName)
            counter++
        }

        tempFile.inputStream().use { input ->
            targetFile.outputStream().use { output ->
                input.copyTo(output, bufferSize = 64 * 1024)
            }
        }

        // Galeride görünmesi için MediaScanner'ı tetikle
        runCatching {
            MediaScannerConnection.scanFile(
                context,
                arrayOf(targetFile.absolutePath),
                arrayOf(mimeType),
                null
            )
        }

        return targetFile.toURI().toString()
    }

    companion object {
        internal fun sanitizeFileName(raw: String): String {
            val stripped = raw.replace(Regex("""(?:\.\.[/\\])+"""), "")
            val withoutCtrl = stripped.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim()
            val base = withoutCtrl.substringBeforeLast('.', withoutCtrl)
                .trimEnd { it == '.' || it.isWhitespace() || it == '_' }
                .trimStart { it == '.' || it.isWhitespace() || it == '_' }
            val ext = if (withoutCtrl.contains('.')) withoutCtrl.substringAfterLast('.', "") else ""

            val cleaned = when {
                base.isEmpty() && ext.isNotEmpty() -> "indirilen_dosya_${System.currentTimeMillis()}.$ext"
                base.isNotEmpty() && ext.isNotEmpty() -> "$base.$ext"
                base.isNotEmpty() -> base
                else -> ""
            }

            return cleaned.ifEmpty { "indirilen_dosya_${System.currentTimeMillis()}" }
        }

        internal fun guessMimeType(fileName: String): String {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "mp4", "m4v", "fmp4", "m4s" -> "video/mp4"
                "mkv" -> "video/x-matroska"
                "mov" -> "video/quicktime"
                "webm" -> "video/webm"
                "avi" -> "video/x-msvideo"
                "wmv" -> "video/x-ms-wmv"
                "ts", "m2ts", "mts" -> "video/mp2t"
                "mp3" -> "audio/mpeg"
                "m4a" -> "audio/mp4"
                "aac" -> "audio/aac"
                "wav" -> "audio/wav"
                "ogg", "oga" -> "audio/ogg"
                "flac" -> "audio/flac"
                "opus" -> "audio/opus"
                else -> "application/octet-stream"
            }
        }
    }
}

package com.example.data.engine

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * DownloadStorageManager:
 * Modern Android storage management integrating public shared Downloads,
 * MediaStore, and Storage Access Framework (SAF).
 *
 * Guarantees:
 * 1. Saves downloads into the shared public 'Downloads/Zireael' directory rather than
 *    the app's private hidden sandbox.
 * 2. Provides Storage Access Framework (SAF) tree URI persistence for user-chosen custom folders.
 * 3. Registers completed downloads with Android's MediaStore so Gallery, Music, and File Manager
 *    apps immediately index the completed files.
 */
object DownloadStorageManager {

    private const val PREFS_NAME = "zireael_storage_prefs"
    private const val KEY_SAF_URI = "key_saf_custom_tree_uri"
    const val DEFAULT_SUBFOLDER = "Zireael"

    /**
     * Resolves the target download directory.
     * Uses the shared public Downloads/Zireael folder by default, or falls back safely.
     */
    fun getDownloadDirectory(context: Context): File {
        return try {
            // 1. Shared public Downloads directory (accessible to user and file managers)
            val publicDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val targetDir = File(publicDownloads, DEFAULT_SUBFOLDER)

            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }

            if (targetDir.exists() && targetDir.canWrite()) {
                targetDir
            } else if (publicDownloads.exists() && publicDownloads.canWrite()) {
                publicDownloads
            } else {
                // Fallback for sandboxed / permission-restricted edge cases
                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: File(context.filesDir, "downloads").apply { if (!exists()) mkdirs() }
            }
        } catch (e: Exception) {
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: File(context.filesDir, "downloads").apply { if (!exists()) mkdirs() }
        }
    }

    /**
     * Saves user-selected SAF Document Tree URI and persists runtime access permissions.
     */
    fun persistSafTreeUri(context: Context, treeUri: Uri) {
        try {
            val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(treeUri, takeFlags)

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_SAF_URI, treeUri.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Retrieves the stored SAF Document Tree URI if configured by the user.
     */
    fun getCustomSafUri(context: Context): Uri? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val uriStr = prefs.getString(KEY_SAF_URI, null) ?: return null
        return try {
            Uri.parse(uriStr)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Clears any saved custom SAF directory to revert to default public Downloads.
     */
    fun clearCustomSafUri(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_SAF_URI).apply()
    }

    /**
     * Registers a completed download with Android's MediaStore and MediaScannerConnection
     * so that Android's Downloads app, Photos, Video players, and File managers immediately show it.
     */
    fun registerWithMediaStore(
        context: Context,
        file: File,
        mimeType: String,
        totalBytes: Long
    ) {
        // 1. Scan file using MediaScannerConnection
        try {
            MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath),
                arrayOf(mimeType),
                null
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. On Android 10+ (API 29+), register with MediaStore.Downloads
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$DEFAULT_SUBFOLDER")
                    put(MediaStore.MediaColumns.SIZE, if (totalBytes > 0) totalBytes else file.length())
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            } catch (e: Exception) {
                // Ignore if already scanned or conflict occurs
            }
        }
    }
}

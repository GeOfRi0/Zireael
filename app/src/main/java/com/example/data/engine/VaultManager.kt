package com.example.data.engine

import android.content.ContentUris
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.MediaStore
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Authentication modes for the Encrypted Vault.
 */
enum class VaultAuthMode(
    val id: String,
    val titleTr: String,
    val descriptionTr: String
) {
    BIOMETRIC_ONLY(
        id = "BIOMETRIC_ONLY",
        titleTr = "Sadece Cihaz Biyometriği",
        descriptionTr = "Parmak izi veya yüz tanıma ile hızlı kilit açma (BiometricPrompt)"
    ),
    PIN_ONLY(
        id = "PIN_ONLY",
        titleTr = "Sadece Özel PIN / Şifre",
        descriptionTr = "Cihaz kilit ekranından tamamen bağımsız, özel kasa parolası"
    ),
    BIOMETRIC_AND_PIN(
        id = "BIOMETRIC_AND_PIN",
        titleTr = "İkisi Birden (Çift Aşama)",
        descriptionTr = "Maksimum güvenlik: Kasayı açmak için hem biyometrik doğrulama hem de PIN gerekir"
    );

    companion object {
        fun fromId(id: String?): VaultAuthMode {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: PIN_ONLY
        }
    }
}

/**
 * Model representing an encrypted file stored in the vault.
 */
data class VaultFileItem(
    val file: File,
    val originalName: String,
    val encryptedSizeBytes: Long,
    val lastModified: Long
)

/**
 * VaultManager:
 * Enterprise-grade Encrypted Vault for Zireael.
 *
 * Core capabilities:
 * 1. Hardware-backed AES-256-GCM encryption/decryption using Android KeyStore.
 * 2. 8KB constant streaming buffer to prevent OOM errors on large files.
 * 3. Three distinct authentication modes (Biometric only, Independent PIN only, or Both).
 * 4. '.tvault' file format with '.nomedia' concealment, completely hiding files from Gallery & MediaStore.
 */
class VaultManager(private val context: Context) {

    companion object {
        private const val TAG = "VaultManager"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "Zireael_Vault_AES256_Key"
        private const val PREFS_NAME = "zireael_vault_prefs"

        private const val PREF_AUTH_MODE = "pref_auth_mode"
        private const val PREF_PIN_HASH = "pref_pin_hash"
        private const val PREF_PIN_SALT = "pref_pin_salt"
        private const val PREF_VAULT_ENABLED = "pref_vault_enabled"

        const val VAULT_EXTENSION = ".tvault"
        private const val GCM_IV_LENGTH = 12 // 96-bit IV standard for AES-GCM
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val STREAM_BUFFER_SIZE = 8 * 1024 // 8 KB constant buffer to prevent OOM
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // In-memory unlock state
    private val _isUnlocked = MutableStateFlow(false)
    val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    private val vaultDir: File by lazy {
        val dir = File(context.filesDir, "vault").apply { mkdirs() }
        // Ensure .nomedia file exists to hide all contents from Android MediaScanner
        val noMedia = File(dir, ".nomedia")
        if (!noMedia.exists()) {
            try { noMedia.createNewFile() } catch (_: Exception) {}
        }
        dir
    }

    init {
        ensureKeyStoreKey()
    }

    // -------------------------------------------------------------------------
    // 1. Android KeyStore & SecretKey Management
    // -------------------------------------------------------------------------

    private fun ensureKeyStoreKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) {
            val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            if (entry != null) {
                return entry.secretKey
            }
        }

        // Generate 256-bit AES KeyStore SecretKey
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(false) // We supply our own cryptographically secure IV per file
            .build()

        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun getSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        return entry?.secretKey ?: ensureKeyStoreKey()
    }

    // -------------------------------------------------------------------------
    // 2. AES-256-GCM File Encryption & Decryption (Streaming with 8KB Buffer)
    // -------------------------------------------------------------------------

    /**
     * Encrypts a downloaded file into the private vault directory using AES-256-GCM.
     * The resulting file is renamed with the '.tvault' extension and hidden from MediaStore.
     *
     * @param sourceFile The unencrypted downloaded file.
     * @param deleteSource When true, completely removes the unencrypted file and cleans MediaStore.
     * @return Result containing the encrypted File.
     */
    suspend fun encryptFileToVault(sourceFile: File, deleteSource: Boolean = true): Result<File> = withContext(Dispatchers.IO) {
        if (!sourceFile.exists() || sourceFile.length() == 0L) {
            return@withContext Result.failure(IllegalArgumentException("Kaynak dosya bulunamadı veya boş: ${sourceFile.name}"))
        }

        val sanitizedBase = PathSecuritySanitizer.sanitizeFileName(sourceFile.name)
        val encryptedFileName = "$sanitizedBase$VAULT_EXTENSION"
        val targetVaultFile = File(vaultDir, encryptedFileName)

        try {
            // 1. Generate 12-byte secure random IV
            val iv = ByteArray(GCM_IV_LENGTH)
            SecureRandom().nextBytes(iv)

            // 2. Initialize AES-256-GCM Cipher
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.ENCRYPT_MODE, getSecretKey(), spec)

            // 3. Stream data using 8KB buffer and use {} blocks to eliminate memory leaks and OOM
            FileOutputStream(targetVaultFile).use { fos ->
                // Write the 12-byte IV at the beginning of the encrypted file
                fos.write(iv)

                CipherOutputStream(fos, cipher).use { cos ->
                    FileInputStream(sourceFile).use { fis ->
                        val buffer = ByteArray(STREAM_BUFFER_SIZE)
                        var bytesRead: Int
                        while (fis.read(buffer).also { bytesRead = it } != -1) {
                            cos.write(buffer, 0, bytesRead)
                        }
                        cos.flush()
                    }
                }
            }

            // 4. Remove original file and conceal from Android MediaStore / Gallery
            if (deleteSource) {
                removeFromMediaStore(sourceFile)
                sourceFile.delete()
            }

            Log.i(TAG, "File encrypted into vault successfully: ${targetVaultFile.name} (${targetVaultFile.length()} bytes)")
            Result.success(targetVaultFile)
        } catch (e: Exception) {
            Log.e(TAG, "Encryption failed for ${sourceFile.name}", e)
            if (targetVaultFile.exists()) {
                targetVaultFile.delete()
            }
            Result.failure(e)
        }
    }

    /**
     * Decrypts a vault file back to a target public directory using AES-256-GCM.
     * Re-registers the decrypted file with Android MediaStore.
     *
     * @param vaultFile The '.tvault' encrypted file.
     * @param targetDirectory Target folder (e.g. shared Downloads/Zireael).
     * @return Result containing the restored, unencrypted file.
     */
    suspend fun decryptFileFromVault(vaultFile: File, targetDirectory: File): Result<File> = withContext(Dispatchers.IO) {
        if (!vaultFile.exists() || vaultFile.length() <= GCM_IV_LENGTH) {
            return@withContext Result.failure(IllegalArgumentException("Kasa dosyası geçersiz veya bozuk: ${vaultFile.name}"))
        }

        val originalName = vaultFile.name.removeSuffix(VAULT_EXTENSION)
        val sanitizedName = PathSecuritySanitizer.sanitizeFileName(originalName)
        val restoredFile = File(targetDirectory, sanitizedName)
        targetDirectory.mkdirs()

        try {
            FileInputStream(vaultFile).use { fis ->
                // 1. Read 12-byte IV from file header
                val iv = ByteArray(GCM_IV_LENGTH)
                val ivRead = fis.read(iv)
                if (ivRead != GCM_IV_LENGTH) {
                    throw IllegalStateException("IV okunamadı: Kasa başlığı eksik")
                }

                // 2. Initialize AES-256-GCM Cipher
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
                cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), spec)

                // 3. Stream decryption using 8KB buffer in use {} blocks
                CipherInputStream(fis, cipher).use { cis ->
                    FileOutputStream(restoredFile).use { fos ->
                        val buffer = ByteArray(STREAM_BUFFER_SIZE)
                        var bytesRead: Int
                        while (cis.read(buffer).also { bytesRead = it } != -1) {
                            fos.write(buffer, 0, bytesRead)
                        }
                        fos.flush()
                    }
                }
            }

            // 4. Register restored file with MediaStore so it appears in Photos / Downloads
            DownloadStorageManager.registerWithMediaStore(
                context = context,
                file = restoredFile,
                mimeType = guessMimeType(restoredFile.name),
                totalBytes = restoredFile.length()
            )

            Log.i(TAG, "File decrypted from vault successfully: ${restoredFile.name}")
            Result.success(restoredFile)
        } catch (e: Exception) {
            Log.e(TAG, "Decryption failed for ${vaultFile.name}", e)
            if (restoredFile.exists()) {
                restoredFile.delete()
            }
            Result.failure(e)
        }
    }

    /**
     * Decrypts a vault file into a private, temporary cache file strictly for internal in-app playback.
     * The file is created in cacheDir/vault_stream/ with .nomedia and is NEVER exposed to external apps.
     * Callers must delete the returned file once playback is completed.
     */
    suspend fun createSecureDecryptedPlaybackFile(vaultFile: File): Result<File> = withContext(Dispatchers.IO) {
        if (!vaultFile.exists() || vaultFile.length() <= GCM_IV_LENGTH) {
            return@withContext Result.failure(IllegalArgumentException("Kasa dosyası geçersiz: ${vaultFile.name}"))
        }

        val streamCacheDir = File(context.cacheDir, "vault_stream").apply { mkdirs() }
        val noMedia = File(streamCacheDir, ".nomedia")
        if (!noMedia.exists()) {
            try { noMedia.createNewFile() } catch (_: Exception) {}
        }

        val originalName = vaultFile.name.removeSuffix(VAULT_EXTENSION)
        val sanitized = PathSecuritySanitizer.sanitizeFileName(originalName)
        val tempPlaybackFile = File(streamCacheDir, "temp_${System.currentTimeMillis()}_$sanitized")

        try {
            FileInputStream(vaultFile).use { fis ->
                val iv = ByteArray(GCM_IV_LENGTH)
                val ivRead = fis.read(iv)
                if (ivRead != GCM_IV_LENGTH) {
                    throw IllegalStateException("IV okunamadı: Kasa başlığı eksik")
                }

                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
                cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), spec)

                CipherInputStream(fis, cipher).use { cis ->
                    FileOutputStream(tempPlaybackFile).use { fos ->
                        val buffer = ByteArray(STREAM_BUFFER_SIZE)
                        var bytesRead: Int
                        while (cis.read(buffer).also { bytesRead = it } != -1) {
                            fos.write(buffer, 0, bytesRead)
                        }
                        fos.flush()
                    }
                }
            }
            Result.success(tempPlaybackFile)
        } catch (e: Exception) {
            if (tempPlaybackFile.exists()) tempPlaybackFile.delete()
            Result.failure(e)
        }
    }

    /**
     * Deletes any temporary secure decrypted playback files in cacheDir/vault_stream/.
     */
    fun cleanupSecurePlaybackFiles() {
        try {
            val streamCacheDir = File(context.cacheDir, "vault_stream")
            if (streamCacheDir.exists() && streamCacheDir.isDirectory) {
                streamCacheDir.listFiles()?.forEach { file ->
                    if (file.name.startsWith("temp_")) {
                        file.delete()
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clean up secure playback files: ${e.message}")
        }
    }

    /**
     * Completely conceals a file from Android's MediaStore index (Galleries, Downloads app).
     */
    private fun removeFromMediaStore(file: File) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                val selection = "${MediaStore.MediaColumns.DATA} = ?"
                context.contentResolver.delete(uri, selection, arrayOf(file.absolutePath))
            } else {
                val uri = MediaStore.Files.getContentUri("external")
                val selection = "${MediaStore.MediaColumns.DATA} = ?"
                context.contentResolver.delete(uri, selection, arrayOf(file.absolutePath))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to delete from MediaStore index: ${e.message}")
        }
    }

    // -------------------------------------------------------------------------
    // 3. Vault Authentication Management (Biometric, PIN, or Both)
    // -------------------------------------------------------------------------

    fun getAuthMode(): VaultAuthMode {
        val id = prefs.getString(PREF_AUTH_MODE, VaultAuthMode.PIN_ONLY.id)
        return VaultAuthMode.fromId(id)
    }

    fun setAuthMode(mode: VaultAuthMode) {
        prefs.edit().putString(PREF_AUTH_MODE, mode.id).apply()
    }

    fun isVaultConfigured(): Boolean {
        val hasPin = !prefs.getString(PREF_PIN_HASH, null).isNullOrBlank()
        val isEnabled = prefs.getBoolean(PREF_VAULT_ENABLED, false)
        return isEnabled && (hasPin || getAuthMode() == VaultAuthMode.BIOMETRIC_ONLY)
    }

    fun setPin(pin: String) {
        require(pin.isNotBlank()) { "PIN boş olamaz" }
        val salt = ByteArray(16).apply { SecureRandom().nextBytes(this) }
        val saltBase64 = Base64.encodeToString(salt, Base64.NO_WRAP)
        val hash = hashPin(pin, salt)

        prefs.edit()
            .putString(PREF_PIN_HASH, hash)
            .putString(PREF_PIN_SALT, saltBase64)
            .putBoolean(PREF_VAULT_ENABLED, true)
            .apply()
    }

    fun verifyPin(pin: String): Boolean {
        val storedHash = prefs.getString(PREF_PIN_HASH, null) ?: return false
        val saltBase64 = prefs.getString(PREF_PIN_SALT, null) ?: return false
        val salt = try { Base64.decode(saltBase64, Base64.NO_WRAP) } catch (_: Exception) { return false }
        val computedHash = hashPin(pin, salt)
        return storedHash == computedHash
    }

    private fun hashPin(pin: String, salt: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(salt)
        val digest = md.digest(pin.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(digest, Base64.NO_WRAP)
    }

    fun canUseBiometric(): Boolean {
        val biometricManager = BiometricManager.from(context)
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK
        return biometricManager.canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS
    }

    fun createBiometricPromptInfo(
        title: String = "Gizli Kasa Kilidini Aç",
        subtitle: String = "Kasaya erişmek için parmak izinizi veya yüzünüzü doğrulayın"
    ): BiometricPrompt.PromptInfo {
        return BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText("İptal")
            .build()
    }

    fun unlock() {
        _isUnlocked.value = true
    }

    fun lock() {
        _isUnlocked.value = false
    }

    // -------------------------------------------------------------------------
    // 4. Vault File Management
    // -------------------------------------------------------------------------

    fun listVaultFiles(): List<VaultFileItem> {
        val files = vaultDir.listFiles { f -> f.isFile && f.name.endsWith(VAULT_EXTENSION) } ?: emptyArray()
        return files.map { file ->
            val originalName = file.name.removeSuffix(VAULT_EXTENSION)
            VaultFileItem(
                file = file,
                originalName = originalName,
                encryptedSizeBytes = file.length(),
                lastModified = file.lastModified()
            )
        }.sortedByDescending { it.lastModified }
    }

    fun deleteVaultFile(vaultFile: File): Boolean {
        return if (vaultFile.exists() && vaultFile.parentFile == vaultDir) {
            vaultFile.delete()
        } else false
    }

    fun getVaultFileCount(): Int {
        return listVaultFiles().size
    }

    private fun guessMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "pdf" -> "application/pdf"
            "apk" -> "application/vnd.android.package-archive"
            "zip" -> "application/zip"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            else -> "application/octet-stream"
        }
    }
}
